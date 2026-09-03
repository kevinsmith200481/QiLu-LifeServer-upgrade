from __future__ import annotations

import math
import statistics
import time
from dataclasses import dataclass
from typing import Dict, Iterable, List, Mapping, Sequence
from unittest.mock import patch

from rag.quality_fixture import QUALITY_KNOWLEDGE_VERSION, build_quality_fixture
from rag.retriever import CampusKnowledgeRetriever, KnowledgeHit


@dataclass
class TraceCapture:
    attributes: Dict[str, object]

    def __init__(self) -> None:
        self.attributes = {}

    def set_attribute(self, name: str, value: object) -> None:
        self.attributes[name] = value


def run_quality_evaluation(provider_mode: str) -> Dict[str, object]:
    documents, cases = build_quality_fixture()
    if len(documents) < 12 or len(cases) < 60:
        raise ValueError("RAG quality fixture must contain at least 12 documents and 60 cases")

    retriever = CampusKnowledgeRetriever()
    reload_started = time.perf_counter()
    reload_result = retriever.reload_documents(
        documents,
        knowledge_version=QUALITY_KNOWLEDGE_VERSION,
        knowledge_source="ai_knowledge",
    )
    sync_duration_ms = (time.perf_counter() - reload_started) * 1000.0
    if not reload_result.success or not reload_result.activated:
        raise RuntimeError("quality fixture candidate was not activated")

    snapshot = retriever._capture_active_snapshot()
    cleanup_passed = False
    try:
        normal = evaluate_path(retriever, cases, "milvus-normal")

        # 只故障化查询连接，保留已发布的 active Collection，用于证明回退读取相同 chunk 集合。
        with patch.object(retriever.milvus, "connect", return_value=False):
            retriever.milvus.connected = False
            retriever.milvus.last_error = "InjectedMilvusUnavailable"
            fallback = evaluate_path(retriever, cases, "milvus-failure")
    finally:
        cleanup_passed = retriever.milvus.clear()
        retriever.milvus.cleanup_superseded(force=True)
        cleanup_passed = cleanup_passed and retriever.milvus.last_cleanup_error is None

    normal_recall = float(normal["metrics"]["recallAt3"])
    fallback_recall = float(fallback["metrics"]["recallAt3"])
    recall_drop = max(0.0, normal_recall - fallback_recall)
    live_milvus = (
        snapshot.backend_states.get("milvus") == "READY"
        and bool(snapshot.milvus_physical_collection)
        and normal["vectorBackends"].get("milvus", 0) > 0
    )
    fallback_used = fallback["vectorBackends"].get("faiss", 0) > 0

    structural_gates = {
        "minimumCaseCount": len(cases) >= 60,
        "executedCaseCountMatches": normal["caseCount"] == len(cases) == fallback["caseCount"],
        "sourceVersionAccuracy": normal["metrics"]["sourceVersionAccuracy"] == 1.0
        and fallback["metrics"]["sourceVersionAccuracy"] == 1.0,
        "engineeringContentLeakCount": normal["metrics"]["engineeringContentLeakCount"] == 0
        and fallback["metrics"]["engineeringContentLeakCount"] == 0,
        "longDocumentRecall": normal["metrics"]["longDocumentRecallAt3"] == 1.0
        and fallback["metrics"]["longDocumentRecallAt3"] == 1.0,
        "liveMilvusNormalPath": live_milvus,
        "faissFallbackPath": fallback_used,
        "fallbackRecallDrop": recall_drop <= 0.05,
        "milvusCleanup": cleanup_passed,
    }
    accuracy_gates: Dict[str, bool] = {}
    if provider_mode == "real":
        accuracy_gates = {
            "recallAt3": normal["metrics"]["recallAt3"] >= 0.95,
            "mrr": normal["metrics"]["mrr"] >= 0.85,
            "ndcgAt3": normal["metrics"]["ndcgAt3"] >= 0.90,
            "noAnswerDecisionAccuracy": normal["metrics"]["noAnswerDecisionAccuracy"] >= 0.95,
            "criticalSafetyAccuracy": normal["metrics"]["criticalSafetyAccuracy"] == 1.0,
        }

    result = {
        "schemaVersion": 1,
        "providerMode": provider_mode,
        "externalEmbeddingUsed": provider_mode == "real",
        "liveMilvusUsed": live_milvus,
        "knowledgeVersion": snapshot.knowledge_version,
        "indexVersion": snapshot.index_version,
        "documentCount": len(documents),
        "chunkCount": len(snapshot.chunks),
        "chunkManifest": [chunk.metadata() for chunk in snapshot.chunks],
        "indexFingerprint": _snapshot_index_fingerprint(retriever, snapshot),
        "caseCount": len(cases),
        "syncDurationMs": round(sync_duration_ms, 3),
        "backendStates": dict(snapshot.backend_states),
        "normalPath": normal,
        "milvusFailurePath": fallback,
        "recallAt3Drop": round(recall_drop, 6),
        "structuralGates": structural_gates,
        "accuracyGates": accuracy_gates,
    }
    result["passed"] = all(structural_gates.values()) and all(accuracy_gates.values())
    return result


