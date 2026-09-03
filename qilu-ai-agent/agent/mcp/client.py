from __future__ import annotations

import logging
import time
from typing import Callable, Dict

from agent.mcp.adapter import MCP_DISCOVERABLE_TOOLS
from agent.mcp.schemas import McpToolCallResult


logger = logging.getLogger(__name__)


def call_tool(
    call_business_tool_func: Callable[[Dict[str, object], str, Dict[str, object]], Dict[str, object]],
    state: Dict[str, object],
    tool_name: str,
    arguments: Dict[str, object],
) -> Dict[str, object]:
    start = time.perf_counter()
    trace_id = str(state.get("trace_id") or state.get("traceId") or "") or None
    if tool_name not in MCP_DISCOVERABLE_TOOLS:
        return _error_result(tool_name, trace_id, start, "MCP_TOOL_NOT_DISCOVERABLE")
    try:
        result = call_business_tool_func(state, tool_name, arguments or {})
    except TimeoutError:
        return _error_result(tool_name, trace_id, start, "TimeoutError")
    except Exception as exc:
        return _error_result(tool_name, trace_id, start, type(exc).__name__)
    return _normalize_result(tool_name, trace_id, start, result or {})


def _normalize_result(tool_name: str, trace_id: str, start: float, result: Dict[str, object]) -> Dict[str, object]:
    success = bool(result.get("success"))
    data = result.get("data") if success else None
    count = _result_count(data) if success else 0
    if success and result.get("count") is not None:
        count = int(result.get("count") or 0)
    latency_ms = _latency_ms(start, result)
    error_type = None if success else str(result.get("errorType") or result.get("message") or "TOOL_UNAVAILABLE")
    normalized = McpToolCallResult(
        toolName=str(result.get("toolName") or tool_name),
        success=success,
        data=data,
        message=result.get("message"),
        count=count,
        traceId=trace_id,
        latencyMs=latency_ms,
        errorType=error_type,
    ).to_dict()
    _log_result(normalized)
    return normalized


def _error_result(tool_name: str, trace_id: str, start: float, error_type: str) -> Dict[str, object]:
    result = McpToolCallResult(
        toolName=tool_name,
        success=False,
        data=None,
        message=error_type,
        count=0,
        traceId=trace_id,
        latencyMs=_latency_ms(start, {}),
        errorType=error_type,
    ).to_dict()
    _log_result(result)
    return result


def _latency_ms(start: float, result: Dict[str, object]) -> float:
    if result.get("latencyMs") is not None:
        return float(result.get("latencyMs") or 0.0)
    return (time.perf_counter() - start) * 1000.0


def _result_count(data: object) -> int:
    if isinstance(data, list):
        return len(data)
    return 1 if data else 0


def _log_result(result: Dict[str, object]) -> None:
    logger.info(
        "mcp tool execution record traceId=%s toolName=%s toolProtocol=%s success=%s count=%s elapsedMs=%.2f errorType=%s",
        result.get("traceId"),
        result.get("toolName"),
        result.get("toolProtocol"),
        result.get("success"),
        result.get("count"),
        float(result.get("latencyMs") or 0.0),
        result.get("errorType"),
    )
