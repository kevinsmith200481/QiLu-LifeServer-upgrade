from __future__ import annotations

import copy
import hmac
import os
import re
import sqlite3
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional, Sequence, Tuple

try:
    from langgraph.checkpoint.sqlite import SqliteSaver
except ImportError:  # Checkpoint can be disabled for the legacy runtime.
    SqliteSaver = None  # type: ignore[assignment]

from app.schemas import CampusAssistantRequest


SCHEMA_VERSION = "3"
_URL_PATTERN = re.compile(r"https?://[^\s,;，；]+", re.IGNORECASE)
_PHONE_PATTERN = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
_SECRET_PATTERN = re.compile(
    r"(?i)(api[_-]?key|token|password|secret)\s*[:=]\s*[^\s,;]+"
)
_ATTACHMENT_URL_PATTERN = re.compile(
    r"(?i)[^\s,;，；]*(?:attachment|附件)[^\s,;，；]*"
)
_SAFE_STATE_CHANNELS = {
    "request",
    "trace_id",
    "orchestrator",
    "memory_summary",
    "intent_classification",
    "intent_decision",
    "intent_entities",
    "intent_source",
    "intent_confidence",
    "intent_router_mode",
    "classifier_fallback_reason",
    "memory_shadow_comparison",
    "retrieval_mode",
    "routing_reason",
    "low_confidence",
    "permission_denied",
    "knowledge_sources",
    "intent",
    "confidence",
    "need_create_ticket",
    "planned_tools",
    "planner_mode",
    "planner_fallback_reason",
    "model_name",
    "finish_reason",
    "schema_validation",
    "native_round",
    "native_result_cache",
    "native_pending_generation",
    "current_round_calls",
    "current_round_results",
    "tool_results",
    "business_cards",
    "action_drafts",
    "fallback_reason",
    "execution_records",
    "errors",
    "user_input",
    "escalate",
    "knowledge_initialized",
    "user_id",
    "role",
    "business_tool_results",
    "memory_context",
    "agent_plan",
    "generation_record",
    "fallback_records",
    "request_id",
    "tool_execution_keys",
}
_GRAPH_TRIGGER_CHANNELS = {
    "load_memory",
    "classify_intent",
    "select_retrieval_policy",
    "route_query",
    "retrieve",
    "check_escalation",
    "detect_intent",
    "plan_tools",
    "execute_tools",
    "generate",
    "generate_clarification",
    "fallback",
    "finalize_response",
}


class CheckpointConflictError(RuntimeError):
    """Raised when a second request attempts to execute the same graph thread."""

    error_code = "CHECKPOINT_THREAD_CONFLICT"


@dataclass(frozen=True)
class CheckpointSettings:
    enabled: bool
    path: Path
    ttl_seconds: int
    max_checkpoints_per_thread: int
    cleanup_interval_seconds: int
    schema_version: str
    internal_token: str

    @classmethod
    def from_env(cls) -> "CheckpointSettings":
        default_path = Path(__file__).resolve().parents[1] / "data" / "checkpoints" / "langgraph.sqlite3"
        return cls(
            enabled=_env_bool("AI_CHECKPOINT_ENABLED", False),
            path=Path(os.getenv("AI_CHECKPOINT_PATH", str(default_path))).expanduser().resolve(),
            ttl_seconds=_positive_int("AI_CHECKPOINT_TTL_SECONDS", 7 * 24 * 60 * 60),
            max_checkpoints_per_thread=_positive_int("AI_CHECKPOINT_MAX_RETAINED", 40),
            cleanup_interval_seconds=_positive_int("AI_CHECKPOINT_CLEANUP_INTERVAL_SECONDS", 300),
            schema_version=os.getenv("AI_CHECKPOINT_SCHEMA_VERSION", SCHEMA_VERSION).strip() or SCHEMA_VERSION,
            internal_token=os.getenv("AI_CHECKPOINT_INTERNAL_TOKEN", ""),
        )