def _snapshot_index_fingerprint(
    retriever: CampusKnowledgeRetriever,
    snapshot: object,
) -> Dict[str, object]:
    """导出不含密钥和物理 Collection 原名的正式索引配置指纹。"""
    fingerprints = snapshot.fingerprints
    if fingerprints is None:
        raise RuntimeError("quality snapshot does not contain index fingerprints")
    return {
        "knowledgeVersion": snapshot.knowledge_version,
        "indexVersion": snapshot.index_version,
        "embeddingProvider": retriever.embedding_descriptor.provider,
        "embeddingModel": retriever.embedding_descriptor.model,
        "embeddingDimension": retriever.embedding_descriptor.dimension,
        "embeddingAlgorithmRevision": retriever.embedding_descriptor.algorithm_revision,
        "embeddingDeploymentRevision": retriever.embedding_descriptor.deployment_revision,
        "embeddingFingerprint": fingerprints.embedding_fingerprint,
        "chunkSize": retriever.chunk_config.size,
        "chunkOverlap": retriever.chunk_config.overlap,
        "chunkUnit": retriever.chunk_config.unit,
        "chunkAlgorithm": retriever.chunk_config.algorithm,
        "chunkFingerprint": fingerprints.chunk_fingerprint,
        "lexicalImplementation": retriever.lexical_descriptor.implementation,
        "lexicalRevision": retriever.lexical_descriptor.tokenizer_revision,
        "lexicalParameters": retriever.lexical_descriptor.parameters,
        "lexicalFingerprint": fingerprints.lexical_fingerprint,
        "indexSchemaVersion": fingerprints.index_schema_version,
        "backendStates": dict(snapshot.backend_states),
    }


def evaluate_path(
    retriever: CampusKnowledgeRetriever,
    cases: Sequence[Mapping[str, object]],
    path_name: str,
) -> Dict[str, object]:
    results: List[Dict[str, object]] = []
    latencies: List[float] = []
    vector_backends: Dict[str, int] = {}
    candidate_totals = {"vector": 0, "lexical": 0, "filtered": 0, "final": 0}

    for case in cases:
        trace = TraceCapture()
        started = time.perf_counter()
        hits = retriever.retrieve_documents(str(case["question"]), limit=3, trace_span=trace)
        latency_ms = (time.perf_counter() - started) * 1000.0
        latencies.append(latency_ms)
        vector_backend = str(trace.attributes.get("ai.rag.vector_backend", "none"))
        vector_backends[vector_backend] = vector_backends.get(vector_backend, 0) + 1
        for key, attribute in (
            ("vector", "ai.rag.vector_candidate_count"),
            ("lexical", "ai.rag.lexical_candidate_count"),
            ("filtered", "ai.rag.filtered_candidate_count"),
            ("final", "ai.rag.final_hit_count"),
        ):
            candidate_totals[key] += int(trace.attributes.get(attribute, 0) or 0)
        results.append(_evaluate_case(case, hits, latency_ms, vector_backend))

    metrics = _aggregate_metrics(results, latencies)
    count = max(1, len(results))
    return {
        "name": path_name,
        "caseCount": len(results),
        "metrics": metrics,
        "averageCandidateCounts": {
            key: round(value / count, 4) for key, value in candidate_totals.items()
        },
        "vectorBackends": vector_backends,
        "cases": results,
    }


