from __future__ import annotations

import hashlib
import threading
import time
from collections import defaultdict, deque
from typing import Dict, Optional, Tuple


class CallMetrics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._started_at = time.time()
        self._stats: Dict[str, Dict[str, object]] = defaultdict(self._new_stats)
        self._orchestrator_counts: Dict[str, int] = defaultdict(int)
        self._langgraph_nodes: Dict[str, Dict[str, object]] = defaultdict(self._new_stats)
        self._langgraph_fallbacks: Dict[str, int] = defaultdict(int)
        self._intent_classifications: Dict[Tuple[str, str, str, bool], int] = defaultdict(int)
        self._retrieval_routes: Dict[Tuple[str, str, bool], int] = defaultdict(int)
        self._memory_resolutions: Dict[Tuple[str, str], int] = defaultdict(int)
        self._memory_shadow_comparisons: Dict[Tuple[str, str], int] = defaultdict(int)
        self._rag_index_prepare: Dict[Tuple[str, str], Dict[str, float]] = defaultdict(
            lambda: {"total": 0, "totalDurationMs": 0.0, "lastDurationMs": 0.0}
        )
        self._rag_index_activate: Dict[str, int] = defaultdict(int)
        self._rag_index_cleanup: Dict[str, int] = defaultdict(int)
        self._rag_active_snapshot = {
            "knowledgeVersion": "none",
            "indexVersion": "none",
            "chunkCount": 0,
        }
        self._rag_retrieval: Dict[str, Dict[str, float]] = defaultdict(
            lambda: {"candidates": 0, "hits": 0, "totalDurationMs": 0.0, "calls": 0}
        )
        self._rag_fusion: Dict[str, int] = defaultdict(int)
        self._rag_no_source: Dict[str, int] = defaultdict(int)
        self._rag_degraded: Dict[Tuple[str, str], int] = defaultdict(int)

    @staticmethod
    def _new_stats() -> Dict[str, object]:
        return {
            "total": 0,
            "success": 0,
            "failure": 0,
            "fallback": 0,
            "totalLatencyMs": 0.0,
            "maxLatencyMs": 0.0,
            "lastLatencyMs": 0.0,
            "lastError": None,
            "latencySamplesMs": deque(maxlen=200),
        }

    def record(
        self,
        operation: str,
        elapsed_ms: float,
        success: bool,
        fallback: bool = False,
        error: Optional[BaseException] = None,
    ) -> None:
        with self._lock:
            stats = self._stats[operation]
            stats["total"] = int(stats["total"]) + 1
            stats["success"] = int(stats["success"]) + (1 if success else 0)
            stats["failure"] = int(stats["failure"]) + (0 if success else 1)
            stats["fallback"] = int(stats["fallback"]) + (1 if fallback else 0)
            stats["totalLatencyMs"] = float(stats["totalLatencyMs"]) + elapsed_ms
            stats["maxLatencyMs"] = max(float(stats["maxLatencyMs"]), elapsed_ms)
            stats["lastLatencyMs"] = elapsed_ms
            stats["lastError"] = type(error).__name__ if error else None
            samples = stats["latencySamplesMs"]
            if isinstance(samples, deque):
                samples.append(max(0.0, elapsed_ms))

    def record_orchestrator(self, mode: str) -> None:
        normalized = mode if mode in {"legacy", "langgraph"} else "legacy"
        with self._lock:
            self._orchestrator_counts[normalized] = int(self._orchestrator_counts[normalized]) + 1

    def record_intent_classification(
        self,
        mode: str,
        source: str,
        confidence_bucket: str,
        fallback: bool,
    ) -> None:
        key = (
            mode if mode in {"keyword", "semantic_shadow", "semantic"} else "keyword",
            source if source in {"memory", "semantic_model", "rule_fallback", "clarification"} else "rule_fallback",
            confidence_bucket if confidence_bucket in {"high", "accepted", "low"} else "low",
            bool(fallback),
        )
        with self._lock:
            self._intent_classifications[key] = int(self._intent_classifications[key]) + 1

    def record_retrieval_route(self, mode: str, reason: str, low_confidence: bool) -> None:
        known_reasons = {
            "intent_policy:memory",
            "intent_policy:semantic_model",
            "intent_policy:rule_fallback",
            "intent_policy:clarification",
            "low_confidence_candidates_same_mode",
            "low_confidence_candidate_mode_conflict",
            "ambiguous_intent",
        }
        key = (
            mode if mode in {"BUSINESS_ONLY", "RAG_ONLY", "HYBRID", "CLARIFY"} else "CLARIFY",
            reason if reason in known_reasons else "other",
            bool(low_confidence),
        )
        with self._lock:
            self._retrieval_routes[key] = int(self._retrieval_routes[key]) + 1

    def record_memory_resolution(self, entity_type: str, outcome: str) -> None:
        known_types = {"ticket", "appointment", "service_point", "none"}
        known_outcomes = {
            "explicit_id",
            "memory_unique_entity",
            "memory_recent_turn",
            "memory_multiple_candidates",
            "none",
        }
        key = (
            entity_type if entity_type in known_types else "none",
            outcome if outcome in known_outcomes else "none",
        )
        with self._lock:
            self._memory_resolutions[key] = int(self._memory_resolutions[key]) + 1

    def record_memory_shadow_comparison(self, comparison: Dict[str, object]) -> None:
        """只按固定维度记录一致/差异计数，不把正文、实体 ID 或工具参数写入指标。"""
        dimensions = {
            "entity": bool(comparison.get("entityMatch")),
            "route": bool(comparison.get("routeMatch")),
            "tool": bool(comparison.get("toolMatch")),
            "budget": bool(comparison.get("budgetWithinLimit")),
        }
        with self._lock:
            for dimension, matched in dimensions.items():
                key = (dimension, "match" if matched else "different")
                self._memory_shadow_comparisons[key] = int(self._memory_shadow_comparisons[key]) + 1

    def record_langgraph_node(
        self,
        node: str,
        elapsed_ms: float,
        success: bool,
        fallback_reason: Optional[str] = None,
    ) -> None:
        with self._lock:
            stats = self._langgraph_nodes[node]
            stats["total"] = int(stats["total"]) + 1
            stats["success"] = int(stats["success"]) + (1 if success else 0)
            stats["failure"] = int(stats["failure"]) + (0 if success else 1)
            stats["fallback"] = int(stats["fallback"]) + (1 if fallback_reason else 0)
            stats["totalLatencyMs"] = float(stats["totalLatencyMs"]) + elapsed_ms
            stats["maxLatencyMs"] = max(float(stats["maxLatencyMs"]), elapsed_ms)
            stats["lastLatencyMs"] = elapsed_ms
            stats["lastError"] = None if success else "LangGraphNodeError"
            samples = stats["latencySamplesMs"]
            if isinstance(samples, deque):
                samples.append(max(0.0, elapsed_ms))
            if fallback_reason:
                self._langgraph_fallbacks[fallback_reason] = int(self._langgraph_fallbacks[fallback_reason]) + 1

    def record_rag_prepare(self, backend: str, result: str, elapsed_ms: float) -> None:
        normalized_backend = backend if backend in {"bm25", "faiss", "milvus", "snapshot"} else "unknown"
        normalized_result = result if result in {"ready", "failed", "skipped"} else "failed"
        with self._lock:
            stats = self._rag_index_prepare[(normalized_backend, normalized_result)]
            stats["total"] = int(stats["total"]) + 1
            stats["totalDurationMs"] = float(stats["totalDurationMs"]) + max(0.0, elapsed_ms)
            stats["lastDurationMs"] = max(0.0, elapsed_ms)

    def record_rag_activate(self, result: str) -> None:
        normalized = result if result in {"active", "degraded", "failed", "idempotent"} else "failed"
        with self._lock:
            self._rag_index_activate[normalized] = int(self._rag_index_activate[normalized]) + 1

    def record_rag_cleanup(self, result: str) -> None:
        normalized = result if result in {"success", "failed", "pending", "skipped"} else "failed"
        with self._lock:
            self._rag_index_cleanup[normalized] = int(self._rag_index_cleanup[normalized]) + 1

    def set_rag_active_snapshot(self, knowledge_version: str, index_version: str, chunk_count: int) -> None:
        # Prometheus 标签仅保留输入版本的短摘要，避免完整哈希造成高基数和诊断信息泄漏。
        with self._lock:
            self._rag_active_snapshot = {
                "knowledgeVersion": self._version_label(knowledge_version),
                "indexVersion": self._version_label(index_version),
                "chunkCount": max(0, int(chunk_count)),
            }

    def record_rag_retrieval(
        self,
        retriever: str,
        candidate_count: int,
        hit_count: int,
        elapsed_ms: float,
    ) -> None:
        normalized = retriever if retriever in {"milvus", "faiss", "bm25"} else "unknown"
        with self._lock:
            stats = self._rag_retrieval[normalized]
            stats["candidates"] = int(stats["candidates"]) + max(0, int(candidate_count))
            stats["hits"] = int(stats["hits"]) + max(0, int(hit_count))
            stats["totalDurationMs"] = float(stats["totalDurationMs"]) + max(0.0, elapsed_ms)
            stats["calls"] = int(stats["calls"]) + 1

    def record_rag_fusion(self, vector_backend: str) -> None:
        normalized = vector_backend if vector_backend in {"milvus", "faiss", "none"} else "none"
        with self._lock:
            self._rag_fusion[normalized] = int(self._rag_fusion[normalized]) + 1

    def record_rag_no_source(self, reason: str) -> None:
        known = {"knowledge_uninitialized", "no_usable_candidates", "invalid_limit"}
        normalized = reason if reason in known else "other"
        with self._lock:
            self._rag_no_source[normalized] = int(self._rag_no_source[normalized]) + 1

    def record_rag_degraded(self, backend: str, reason: str) -> None:
        normalized_backend = backend if backend in {"milvus", "faiss", "bm25"} else "unknown"
        known_reasons = {"unavailable", "search_failed", "optional_backend_failed"}
        normalized_reason = reason if reason in known_reasons else "other"
        with self._lock:
            key = (normalized_backend, normalized_reason)
            self._rag_degraded[key] = int(self._rag_degraded[key]) + 1

    def snapshot(self) -> Dict[str, object]:
        with self._lock:
            operations = {}
            for name, stats in self._stats.items():
                total = int(stats["total"])
                avg_latency = float(stats["totalLatencyMs"]) / total if total else 0.0
                samples = stats.get("latencySamplesMs")
                operations[name] = {
                    **{key: value for key, value in stats.items() if key != "latencySamplesMs"},
                    "avgLatencyMs": round(avg_latency, 2),
                    "p95LatencyMs": round(self._percentile(samples if isinstance(samples, deque) else [], 0.95), 2),
                    "totalLatencyMs": round(float(stats["totalLatencyMs"]), 2),
                    "maxLatencyMs": round(float(stats["maxLatencyMs"]), 2),
                    "lastLatencyMs": round(float(stats["lastLatencyMs"]), 2),
                }
            langgraph_nodes = {}
            for name, stats in self._langgraph_nodes.items():
                total = int(stats["total"])
                avg_latency = float(stats["totalLatencyMs"]) / total if total else 0.0
                samples = stats.get("latencySamplesMs")
                langgraph_nodes[name] = {
                    **{key: value for key, value in stats.items() if key != "latencySamplesMs"},
                    "avgLatencyMs": round(avg_latency, 2),
                    "p95LatencyMs": round(self._percentile(samples if isinstance(samples, deque) else [], 0.95), 2),
                    "totalLatencyMs": round(float(stats["totalLatencyMs"]), 2),
                    "maxLatencyMs": round(float(stats["maxLatencyMs"]), 2),
                    "lastLatencyMs": round(float(stats["lastLatencyMs"]), 2),
                }
            return {
                "service": "qilu-ai-agent",
                "uptimeSeconds": round(time.time() - self._started_at, 2),
                "operations": operations,
                "orchestrators": {
                    "legacy": {"total": int(self._orchestrator_counts.get("legacy", 0))},
                    "langgraph": {"total": int(self._orchestrator_counts.get("langgraph", 0))},
                },
                "langgraphNodes": langgraph_nodes,
                "langgraphFallbacks": dict(self._langgraph_fallbacks),
                "intentClassifications": [
                    {
                        "mode": mode,
                        "source": source,
                        "confidenceBucket": bucket,
                        "fallback": fallback,
                        "total": total,
                    }
                    for (mode, source, bucket, fallback), total in sorted(self._intent_classifications.items())
                ],
                "retrievalRoutes": [
                    {
                        "mode": mode,
                        "reason": reason,
                        "lowConfidence": low_confidence,
                        "total": total,
                    }
                    for (mode, reason, low_confidence), total in sorted(self._retrieval_routes.items())
                ],
                "memoryResolutions": [
                    {"entityType": entity_type, "outcome": outcome, "total": total}
                    for (entity_type, outcome), total in sorted(self._memory_resolutions.items())
                ],
                "memoryShadowComparisons": [
                    {"dimension": dimension, "result": result, "total": total}
                    for (dimension, result), total in sorted(self._memory_shadow_comparisons.items())
                ],
                "ragIndexPrepare": [
                    {
                        "backend": backend,
                        "result": result,
                        "total": int(stats["total"]),
                        "totalDurationMs": round(float(stats["totalDurationMs"]), 2),
                        "lastDurationMs": round(float(stats["lastDurationMs"]), 2),
                    }
                    for (backend, result), stats in sorted(self._rag_index_prepare.items())
                ],
                "ragIndexActivate": dict(self._rag_index_activate),
                "ragIndexCleanup": dict(self._rag_index_cleanup),
                "ragActiveSnapshot": dict(self._rag_active_snapshot),
                "ragRetrieval": [
                    {
                        "retriever": retriever,
                        "candidates": int(stats["candidates"]),
                        "hits": int(stats["hits"]),
                        "durationMs": round(float(stats["totalDurationMs"]), 2),
                        "calls": int(stats["calls"]),
                    }
                    for retriever, stats in sorted(self._rag_retrieval.items())
                ],
                "ragFusion": dict(self._rag_fusion),
                "ragNoSource": dict(self._rag_no_source),
                "ragDegraded": [
                    {"backend": backend, "reason": reason, "total": total}
                    for (backend, reason), total in sorted(self._rag_degraded.items())
                ],
            }

    def prometheus(self) -> str:
        snapshot = self.snapshot()
        lines = [
            "# HELP qilu_ai_agent_operation_total Total AI Agent operation calls.",
            "# TYPE qilu_ai_agent_operation_total counter",
        ]
        operations = snapshot.get("operations", {})
        if isinstance(operations, dict):
            for name, stats in operations.items():
                if not isinstance(stats, dict):
                    continue
                label = self._label(name)
                lines.append(f'qilu_ai_agent_operation_total{{operation="{label}"}} {int(stats.get("total") or 0)}')
                lines.append(f'qilu_ai_agent_operation_success_total{{operation="{label}"}} {int(stats.get("success") or 0)}')
                lines.append(f'qilu_ai_agent_operation_failure_total{{operation="{label}"}} {int(stats.get("failure") or 0)}')
                lines.append(f'qilu_ai_agent_operation_fallback_total{{operation="{label}"}} {int(stats.get("fallback") or 0)}')
                lines.append(f'qilu_ai_agent_operation_latency_avg_ms{{operation="{label}"}} {float(stats.get("avgLatencyMs") or 0)}')
                lines.append(f'qilu_ai_agent_operation_latency_p95_ms{{operation="{label}"}} {float(stats.get("p95LatencyMs") or 0)}')
                lines.append(f'qilu_ai_agent_operation_latency_max_ms{{operation="{label}"}} {float(stats.get("maxLatencyMs") or 0)}')
        lines.extend([
            "# HELP agent_orchestrator_total Total Agent chat calls by orchestrator mode.",
            "# TYPE agent_orchestrator_total counter",
        ])
        orchestrators = snapshot.get("orchestrators", {})
        if isinstance(orchestrators, dict):
            for mode in ["legacy", "langgraph"]:
                stats = orchestrators.get(mode, {})
                total = stats.get("total", 0) if isinstance(stats, dict) else 0
                lines.append(f'agent_orchestrator_total{{mode="{mode}"}} {int(total or 0)}')
        lines.extend([
            "# HELP langgraph_node_total Total LangGraph node executions.",
            "# TYPE langgraph_node_total counter",
        ])
        langgraph_nodes = snapshot.get("langgraphNodes", {})
        if isinstance(langgraph_nodes, dict):
            for name, stats in langgraph_nodes.items():
                if not isinstance(stats, dict):
                    continue
                label = self._label(name)
                lines.append(f'langgraph_node_total{{node="{label}"}} {int(stats.get("total") or 0)}')
                lines.append(f'langgraph_node_latency_ms{{node="{label}"}} {float(stats.get("avgLatencyMs") or 0)}')
                lines.append(f'langgraph_node_latency_p95_ms{{node="{label}"}} {float(stats.get("p95LatencyMs") or 0)}')
        lines.extend([
            "# HELP langgraph_fallback_total Total LangGraph fallbacks by reason.",
            "# TYPE langgraph_fallback_total counter",
        ])
        fallbacks = snapshot.get("langgraphFallbacks", {})
        known_reasons = ["NO_SOURCE", "KNOWLEDGE_NOT_SYNCED", "PERMISSION_DENIED", "TOOL_TIMEOUT", "TOOL_UNAVAILABLE"]
        if isinstance(fallbacks, dict):
            for reason in sorted(set(known_reasons) | set(str(key) for key in fallbacks.keys())):
                lines.append(f'langgraph_fallback_total{{reason="{self._label(reason)}"}} {int(fallbacks.get(reason, 0) or 0)}')
        lines.extend([
            "# HELP intent_classification_total Total semantic intent classifications by bounded routing attributes.",
            "# TYPE intent_classification_total counter",
        ])
        classifications = snapshot.get("intentClassifications", [])
        if isinstance(classifications, list):
            for item in classifications:
                if not isinstance(item, dict):
                    continue
                lines.append(
                    'intent_classification_total{mode="%s",source="%s",confidence_bucket="%s",fallback="%s"} %d'
                    % (
                        self._label(str(item.get("mode") or "")),
                        self._label(str(item.get("source") or "")),
                        self._label(str(item.get("confidenceBucket") or "")),
                        str(bool(item.get("fallback"))).lower(),
                        int(item.get("total") or 0),
                    )
                )
        lines.extend([
            "# HELP retrieval_route_total Total deterministic retrieval routes by bounded policy attributes.",
            "# TYPE retrieval_route_total counter",
        ])
        routes = snapshot.get("retrievalRoutes", [])
        if isinstance(routes, list):
            for item in routes:
                if not isinstance(item, dict):
                    continue
                lines.append(
                    'retrieval_route_total{mode="%s",reason="%s",low_confidence="%s"} %d'
                    % (
                        self._label(str(item.get("mode") or "")),
                        self._label(str(item.get("reason") or "")),
                        str(bool(item.get("lowConfidence"))).lower(),
                        int(item.get("total") or 0),
                    )
                )
        lines.extend([
            "# HELP ai_memory_entity_resolution_total Total bounded Memory entity resolutions.",
            "# TYPE ai_memory_entity_resolution_total counter",
        ])
        resolutions = snapshot.get("memoryResolutions", [])
        if isinstance(resolutions, list):
            for item in resolutions:
                if not isinstance(item, dict):
                    continue
                lines.append(
                    'ai_memory_entity_resolution_total{entityType="%s",outcome="%s"} %d'
                    % (
                        self._label(str(item.get("entityType") or "none")),
                        self._label(str(item.get("outcome") or "none")),
                        int(item.get("total") or 0),
                    )
                )
        lines.extend([
            "# HELP ai_memory_shadow_comparison_total Total bounded legacy/v2 shadow comparisons.",
            "# TYPE ai_memory_shadow_comparison_total counter",
        ])
        comparisons = snapshot.get("memoryShadowComparisons", [])
        if isinstance(comparisons, list):
            for item in comparisons:
                if not isinstance(item, dict):
                    continue
                lines.append(
                    'ai_memory_shadow_comparison_total{dimension="%s",result="%s"} %d'
                    % (
                        self._label(str(item.get("dimension") or "")),
                        self._label(str(item.get("result") or "")),
                        int(item.get("total") or 0),
                    )
                )
        lines.extend([
            "# HELP rag_index_prepare_total RAG candidate prepare attempts by backend and result.",
            "# TYPE rag_index_prepare_total counter",
        ])
        prepare_durations: Dict[str, float] = {}
        for item in snapshot.get("ragIndexPrepare", []):
            if not isinstance(item, dict):
                continue
            backend = self._label(str(item.get("backend") or "unknown"))
            result = self._label(str(item.get("result") or "failed"))
            lines.append(
                f'rag_index_prepare_total{{backend="{backend}",result="{result}"}} '
                f'{int(item.get("total") or 0)}'
            )
            prepare_durations[backend] = float(item.get("lastDurationMs") or 0)
        for backend, duration in sorted(prepare_durations.items()):
            lines.append(
                f'rag_index_prepare_duration_ms{{backend="{backend}"}} '
                f'{duration}'
            )
        for result, total in sorted(dict(snapshot.get("ragIndexActivate") or {}).items()):
            lines.append(
                f'rag_index_activate_total{{result="{self._label(str(result))}"}} {int(total or 0)}'
            )
        for result, total in sorted(dict(snapshot.get("ragIndexCleanup") or {}).items()):
            lines.append(
                f'rag_index_cleanup_total{{result="{self._label(str(result))}"}} {int(total or 0)}'
            )
        active = snapshot.get("ragActiveSnapshot", {})
        if isinstance(active, dict):
            knowledge_version = self._label(str(active.get("knowledgeVersion") or "none"))
            index_version = self._label(str(active.get("indexVersion") or "none"))
            lines.append(
                f'rag_active_snapshot_info{{knowledge_version="{knowledge_version}",'
                f'index_version="{index_version}"}} 1'
            )
            lines.append(f'rag_chunk_count {int(active.get("chunkCount") or 0)}')
        for item in snapshot.get("ragRetrieval", []):
            if not isinstance(item, dict):
                continue
            retriever = self._label(str(item.get("retriever") or "unknown"))
            lines.append(
                f'rag_retrieval_candidates{{retriever="{retriever}"}} '
                f'{int(item.get("candidates") or 0)}'
            )
            lines.append(
                f'rag_retrieval_hits{{retriever="{retriever}"}} {int(item.get("hits") or 0)}'
            )
            lines.append(
                f'rag_retrieval_duration_ms{{retriever="{retriever}"}} '
                f'{float(item.get("durationMs") or 0)}'
            )
        for backend, total in sorted(dict(snapshot.get("ragFusion") or {}).items()):
            lines.append(
                f'rag_fusion_total{{vector_backend="{self._label(str(backend))}"}} {int(total or 0)}'
            )
        for reason, total in sorted(dict(snapshot.get("ragNoSource") or {}).items()):
            lines.append(
                f'rag_no_source_total{{reason="{self._label(str(reason))}"}} {int(total or 0)}'
            )
        for item in snapshot.get("ragDegraded", []):
            if not isinstance(item, dict):
                continue
            lines.append(
                'rag_degraded_total{backend="%s",reason="%s"} %d'
                % (
                    self._label(str(item.get("backend") or "unknown")),
                    self._label(str(item.get("reason") or "other")),
                    int(item.get("total") or 0),
                )
            )
        lines.append(f'qilu_ai_agent_uptime_seconds {float(snapshot.get("uptimeSeconds") or 0)}')
        return "\n".join(lines) + "\n"

    @staticmethod
    def _percentile(samples, percentile: float) -> float:
        if not samples:
            return 0.0
        values = sorted(float(value) for value in samples)
        index = int((len(values) - 1) * percentile)
        return values[index]

    @staticmethod
    def _label(value: str) -> str:
        return str(value).replace("\\", "\\\\").replace('"', '\\"')

    @staticmethod
    def _version_label(value: str) -> str:
        if not value or value == "uninitialized":
            return "none"
        return hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:12]


metrics = CallMetrics()


def now() -> float:
    return time.perf_counter()


def elapsed_ms(start: float) -> float:
    return (time.perf_counter() - start) * 1000.0
