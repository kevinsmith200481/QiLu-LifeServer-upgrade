from __future__ import annotations

import asyncio
import os
import threading
import time
from typing import Dict


_checkpoint_interrupt_lock = threading.Lock()
_checkpoint_interrupt_consumed = False


def acceptance_faults_enabled() -> bool:
    return (
        os.getenv("APP_PROFILE", "").strip().lower() == "acceptance"
        and _truthy(os.getenv("QILU_ACCEPTANCE_FAULTS_ENABLED"))
    )


async def delay_agent_if_configured() -> None:
    delay_ms = _non_negative_int("QILU_ACCEPTANCE_AGENT_DELAY_MS")
    if acceptance_faults_enabled() and delay_ms > 0:
        await asyncio.sleep(delay_ms / 1000.0)


def delay_tool_if_configured(timeout_seconds: float | None = None) -> None:
    delay_ms = _non_negative_int("QILU_ACCEPTANCE_TOOL_DELAY_MS")
    if acceptance_faults_enabled() and delay_ms > 0:
        delay_seconds = delay_ms / 1000.0
        if timeout_seconds is not None and delay_seconds >= timeout_seconds:
            # Fault delay is part of the tool budget; never sleep past the deadline.
            time.sleep(max(0.0, timeout_seconds))
            raise TimeoutError("ACCEPTANCE_TOOL_TIMEOUT")
        time.sleep(delay_seconds)


def force_model_timeout() -> bool:
    return acceptance_faults_enabled() and _truthy(os.getenv("QILU_ACCEPTANCE_MODEL_TIMEOUT"))


def force_model_unavailable() -> bool:
    return acceptance_faults_enabled() and _truthy(os.getenv("QILU_ACCEPTANCE_MODEL_UNAVAILABLE"))


def force_agent_http_500() -> bool:
    return acceptance_faults_enabled() and _truthy(os.getenv("QILU_ACCEPTANCE_AGENT_HTTP_500"))


def force_agent_invalid_json() -> bool:
    return acceptance_faults_enabled() and _truthy(os.getenv("QILU_ACCEPTANCE_AGENT_INVALID_JSON"))


def force_rag_embed_documents_failure() -> bool:
    """只故障化 candidate 文档向量构建，旧 active 的查询向量仍保持可用。"""

    return acceptance_faults_enabled() and _truthy(
        os.getenv("QILU_ACCEPTANCE_RAG_EMBED_DOCUMENTS_FAILURE")
    )


def consume_checkpoint_interrupt_after_tools() -> bool:
    """Consume the one-shot checkpoint interruption used by live E2E recovery.

    The switch is deliberately process-local and acceptance-only. A restarted
    Agent reads the persisted graph state, while normal profiles can never
    enter the synthetic interruption path.
    """
    global _checkpoint_interrupt_consumed
    enabled = acceptance_faults_enabled() and _truthy(
        os.getenv("QILU_ACCEPTANCE_CHECKPOINT_INTERRUPT_AFTER_TOOLS_ONCE")
    )
    if not enabled:
        return False
    with _checkpoint_interrupt_lock:
        if _checkpoint_interrupt_consumed:
            return False
        _checkpoint_interrupt_consumed = True
        return True


def fault_status() -> Dict[str, object]:
    """Expose switch state without returning secrets or raw environment values."""
    return {
        "acceptanceFaultsEnabled": acceptance_faults_enabled(),
        "agentDelayMs": _non_negative_int("QILU_ACCEPTANCE_AGENT_DELAY_MS"),
        "agentHttp500": force_agent_http_500(),
        "agentInvalidJson": force_agent_invalid_json(),
        "ragEmbedDocumentsFailure": force_rag_embed_documents_failure(),
        "toolDelayMs": _non_negative_int("QILU_ACCEPTANCE_TOOL_DELAY_MS"),
        "modelTimeout": force_model_timeout(),
        "modelUnavailable": force_model_unavailable(),
        "checkpointInterruptAfterToolsOnce": acceptance_faults_enabled()
        and _truthy(os.getenv("QILU_ACCEPTANCE_CHECKPOINT_INTERRUPT_AFTER_TOOLS_ONCE")),
    }


def _non_negative_int(name: str) -> int:
    try:
        return max(0, int(os.getenv(name, "0")))
    except (TypeError, ValueError):
        return 0


def _truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}