class SanitizingSqliteSaver(SqliteSaver if SqliteSaver is not None else object):  # type: ignore[misc]
    """SqliteSaver that persists only the stage-7 recovery whitelist."""

    def __init__(self, conn: sqlite3.Connection, max_checkpoints_per_thread: int) -> None:
        if SqliteSaver is None:
            raise RuntimeError("langgraph-checkpoint-sqlite is not installed")
        super().__init__(conn)
        self.max_checkpoints_per_thread = max_checkpoints_per_thread

    def put(self, config, checkpoint, metadata, new_versions):
        safe_checkpoint = copy.copy(checkpoint)
        safe_checkpoint["channel_values"] = {
            channel: _sanitize_channel(channel, value)
            for channel, value in checkpoint.get("channel_values", {}).items()
            if _keep_channel(channel)
        }
        # Pending sends may contain complete task inputs. The graph used here has
        # no Send-based fan-out, so dropping them is both safe and deterministic.
        safe_checkpoint["pending_sends"] = []
        safe_metadata = _sanitize_metadata(metadata)
        saved = super().put(config, safe_checkpoint, safe_metadata, new_versions)
        self._prune_thread(str(config["configurable"]["thread_id"]))
        return saved

    def put_writes(
        self,
        config,
        writes: Sequence[Tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        safe_writes = [
            (channel, _sanitize_channel(channel, value))
            for channel, value in writes
            if _keep_channel(channel)
        ]
        if safe_writes:
            super().put_writes(config, safe_writes, task_id, task_path)

    def _prune_thread(self, thread_id: str) -> None:
        with self.cursor() as cursor:
            rows = cursor.execute(
                "SELECT checkpoint_id FROM checkpoints WHERE thread_id = ? "
                "ORDER BY checkpoint_id DESC LIMIT -1 OFFSET ?",
                (thread_id, self.max_checkpoints_per_thread),
            ).fetchall()
            stale_ids = [row[0] for row in rows]
            if not stale_ids:
                return
            placeholders = ",".join("?" for _ in stale_ids)
            parameters = [thread_id] + stale_ids
            cursor.execute(
                "DELETE FROM writes WHERE thread_id = ? AND checkpoint_id IN (" + placeholders + ")",
                parameters,
            )
            cursor.execute(
                "DELETE FROM checkpoints WHERE thread_id = ? AND checkpoint_id IN (" + placeholders + ")",
                parameters,
            )


class CheckpointRuntime:
    """Owns persistence, lifecycle cleanup and per-thread concurrency control."""

    def __init__(self, settings: CheckpointSettings) -> None:
        self.settings = settings
        self._connection: Optional[sqlite3.Connection] = None
        self.saver: Optional[SanitizingSqliteSaver] = None
        self._thread_locks: Dict[str, threading.Lock] = {}
        self._thread_locks_guard = threading.Lock()
        self._cleanup_guard = threading.Lock()
        self._last_cleanup_monotonic = 0.0
        self._cleanup_status: Dict[str, Any] = {
            "status": "disabled" if not settings.enabled else "pending",
            "lastRunAt": None,
            "deletedThreads": 0,
        }
        if settings.enabled:
            self._open()

    def _open(self) -> None:
        if SqliteSaver is None:
            raise RuntimeError(
                "AI_CHECKPOINT_ENABLED=true requires langgraph-checkpoint-sqlite==2.0.11"
            )
        self.settings.path.parent.mkdir(parents=True, exist_ok=True)
        self._connection = sqlite3.connect(
            str(self.settings.path), timeout=10.0, check_same_thread=False
        )
        self._connection.execute("PRAGMA busy_timeout=10000")
        self.saver = SanitizingSqliteSaver(
            self._connection, self.settings.max_checkpoints_per_thread
        )
        self.saver.setup()
        self._connection.execute(
            """
            CREATE TABLE IF NOT EXISTS qilu_checkpoint_threads (
                thread_id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                schema_version TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """
        )
        self._connection.commit()
        self.cleanup(force=True)

    def close(self) -> None:
        if self._connection is not None:
            self._connection.close()
        self._connection = None
        self.saver = None

    def status(self) -> Dict[str, Any]:
        return {
            "checkpoint": {
                "enabled": self.settings.enabled,
                "backend": "sqlite" if self.settings.enabled else "disabled",
                "schemaVersion": self.settings.schema_version,
                "cleanup": dict(self._cleanup_status),
            }
        }

    def verify_internal_token(self, supplied: Optional[str]) -> bool:
        expected = self.settings.internal_token
        return bool(expected and supplied and hmac.compare_digest(expected, supplied))

    def thread_id(self, user_id: int, conversation_id: str) -> str:
        return "%s:%s" % (user_id, conversation_id)

    @contextmanager
    def acquire_thread(self, thread_id: str) -> Iterator[None]:
        with self._thread_locks_guard:
            lock = self._thread_locks.setdefault(thread_id, threading.Lock())
        if not lock.acquire(blocking=False):
            raise CheckpointConflictError(
                "Another request is already executing this checkpoint thread"
            )
        try:
            yield
        finally:
            lock.release()

    def prepare_thread(self, user_id: int, conversation_id: str) -> Dict[str, Any]:
        if not self.settings.enabled or self.saver is None or self._connection is None:
            return {}
        self.cleanup()
        thread_id = self.thread_id(user_id, conversation_id)
        with self.saver.cursor(transaction=False) as cursor:
            row = cursor.execute(
                "SELECT schema_version FROM qilu_checkpoint_threads WHERE thread_id = ?",
                (thread_id,),
            ).fetchone()
        checkpoint_exists = self.saver.get_tuple(
            {"configurable": {"thread_id": thread_id}}
        ) is not None
        if checkpoint_exists and (row is None or row[0] != self.settings.schema_version):
            # Unknown and old schemas are invalidated instead of preventing startup.
            self.delete_thread(user_id, conversation_id)
            checkpoint_exists = False
        self._touch(thread_id, user_id, conversation_id)
        return self.recovered_business_context(thread_id) if checkpoint_exists else {}

    def recovered_business_context(self, thread_id: str) -> Dict[str, Any]:
        if self.saver is None:
            return {}
        checkpoint_tuple = self.saver.get_tuple(
            {"configurable": {"thread_id": thread_id}}
        )
        if checkpoint_tuple is None:
            return {}
        values = checkpoint_tuple.checkpoint.get("channel_values", {})
        memory = values.get("memory_context") or values.get("memory_summary") or {}
        context = memory.get("businessContext", {}) if isinstance(memory, dict) else {}
        recovered = _business_context(context)
        for card in values.get("business_cards", []) or []:
            if isinstance(card, dict):
                _capture_business_entity(recovered, card)
        return recovered

    def touch_thread(self, user_id: int, conversation_id: str) -> None:
        if self.settings.enabled:
            self._touch(self.thread_id(user_id, conversation_id), user_id, conversation_id)

    def _touch(self, thread_id: str, user_id: int, conversation_id: str) -> None:
        if self._connection is None or self.saver is None:
            return
        now = int(time.time())
        with self.saver.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO qilu_checkpoint_threads
                    (thread_id, user_id, conversation_id, schema_version, updated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(thread_id) DO UPDATE SET
                    schema_version=excluded.schema_version,
                    updated_at=excluded.updated_at,
                    expires_at=excluded.expires_at
                """,
                (
                    thread_id,
                    str(user_id),
                    conversation_id,
                    self.settings.schema_version,
                    now,
                    now + self.settings.ttl_seconds,
                ),
            )

    def delete_thread(self, user_id: int, conversation_id: str) -> bool:
        if not self.settings.enabled or self.saver is None or self._connection is None:
            return True
        thread_id = self.thread_id(user_id, conversation_id)
        self.saver.delete_thread(thread_id)
        with self.saver.cursor() as cursor:
            cursor.execute(
                "DELETE FROM qilu_checkpoint_threads WHERE thread_id = ?", (thread_id,)
            )
        return True

    def delete_user(self, user_id: int) -> int:
        if not self.settings.enabled or self.saver is None or self._connection is None:
            return 0
        with self.saver.cursor(transaction=False) as cursor:
            rows = cursor.execute(
                "SELECT thread_id FROM qilu_checkpoint_threads WHERE user_id = ?",
                (str(user_id),),
            ).fetchall()
        for (thread_id,) in rows:
            self.saver.delete_thread(thread_id)
        with self.saver.cursor() as cursor:
            cursor.execute(
                "DELETE FROM qilu_checkpoint_threads WHERE user_id = ?", (str(user_id),)
            )
        return len(rows)

    def cleanup(self, force: bool = False) -> int:
        if not self.settings.enabled or self.saver is None or self._connection is None:
            return 0
        now_monotonic = time.monotonic()
        if not force and now_monotonic - self._last_cleanup_monotonic < self.settings.cleanup_interval_seconds:
            return 0
        if not self._cleanup_guard.acquire(blocking=False):
            return 0
        try:
            now = int(time.time())
            with self.saver.cursor(transaction=False) as cursor:
                rows = cursor.execute(
                    "SELECT thread_id FROM qilu_checkpoint_threads WHERE expires_at <= ?",
                    (now,),
                ).fetchall()
            for (thread_id,) in rows:
                self.saver.delete_thread(thread_id)
            with self.saver.cursor() as cursor:
                cursor.execute(
                    "DELETE FROM qilu_checkpoint_threads WHERE expires_at <= ?", (now,)
                )
            self._last_cleanup_monotonic = now_monotonic
            self._cleanup_status = {
                "status": "ok",
                "lastRunAt": datetime.now(timezone.utc).isoformat(),
                "deletedThreads": len(rows),
            }
            return len(rows)
        except Exception as exc:
            self._cleanup_status = {
                "status": "error",
                "lastRunAt": datetime.now(timezone.utc).isoformat(),
                "deletedThreads": 0,
                "errorType": type(exc).__name__,
            }
            raise
        finally:
            self._cleanup_guard.release()


_runtime: Optional[CheckpointRuntime] = None
_runtime_guard = threading.Lock()


def get_checkpoint_runtime() -> CheckpointRuntime:
    global _runtime
    with _runtime_guard:
        if _runtime is None:
            _runtime = CheckpointRuntime(CheckpointSettings.from_env())
        return _runtime


def reset_checkpoint_runtime() -> None:
    global _runtime
    with _runtime_guard:
        if _runtime is not None:
            _runtime.close()
        _runtime = None


def checkpoint_status() -> Dict[str, Any]:
    return get_checkpoint_runtime().status()


def _keep_channel(channel: str) -> bool:
    return (
        channel in _SAFE_STATE_CHANNELS
        or channel in _GRAPH_TRIGGER_CHANNELS
        or channel.startswith("start:")
        or channel.startswith("branch:")
        or channel.startswith("__")
    )


def _sanitize_channel(channel: str, value: Any) -> Any:
    if channel == "request":
        return _safe_request(value)
    if channel in {"user_input"}:
        return _summary(value)
    if channel in {"memory_summary", "memory_context"}:
        return _safe_memory(value)
    if channel == "intent_classification":
        return _safe_intent_classification(value)
    if channel == "intent_decision":
        return _safe_intent_decision(value)
    if channel == "intent_entities":
        return _safe_intent_entities(value)
    if channel == "memory_shadow_comparison":
        # Shadow 对照只保留布尔结论和无正文预算计数，禁止持久化双份 Memory。
        return _safe_memory_shadow_comparison(value)
    if channel == "retrieved_context":
        return _summary(value, 256)
    if channel == "knowledge_sources":
        return [_safe_knowledge_source(item) for item in _object_list(value)]
    if channel in {"planned_tools", "current_round_calls"}:
        return [_safe_tool_call(item) for item in _dict_list(value)]
    if channel in {
        "native_result_cache",
        "current_round_results",
        "tool_results",
        "business_tool_results",
    }:
        return _safe_tool_results(value)
    if channel in {"business_cards", "action_drafts"}:
        return [_safe_entity(item) for item in _dict_list(value)]
    if channel in {"execution_records", "errors", "fallback_records"}:
        return [_safe_execution(item) for item in _dict_list(value)]
    if channel == "native_pending_generation":
        # A native model object and its message transcript are intentionally not
        # persisted. Recovery continues through the deterministic rule response.
        return False
    return _safe_scalar_container(value)


def _safe_request(value: Any) -> CampusAssistantRequest:
    if isinstance(value, CampusAssistantRequest):
        request = value
    elif isinstance(value, dict):
        request = CampusAssistantRequest(**value)
    else:
        request = CampusAssistantRequest(question="checkpoint recovery")
    return CampusAssistantRequest(
        userId=request.userId,
        role=request.role,
        traceId=request.traceId,
        conversationId=request.conversationId,
        question=_summary(request.question),
        lastBusinessContext=_business_context(request.lastBusinessContext or {}),
    )


def _safe_memory(value: Any) -> Dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    # Schema 3 明确禁止把 MySQL Memory 的 recentTurns/rollingSummary 写入 SQLite。
    # Checkpoint 只保留一个已确认实体，供图中断后恢复工具幂等和路由。
    return {
        "conversationId": str(value.get("conversationId") or "")[:128],
        "businessContext": _single_business_context(value.get("businessContext") or {}),
    }


def _safe_memory_shadow_comparison(value: Any) -> Dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    return {
        "entityMatch": bool(value.get("entityMatch")),
        "routeMatch": bool(value.get("routeMatch")),
        "toolMatch": bool(value.get("toolMatch")),
        "budgetWithinLimit": bool(value.get("budgetWithinLimit")),
        "legacyEstimatedTokens": _non_negative_integer(value.get("legacyEstimatedTokens")),
        "v2EstimatedTokens": _non_negative_integer(value.get("v2EstimatedTokens")),
    }


def _safe_intent_classification(value: Any) -> Dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    result: Dict[str, Any] = {
        "decision": _safe_intent_decision(value.get("decision")),
        "mode": str(value.get("mode") or "keyword")[:32],
        "fallbackReason": str(value.get("fallbackReason") or "")[:80] or None,
        "lowConfidence": bool(value.get("lowConfidence")),
        "shadowDifferent": bool(value.get("shadowDifferent")),
    }
    for source_key in ("modelDecision", "shadowDecision"):
        source_value = value.get(source_key)
        result[source_key] = _safe_intent_decision(source_value) if isinstance(source_value, dict) else None
    return result


def _safe_intent_decision(value: Any) -> Dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    confidence = value.get("confidence")
    safe_confidence = float(confidence) if isinstance(confidence, (int, float)) and not isinstance(confidence, bool) else 0.0
    candidates = value.get("candidateIntents")
    safe_candidates = (
        [str(item)[:64] for item in candidates[:3] if isinstance(item, str)]
        if isinstance(candidates, list)
        else []
    )
    return {
        "intent": str(value.get("intent") or "general")[:64],
        "confidence": max(0.0, min(1.0, safe_confidence)),
        "scope": str(value.get("scope") or "public_knowledge")[:32],
        "entities": _safe_intent_entities(value.get("entities")),
        "candidateIntents": safe_candidates,
        "intentSource": str(value.get("intentSource") or "rule_fallback")[:32],
    }


def _safe_intent_entities(value: Any) -> Dict[str, Optional[int]]:
    source = value if isinstance(value, dict) else {}
    result: Dict[str, Optional[int]] = {}
    for key in ("appointmentId", "ticketId", "servicePointId"):
        item = source.get(key)
        result[key] = item if isinstance(item, int) and not isinstance(item, bool) and item > 0 else None
    return result


def _safe_knowledge_source(value: Any) -> Dict[str, Any]:
    source = _as_dict(value)
    safe: Dict[str, Any] = {"type": "knowledge"}
    for key in ("id", "knowledgeId"):
        item = source.get(key)
        if isinstance(item, int) and not isinstance(item, bool) and item > 0:
            safe[key] = item
    score = source.get("score")
    if isinstance(score, (int, float)) and not isinstance(score, bool):
        safe["score"] = float(score)
    for key in ("source", "knowledgeVersion"):
        item = source.get(key)
        if isinstance(item, str):
            safe[key] = _summary(item, 128)
    return safe


def _business_context(value: Any) -> Dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    result: Dict[str, Any] = {}
    for key in ("lastTicket", "lastAppointment", "lastServicePoint", "lastActionDraft"):
        item = value.get(key)
        if isinstance(item, dict):
            safe = _safe_entity(item)
            if safe:
                result[key] = safe
    return result


def _single_business_context(value: Any) -> Dict[str, Any]:
    """按固定优先级只保留一个确认实体，禁止多候选列表进入 Checkpoint。"""
    context = _business_context(value)
    for key in ("lastTicket", "lastAppointment", "lastServicePoint", "lastActionDraft"):
        entity = context.get(key)
        if isinstance(entity, dict) and entity:
            return {key: entity}
    return {}


def _capture_business_entity(context: Dict[str, Any], entity: Dict[str, Any]) -> None:
    entity_type = str(entity.get("type") or "")
    target = {
        "ticket": "lastTicket",
        "appointment": "lastAppointment",
        "service_point": "lastServicePoint",
        "action_draft": "lastActionDraft",
    }.get(entity_type)
    if target and target not in context:
        context[target] = _safe_entity(entity)


def _safe_tool_call(value: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "toolName": str(value.get("toolName") or "")[:80],
        "toolCallId": str(value.get("toolCallId") or "")[:128],
        "schemaValidation": str(value.get("schemaValidation") or "")[:40],
        "arguments": _id_fields(value.get("arguments")),
    }


def _safe_tool_results(value: Any) -> Any:
    if isinstance(value, dict):
        first = next(((key, item) for key, item in value.items() if isinstance(item, dict)), None)
        return {} if first is None else {str(first[0])[:160]: _safe_tool_result(first[1])}
    items = _dict_list(value)
    return [_safe_tool_result(items[0])] if items else []


def _safe_tool_result(value: Dict[str, Any]) -> Dict[str, Any]:
    data = value.get("data")
    if isinstance(data, list):
        first = next((item for item in data if isinstance(item, dict)), None)
        safe_data = _safe_entity(first) if first is not None else None
    elif isinstance(data, dict):
        safe_data = _safe_entity(data)
    else:
        safe_data = None
    return {
        "toolName": str(value.get("toolName") or "")[:80],
        "toolCallId": str(value.get("toolCallId") or "")[:128],
        "success": bool(value.get("success")),
        "count": int(value.get("count") or 0),
        "errorType": str(value.get("errorType") or "")[:80] or None,
        "errorCode": str(value.get("errorCode") or "")[:80] or None,
        "schemaValidation": str(value.get("schemaValidation") or "")[:40],
        "data": safe_data,
    }


def _safe_entity(value: Dict[str, Any]) -> Dict[str, Any]:
    safe: Dict[str, Any] = {}
    for key, item in value.items():
        normalized = str(key)
        if normalized == "type" or normalized.lower().endswith("id"):
            if isinstance(item, (str, int)) and not isinstance(item, bool):
                safe[normalized] = item if isinstance(item, int) else item[:128]
        elif normalized in {"success", "completed", "executionStatus"}:
            if isinstance(item, (str, int, bool)):
                safe[normalized] = item
    return safe


def _safe_execution(value: Dict[str, Any]) -> Dict[str, Any]:
    safe: Dict[str, Any] = {}
    for key in (
        "nodeName",
        "toolName",
        "toolCallId",
        "success",
        "status",
        "errorType",
        "errorCode",
        "reason",
        "stage",
        "count",
        "requestId",
    ):
        item = value.get(key)
        if isinstance(item, (str, int, float, bool)):
            safe[key] = item if not isinstance(item, str) else item[:160]
    return safe


def _sanitize_metadata(value: Any) -> Dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    safe: Dict[str, Any] = {}
    for key in ("source", "step"):
        item = value.get(key)
        if isinstance(item, (str, int)):
            safe[key] = item
    writes = value.get("writes")
    if isinstance(writes, dict):
        safe["writes"] = {
            channel: _sanitize_channel(channel, item)
            for channel, item in writes.items()
            if _keep_channel(channel)
        }
    return safe


def _safe_scalar_container(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, str):
        return _summary(value, 256)
    if isinstance(value, list):
        return [_safe_scalar_container(item) for item in value[:100] if isinstance(item, (str, int, float, bool, type(None)))]
    if isinstance(value, dict):
        result: Dict[str, Any] = {}
        for key, item in list(value.items())[:100]:
            if isinstance(item, (str, int, float, bool, type(None))):
                result[str(key)[:80]] = _safe_scalar_container(item)
        return result
    return None


def _id_fields(value: Any) -> Dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    return {
        str(key): item
        for key, item in value.items()
        if str(key).lower().endswith("id") and isinstance(item, (str, int)) and not isinstance(item, bool)
    }


def _dict_list(value: Any) -> List[Dict[str, Any]]:
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def _object_list(value: Any) -> List[Any]:
    return list(value) if isinstance(value, list) else []


def _as_dict(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    model_dump = getattr(value, "model_dump", None)
    return model_dump(mode="json") if callable(model_dump) else {}


def _summary(value: Any, limit: int = 160) -> str:
    text = str(value or "")
    text = _URL_PATTERN.sub("[url]", text)
    text = _PHONE_PATTERN.sub("[phone]", text)
    text = _SECRET_PATTERN.sub(lambda match: match.group(1) + "=[secret]", text)
    text = _ATTACHMENT_URL_PATTERN.sub("[attachment]", text)
    return " ".join(text.split())[:limit]


def _non_negative_integer(value: Any) -> int:
    if isinstance(value, bool):
        return 0
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0


def _env_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    return default if value is None else value.strip().lower() in {"1", "true", "yes", "on"}


def _positive_int(name: str, default: int) -> int:
    value = int(os.getenv(name, str(default)))
    if value <= 0:
        raise ValueError("%s must be greater than zero" % name)
    return value