def _evaluate_case(
    case: Mapping[str, object],
    hits: Sequence[KnowledgeHit],
    latency_ms: float,
    vector_backend: str,
) -> Dict[str, object]:
    expected = {int(value) for value in case["expectedKnowledgeIds"]}
    forbidden = {int(value) for value in case["forbiddenKnowledgeIds"]}
    knowledge_ids = [int(hit.metadata["knowledgeId"]) for hit in hits]
    contents = "\n".join(hit.content for hit in hits)
    first_relevant_rank = next(
        (rank for rank, knowledge_id in enumerate(knowledge_ids, start=1) if knowledge_id in expected),
        None,
    )
    source_version_valid = all(
        hit.metadata.get("source") == "ai_knowledge"
        and hit.metadata.get("knowledgeVersion") == QUALITY_KNOWLEDGE_VERSION
        and bool(hit.metadata.get("indexVersion"))
        for hit in hits
    )
    forbidden_hit = any(knowledge_id in forbidden for knowledge_id in knowledge_ids)
    forbidden_term_hit = any(str(term) in contents for term in case["forbiddenTerms"])
    engineering_content_leak = any(
        hit.metadata.get("source") != "ai_knowledge" for hit in hits
    ) or (case["category"] == "engineering-boundary" and forbidden_term_hit)
    required_terms_present = all(str(term) in contents for term in case["requiredTerms"])
    answerable = bool(case["answerable"])
    no_answer_correct = bool(hits) == answerable
    return {
        "caseId": case["caseId"],
        "category": case["category"],
        "answerable": answerable,
        "expectedKnowledgeIds": sorted(expected),
        "actualKnowledgeIds": knowledge_ids,
        "actualHits": [
            {
                "knowledgeId": int(hit.metadata["knowledgeId"]),
                "chunkIndexes": list(hit.metadata.get("chunkIndexes") or [hit.metadata.get("chunkIndex")]),
                "retrievers": list(hit.retrievers),
                "rawScores": dict(hit.retriever_scores),
                "normalizedScores": dict(hit.normalized_retriever_scores),
                "fusionScore": hit.fusion_score,
            }
            for hit in hits
        ],
        "firstRelevantRank": first_relevant_rank,
        "recallAt1": first_relevant_rank == 1,
        "recallAt3": first_relevant_rank is not None and first_relevant_rank <= 3,
        "sourceVersionValid": source_version_valid,
        "noAnswerDecisionCorrect": no_answer_correct,
        "forbiddenHit": forbidden_hit,
        "forbiddenTermHit": forbidden_term_hit,
        "engineeringContentLeak": engineering_content_leak,
        "requiredTermsPresent": required_terms_present,
        "vectorBackend": vector_backend,
        "latencyMs": round(latency_ms, 3),
    }


def _aggregate_metrics(
    results: Sequence[Mapping[str, object]],
    latencies: Sequence[float],
) -> Dict[str, object]:
    answerable = [result for result in results if result["answerable"]]
    no_answer = [result for result in results if not result["answerable"]]
    critical = [
        result for result in no_answer
        if result["category"] in {"engineering-boundary", "source-boundary", "version-boundary", "safety"}
    ]
    long_document = [result for result in answerable if str(result["category"]).startswith("long-")]
    source_count = sum(len(result["actualKnowledgeIds"]) for result in results)
    valid_source_count = sum(
        len(result["actualKnowledgeIds"]) for result in results if result["sourceVersionValid"]
    )
    reciprocal_ranks = [
        0.0 if result["firstRelevantRank"] is None else 1.0 / int(result["firstRelevantRank"])
        for result in answerable
    ]
    ndcg_values = [_ndcg_at_3(result) for result in answerable]
    return {
        "recallAt1": _mean_bool(result["recallAt1"] for result in answerable),
        "recallAt3": _mean_bool(result["recallAt3"] for result in answerable),
        "mrr": round(statistics.fmean(reciprocal_ranks), 6) if reciprocal_ranks else 1.0,
        "ndcgAt3": round(statistics.fmean(ndcg_values), 6) if ndcg_values else 1.0,
        "sourceVersionAccuracy": round(valid_source_count / source_count, 6) if source_count else 1.0,
        "noAnswerDecisionAccuracy": _mean_bool(
            result["noAnswerDecisionCorrect"] for result in no_answer
        ),
        "criticalSafetyAccuracy": _mean_bool(
            result["noAnswerDecisionCorrect"]
            and not result["forbiddenHit"]
            and not result["forbiddenTermHit"]
            for result in critical
        ),
        "engineeringContentLeakCount": sum(
            1 for result in results if result["engineeringContentLeak"]
        ),
        "longDocumentRecallAt3": _mean_bool(
            result["recallAt3"] for result in long_document
        ),
        "requiredTermAccuracy": _mean_bool(
            result["requiredTermsPresent"] for result in answerable
        ),
        "p50RetrievalLatencyMs": round(_percentile(latencies, 0.50), 3),
        "p95RetrievalLatencyMs": round(_percentile(latencies, 0.95), 3),
    }


def _ndcg_at_3(result: Mapping[str, object]) -> float:
    rank = result["firstRelevantRank"]
    if rank is None or int(rank) > 3:
        return 0.0
    return 1.0 / math.log2(int(rank) + 1.0)


def _mean_bool(values: Iterable[object]) -> float:
    collected = [bool(value) for value in values]
    return round(sum(collected) / len(collected), 6) if collected else 1.0


def _percentile(values: Sequence[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * ratio) - 1))
    return ordered[index]
