from __future__ import annotations

import argparse
import json
import os
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description="Run RAG stage F reliability and performance evaluation.")
    parser.add_argument("--output", required=True)
    parser.add_argument("--runtime-directory", required=True)
    parser.add_argument("--collection", required=True)
    parser.add_argument("--baseline", default="rag_baseline_cases.json")
    parser.add_argument("--embedding-model", required=True)
    parser.add_argument("--embedding-dimension", type=int, required=True)
    parser.add_argument("--external-sync-maximum-ms", type=float, default=45000.0)
    args = parser.parse_args()

    _configure_local(milvus_enabled=False, collection=args.collection)
    from rag.resilience_eval import (
        run_local_performance,
        run_local_sync_and_pointer,
        run_external_sync_100,
        run_twenty_live_reloads,
    )

    run_errors: dict[str, str] = {}
    performance = None
    sync = None
    external_sync = None
    reloads = None
    try:
        performance = run_local_performance(Path(args.baseline).resolve())
    except Exception as exc:
        run_errors["retrievalOnly"] = type(exc).__name__
    try:
        sync = run_local_sync_and_pointer()
    except Exception as exc:
        run_errors["localSync100"] = type(exc).__name__
    try:
        _configure_external(args.collection, args.embedding_model, args.embedding_dimension)
        external_sync = run_external_sync_100(args.collection, args.external_sync_maximum_ms)
    except Exception as exc:
        run_errors["externalSync100"] = type(exc).__name__
    try:
        _configure_local(milvus_enabled=True, collection=args.collection)
        reloads = run_twenty_live_reloads(Path(args.runtime_directory).resolve(), args.collection)
    except Exception as exc:
        run_errors["continuousReloads"] = type(exc).__name__

    result = {
        "schemaVersion": 1,
        "providerMode": "local-and-real",
        "externalEmbeddingUsed": external_sync is not None,
        "liveMilvusUsed": external_sync is not None and reloads is not None,
        "retrievalOnly": performance,
        "localSync100": sync,
        "externalSync100": external_sync,
        "continuousReloads": reloads,
        "errorTypes": run_errors,
    }
    result["passed"] = (
        not run_errors
        and performance is not None and performance["passed"]
        and sync is not None and sync["passed"]
        and external_sync is not None and external_sync["passed"]
        and reloads is not None and reloads["passed"]
    )
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "continuousReloads"}, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 1


def _configure_local(milvus_enabled: bool, collection: str) -> None:
    os.environ["AI_SKIP_DOTENV"] = "true"
    os.environ["CAMPUS_KB_MODE"] = "production"
    os.environ["AI_EMBEDDING_PROVIDER"] = "local"
    os.environ["AI_EMBEDDING_MODEL"] = "local-hash-v1"
    os.environ["AI_EMBEDDING_DIMENSION"] = "384"
    os.environ["AI_EMBEDDING_ALGORITHM_REVISION"] = "local-hash-v1"
    os.environ["AI_EMBEDDING_DEPLOYMENT_REVISION"] = "rag-stage-f-resilience"
    os.environ["AI_LOCAL_EMBEDDINGS"] = "true"
    os.environ["OPENAI_API_KEY"] = "acceptance-local-not-used"
    os.environ["MILVUS_ENABLED"] = str(milvus_enabled).lower()
    os.environ["MILVUS_HOST"] = "127.0.0.1"
    os.environ["MILVUS_PORT"] = "19530"
    os.environ["MILVUS_COLLECTION"] = collection
    os.environ["RAG_REQUIRED_BACKENDS"] = "bm25,milvus" if milvus_enabled else "bm25"
    os.environ["RAG_MILVUS_RETAINED_COLLECTIONS"] = "2"
    os.environ["RAG_MILVUS_CLEANUP_GRACE_SECONDS"] = "0"
    os.environ["RAG_MIN_MILVUS_SCORE"] = "0.2"
    os.environ["RAG_MIN_FAISS_SCORE"] = "0.2"
    os.environ["RAG_MIN_BM25_SCORE"] = "0.2"
    os.environ["RAG_FAISS_SEARCH_THREADS"] = "1"


def _configure_external(collection: str, model: str, dimension: int) -> None:
    if not os.getenv("OPENAI_API_KEY") or not (os.getenv("OPENAI_BASE_URL") or os.getenv("OPENAI_API_BASE")):
        raise ValueError("external sync evaluation requires OPENAI_API_KEY and OPENAI_BASE_URL")
    _configure_local(milvus_enabled=True, collection=collection)
    # 外部同步必须显式覆盖本地 Hash 标记，保证证据对应真实兼容 Embedding 服务。
    os.environ["AI_EMBEDDING_PROVIDER"] = "openai"
    os.environ["AI_EMBEDDING_MODEL"] = model
    os.environ["AI_EMBEDDING_DIMENSION"] = str(dimension)
    os.environ["AI_EMBEDDING_ALGORITHM_REVISION"] = "openai-compatible-v1"
    os.environ["AI_EMBEDDING_DEPLOYMENT_REVISION"] = "rag-stage-f-external-sync-100"
    os.environ["AI_LOCAL_EMBEDDINGS"] = "false"


if __name__ == "__main__":
    raise SystemExit(main())
