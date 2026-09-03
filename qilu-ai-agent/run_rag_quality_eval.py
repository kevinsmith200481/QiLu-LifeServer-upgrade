from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from dotenv import load_dotenv


def main() -> int:
    parser = argparse.ArgumentParser(description="Run isolated RAG quality evaluation.")
    parser.add_argument("--provider", choices=("local", "real"), required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--collection", required=True)
    parser.add_argument("--embedding-model")
    parser.add_argument("--embedding-dimension", type=int)
    parser.add_argument("--vector-min-score", type=float, default=0.2)
    parser.add_argument("--bm25-min-score", type=float, default=0.2)
    parser.add_argument("--vector-query-prefix", default="")
    parser.add_argument("--disable-vector-query-rewrite", action="store_true")
    args = parser.parse_args()

    load_dotenv(Path(__file__).with_name(".env"), override=False)
    _configure_environment(args)

    # Retriever 在导入时解析可选向量依赖，因此必须先完成 provider 与 Milvus 环境配置。
    from rag.quality_eval import run_quality_evaluation

    result: dict[str, object]
    try:
        result = run_quality_evaluation(args.provider)
    except Exception as exc:
        result = {
            "schemaVersion": 1,
            "providerMode": args.provider,
            "externalEmbeddingUsed": args.provider == "real",
            "liveMilvusUsed": False,
            "passed": False,
            "errorCode": "RAG_QUALITY_EVAL_FAILED",
            "errorType": type(exc).__name__,
        }
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in {"normalPath", "milvusFailurePath"}}, ensure_ascii=False, indent=2))
    return 0 if result.get("passed") is True else 1


def _configure_environment(args: argparse.Namespace) -> None:
    os.environ["AI_SKIP_DOTENV"] = "true"
    os.environ["CAMPUS_KB_MODE"] = "production"
    os.environ["MILVUS_ENABLED"] = "true"
    os.environ.setdefault("MILVUS_HOST", "127.0.0.1")
    os.environ.setdefault("MILVUS_PORT", "19530")
    os.environ["MILVUS_COLLECTION"] = args.collection
    os.environ["RAG_REQUIRED_BACKENDS"] = "bm25,milvus"
    os.environ["RAG_MILVUS_RETAINED_COLLECTIONS"] = "2"
    os.environ["RAG_MILVUS_CLEANUP_GRACE_SECONDS"] = "0"
    os.environ["RAG_MIN_MILVUS_SCORE"] = str(args.vector_min_score)
    os.environ["RAG_MIN_FAISS_SCORE"] = str(args.vector_min_score)
    os.environ["RAG_MIN_BM25_SCORE"] = str(args.bm25_min_score)
    os.environ["RAG_VECTOR_QUERY_PREFIX"] = args.vector_query_prefix
    os.environ["RAG_VECTOR_QUERY_REWRITE"] = str(not args.disable_vector_query_rewrite).lower()
    os.environ["RAG_FAISS_SEARCH_THREADS"] = "1"
    os.environ["RAG_VECTOR_CANDIDATE_K"] = "12"
    os.environ["RAG_LEXICAL_CANDIDATE_K"] = "12"
    os.environ["RAG_RRF_K"] = "60"
    os.environ["RAG_MAX_CHUNKS_PER_KNOWLEDGE"] = "2"
    os.environ["RAG_CONTEXT_MAX_CHARACTERS"] = "6000"

    if args.provider == "local":
        os.environ["AI_EMBEDDING_PROVIDER"] = "local"
        os.environ["AI_EMBEDDING_MODEL"] = "local-hash-v1"
        os.environ["AI_EMBEDDING_DIMENSION"] = str(args.embedding_dimension or 384)
        os.environ["AI_EMBEDDING_ALGORITHM_REVISION"] = "local-hash-v1"
        os.environ["AI_EMBEDDING_DEPLOYMENT_REVISION"] = "rag-stage-f-local"
        os.environ["AI_LOCAL_EMBEDDINGS"] = "true"
        os.environ.setdefault("OPENAI_API_KEY", "acceptance-local-not-used")
        return

    if not os.getenv("OPENAI_API_KEY") or not (os.getenv("OPENAI_BASE_URL") or os.getenv("OPENAI_API_BASE")):
        raise ValueError("real embedding evaluation requires OPENAI_API_KEY and OPENAI_BASE_URL")
    os.environ["AI_EMBEDDING_PROVIDER"] = "openai"
    os.environ["AI_EMBEDDING_MODEL"] = args.embedding_model or os.getenv("AI_EMBEDDING_MODEL") or "text-embedding-3-small"
    os.environ["AI_EMBEDDING_DIMENSION"] = str(args.embedding_dimension or int(os.getenv("AI_EMBEDDING_DIMENSION", "1536")))
    os.environ["AI_EMBEDDING_ALGORITHM_REVISION"] = "openai-compatible-v1"
    os.environ["AI_EMBEDDING_DEPLOYMENT_REVISION"] = "rag-stage-f-real"
    os.environ["AI_LOCAL_EMBEDDINGS"] = "false"


if __name__ == "__main__":
    raise SystemExit(main())
