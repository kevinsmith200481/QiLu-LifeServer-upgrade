from __future__ import annotations

import json
import logging
from typing import Any, Callable, Dict, List, Optional

from agent import campus_support_agent as legacy
from agent.execution import FallbackRecord, ToolExecutionRecord, build_agent_plan
from agent.intent_router import RetrievalMode
from agent.langgraph_state import CampusGraphState, append_error, response_to_state_fields
from agent.memory import build_request_memory_context
from agent.native_function_calling import build_tool_messages, continue_native_plan, start_native_plan
from app.metrics import elapsed_ms, metrics, now
from app.schemas import CampusAssistantResponse

logger = logging.getLogger(__name__)


NodeBody = Callable[[CampusGraphState], Dict[str, Any]]


def load_memory_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "load_memory", _load_memory)


def retrieve_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "retrieve", lambda current: dict(legacy.retrieve_context(current)))  # type: ignore[arg-type]


def check_escalation_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "check_escalation", _check_escalation)


def detect_intent_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "detect_intent", lambda current: dict(legacy.detect_intent(current)))  # type: ignore[arg-type]


def classify_intent_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "classify_intent", lambda current: dict(legacy.classify_query_intent(current)))  # type: ignore[arg-type]


def select_retrieval_policy_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(
        state,
        "select_retrieval_policy",
        lambda current: dict(legacy.select_query_retrieval_policy(current)),  # type: ignore[arg-type]
    )


def route_query_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "route_query", _route_query)


def generate_clarification_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(
        state,
        "generate_clarification",
        lambda current: dict(legacy.generate_clarification(current)),  # type: ignore[arg-type]
    )


def plan_tools_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "plan_tools", _plan_tools)


def execute_tools_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "execute_tools", _execute_tools)


def generate_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "generate", _generate)


def finalize_response_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "finalize_response", _finalize_response)


def fallback_node(state: CampusGraphState) -> Dict[str, Any]:
    return _run_node(state, "fallback", _fallback)


def _load_memory(state: CampusGraphState) -> Dict[str, Any]:
    request = state.get("request")
    if request is None:
        return {}
    memory_context = build_request_memory_context(request)
    return {
        "trace_id": request.traceId,
        "traceparent": request.traceParent,
        "trace_parent": request.traceParent,
        "memory_summary": memory_context,
        "memory_context": memory_context,
        "user_input": request.question,
        "service_points": request.servicePoints or legacy.memory_service_point_list(memory_context),
        "tickets": request.tickets or legacy.memory_ticket_list(memory_context),
        "appointments": request.appointments or legacy.memory_appointment_list(memory_context),
        "user_id": request.userId,
        "role": request.role,
    }


def _check_escalation(state: CampusGraphState) -> Dict[str, Any]:
    update = dict(legacy.check_escalation(state))  # type: ignore[arg-type]
    intent = str(state.get("intent") or "general")
    update["need_create_ticket"] = bool(update.get("escalate") or intent == "repair")
    return update


def _route_query(state: CampusGraphState) -> Dict[str, Any]:
    retrieval_mode = str(state.get("retrieval_mode") or "")
    if retrieval_mode not in {mode.value for mode in RetrievalMode}:
        raise ValueError("retrieval mode is missing or invalid")
    return {"retrieval_mode": retrieval_mode}


def _plan_tools(state: CampusGraphState) -> Dict[str, Any]:
    retrieval_mode = str(state.get("retrieval_mode") or "")
    permission_denied = legacy.business_tool_permission_denied(state)  # type: ignore[arg-type]
    if permission_denied or retrieval_mode in {
        RetrievalMode.DIRECT_LLM.value,
        RetrievalMode.RAG_ONLY.value,
        RetrievalMode.CLARIFY.value,
    }:
        plan = build_agent_plan(state, [], planner_mode="rule", schema_validation="not_applicable")
        return {
            "permission_denied": permission_denied,
            "planned_tools": [],
            "current_round_calls": [],
            "agent_plan": plan.to_dict(),
            "planner_mode": "rule",
            "planner_fallback_reason": None,
            "model_name": None,
            "finish_reason": None,
            "schema_validation": "not_applicable",
            "native_round": 0,
            "native_messages": [],
            "native_bound_model": None,
            "native_pending_generation": False,
        }
    rule_calls = legacy.plan_business_tool_calls(state)  # type: ignore[arg-type]
    native_plan = start_native_plan(state, rule_calls)
    calls = native_plan.calls
    plan = build_agent_plan(
        state,
        calls,
        planner_mode=native_plan.planner_mode,
        model_name=native_plan.model_name,
        finish_reason=native_plan.finish_reason,
        schema_validation=native_plan.schema_validation,
    )
    metrics.record("planner.total", 0.0, success=True)
    metrics.record("tool_plan.total", 0.0, success=True)
    logger.info(
        "agent plan traceId=%s orchestrator=langgraph nodeName=plan_tools plan=%s",
        state.get("trace_id"),
        json.dumps(plan.to_dict(), ensure_ascii=False),
    )
    return {
        "planned_tools": calls,
        "current_round_calls": calls,
        "agent_plan": plan.to_dict(),
        "planner_mode": native_plan.planner_mode,
        "planner_fallback_reason": native_plan.fallback_reason,
        "model_name": native_plan.model_name,
        "finish_reason": native_plan.finish_reason,
        "schema_validation": native_plan.schema_validation,
        "native_round": 1 if native_plan.planner_mode == "native" else 0,
        "native_messages": native_plan.messages,
        "native_bound_model": native_plan.bound_model,
        "native_pending_generation": native_plan.planner_mode == "native",
    }


def _execute_tools(state: CampusGraphState) -> Dict[str, Any]:
    calls = list(state.get("planned_tools") or [])
    results = list(state.get("business_tool_results") or [])
    round_results: List[Dict[str, Any]] = []
    execution_records = list(state.get("execution_records") or [])
    fallback_records = list(state.get("fallback_records") or [])
    seen_calls = set(state.get("native_seen_calls") or [])
    result_cache = dict(state.get("native_result_cache") or {})
    execution_keys = set(state.get("tool_execution_keys") or [])
    for call in calls:
        tool_name = str(call.get("toolName") or "")
        arguments = call.get("arguments", {}) if isinstance(call.get("arguments"), dict) else {}
        signature = _tool_signature(tool_name, arguments)
        request_id = str(state.get("request_id") or state.get("trace_id") or "")
        tool_call_id = str(call.get("toolCallId") or signature)
        execution_key = "%s:%s" % (request_id, tool_call_id)
        if execution_key in execution_keys and execution_key in result_cache:
            # A model can repeat an identical call on the second round. Reuse
            # the trusted result so the internal HTTP endpoint is never hit twice.
            round_results.append(dict(result_cache[execution_key]))
            continue
        result = legacy.filter_business_tool_result(  # type: ignore[arg-type]
            state,
            legacy.call_business_tool(state, tool_name, arguments),  # type: ignore[arg-type]
        )
        if not result.get("metricsRecorded"):
            latency_ms = float(result.get("latencyMs") or 0.0)
            success = bool(result.get("success"))
            metrics.record("tool." + str(result.get("toolName") or tool_name), latency_ms, success=success)
            metrics.record("tool_execute.total", latency_ms, success=success)
        result["toolCallId"] = call.get("toolCallId")
        result["schemaValidation"] = call.get("schemaValidation") or "not_applicable"
        results.append(result)
        round_results.append(result)
        seen_calls.add(signature)
        execution_keys.add(execution_key)
        result_cache[execution_key] = dict(result)
        execution_record = ToolExecutionRecord(
            toolName=str(result.get("toolName") or tool_name),
            arguments=arguments,
            success=bool(result.get("success")),
            count=int(result.get("count") or 0),
            latencyMs=float(result.get("latencyMs") or 0.0),
            errorType=result.get("errorType"),
            errorCode=result.get("errorCode"),
            toolProtocol=str(result.get("toolProtocol") or "http_internal"),
            toolCallId=str(call.get("toolCallId")) if call.get("toolCallId") else None,
            schemaValidation=str(call.get("schemaValidation") or "not_applicable"),
        )
        execution_records.append(execution_record.to_dict())
        logger.info(
            "tool execution record traceId=%s orchestrator=langgraph nodeName=execute_tools record=%s",
            state.get("trace_id"),
            json.dumps(_safe_execution_record(execution_record.to_dict()), ensure_ascii=False),
        )
        if not execution_record.success:
            fallback_record = legacy.build_tool_fallback_record(result, arguments)
            fallback_records.append(fallback_record.to_dict())
            logger.info(
                "fallback record traceId=%s orchestrator=langgraph nodeName=execute_tools record=%s",
                state.get("trace_id"),
                json.dumps(fallback_record.to_dict(), ensure_ascii=False),
            )
    return {
        "business_tool_results": results,
        "tool_results": results,
        "current_round_results": round_results,
        "execution_records": execution_records,
        "fallback_records": fallback_records,
        "native_seen_calls": sorted(seen_calls),
        "native_result_cache": result_cache,
        "tool_execution_keys": sorted(execution_keys),
    }


def _generate(state: CampusGraphState) -> Dict[str, Any]:
    if state.get("planner_mode") == "native" and state.get("native_pending_generation"):
        return _generate_native(state)
    update = dict(legacy.generate_response(state))  # type: ignore[arg-type]
    planner_fallback_reason = state.get("planner_fallback_reason")
    if state.get("planner_mode") == "rule_fallback" and planner_fallback_reason:
        generation_record = dict(update.get("generation_record") or {})
        business_tool_succeeded = (
            generation_record.get("generationMode") in {"business_tool", "hybrid", "llm_hybrid"}
            and not generation_record.get("fallbackReason")
        )
        generation_record.update(
            plannerMode="rule_fallback",
            modelName=state.get("model_name"),
            finishReason=state.get("finish_reason"),
            schemaValidation=state.get("schema_validation"),
            fallbackReason=None if business_tool_succeeded else planner_fallback_reason,
        )
        update["generation_record"] = generation_record
        update["fallback_reason"] = None if business_tool_succeeded else planner_fallback_reason
        records = list(update.get("fallback_records") or [])
        records.append(
            FallbackRecord(
                reason=str(planner_fallback_reason),
                stage="native_planner",
                detail={"generationMode": "rule_fallback"},
            ).to_dict()
        )
        update["fallback_records"] = records
    generation_record = update.get("generation_record")
    if isinstance(generation_record, dict) and generation_record.get("fallbackReason"):
        update["fallback_reason"] = generation_record.get("fallbackReason")
    return update


def _generate_native(state: CampusGraphState) -> Dict[str, Any]:
    calls = list(state.get("current_round_calls") or [])
    results = list(state.get("current_round_results") or [])
    completion = continue_native_plan(state, build_tool_messages(calls, results))
    if completion.calls:
        return {
            "planned_tools": completion.calls,
            "current_round_calls": completion.calls,
            "current_round_results": [],
            "native_messages": completion.messages,
            "native_round": int(state.get("native_round") or 1) + 1,
            "finish_reason": completion.finish_reason,
            "schema_validation": completion.schema_validation,
            "native_pending_generation": True,
        }
    if completion.content:
        response_text = completion.content
        generation_mode = "native_function_calling"
        if state.get("retrieval_mode") == RetrievalMode.HYBRID.value and state.get("retrieved_context"):
            response_text = legacy.build_hybrid_response(state, completion.content)  # type: ignore[arg-type]
            generation_mode = "hybrid"
        return {
            "response": response_text,
            "messages": [],
            "native_messages": completion.messages,
            "finish_reason": completion.finish_reason,
            "schema_validation": completion.schema_validation,
            "native_pending_generation": False,
            "generation_record": {
                "generationMode": generation_mode,
                "usedLLM": True,
                "usedRuleFallback": False,
                "fallbackReason": None,
                "plannerMode": "native",
                "modelName": state.get("model_name"),
                "finishReason": completion.finish_reason,
                "schemaValidation": completion.schema_validation,
            },
        }

    reason = completion.fallback_reason or "MODEL_UNAVAILABLE"
    rule_update = dict(legacy.generate_response(state))  # type: ignore[arg-type]
    generation_record = dict(rule_update.get("generation_record") or {})
    business_tool_succeeded = (
        generation_record.get("generationMode") in {"business_tool", "hybrid", "llm_hybrid"}
        and not generation_record.get("fallbackReason")
    )
    rule_update.update(
        planner_mode="rule_fallback",
        planner_fallback_reason=reason,
        fallback_reason=None if business_tool_succeeded else reason,
        native_pending_generation=False,
        schema_validation=completion.schema_validation,
    )
    generation_record.update(
        plannerMode="rule_fallback",
        modelName=state.get("model_name"),
        finishReason=completion.finish_reason,
        schemaValidation=completion.schema_validation,
        fallbackReason=None if business_tool_succeeded else reason,
        usedRuleFallback=True,
    )
    rule_update["generation_record"] = generation_record
    records = list(rule_update.get("fallback_records") or [])
    records.append(FallbackRecord(reason=reason, stage="native_planner", detail={"generationMode": "rule_fallback"}).to_dict())
    rule_update["fallback_records"] = records
    return rule_update


def _finalize_response(state: CampusGraphState) -> Dict[str, Any]:
    response = legacy.build_structured_response(state)  # type: ignore[arg-type]
    if state.get("trace_id"):
        response.traceId = state.get("trace_id")
    update = response_to_state_fields(response)
    update["response"] = response
    return update


def _fallback(state: CampusGraphState) -> Dict[str, Any]:
    reason = _fallback_reason_from_state(state) or "TOOL_UNAVAILABLE"
    records = list(state.get("fallback_records") or [])
    record = FallbackRecord(reason=reason, stage="langgraph", detail={"errors": list(state.get("errors") or [])})
    records.append(record.to_dict())
    metrics.record("fallback.total", 0.0, success=True, fallback=True)
    return {
        "response": _fallback_text(reason),
        "fallback_reason": reason,
        "generation_record": {
            "generationMode": "langgraph_fallback",
            "usedLLM": False,
            "usedRuleFallback": True,
            "fallbackReason": reason,
        },
        "fallback_records": records,
    }


def _run_node(state: CampusGraphState, node_name: str, body: NodeBody) -> Dict[str, Any]:
    start = now()
    success = True
    error_type: Optional[str] = None
    previous_fallback_reason = _fallback_reason_from_state(state)
    update: Dict[str, Any]
    try:
        update = body(state) or {}
    except Exception as exc:
        success = False
        error_type = type(exc).__name__
        fallback_reason = _exception_fallback_reason(node_name, exc)
        update = {
            "errors": append_error(state, node_name, exc),
            "fallback_reason": fallback_reason,
        }
        logger.exception(
            "langgraph node failed traceId=%s orchestrator=langgraph nodeName=%s errorType=%s",
            state.get("trace_id"),
            node_name,
            type(exc).__name__,
        )
    merged = dict(state)
    merged.update(update)
    fallback_reason = _fallback_reason_from_state(merged)
    latency_ms = elapsed_ms(start)
    node_fallback_reason = _node_fallback_reason(state, update, node_name, success, previous_fallback_reason, fallback_reason)
    node_record = {
        "order": len(state.get("lang_graph_nodes") or []) + 1,
        "nodeName": node_name,
        "status": _node_status(success, node_fallback_reason),
        "latencyMs": round(latency_ms, 2),
        "fallbackReason": node_fallback_reason,
        "errorType": error_type or _last_error_type(merged),
        "toolName": _last_tool_name(merged),
        "toolProtocol": _last_tool_protocol(merged),
        "plannerMode": merged.get("planner_mode"),
        "modelName": merged.get("model_name"),
        "toolCallId": _last_tool_call_id(merged),
        "finishReason": merged.get("finish_reason"),
        "schemaValidation": merged.get("schema_validation"),
    }
    node_records = list(state.get("lang_graph_nodes") or [])
    node_records.append(node_record)
    update["lang_graph_nodes"] = node_records
    if isinstance(update.get("response"), CampusAssistantResponse):
        response = update["response"]
        response.orchestrator = str(merged.get("orchestrator") or "langgraph")
        response.langGraphNodes = legacy.sanitize_lang_graph_nodes(node_records)
        response.executionRecords = legacy.sanitize_execution_records(merged.get("execution_records", []))
        response.fallbackRecords = legacy.sanitize_fallback_records(merged.get("fallback_records", []))
    fallback_for_metrics = node_fallback_reason if node_name in {"generate", "fallback", "execute_tools"} else None
    metrics.record_langgraph_node(node_name, latency_ms, success=success, fallback_reason=fallback_for_metrics)
    logger.info(
        "langgraph node traceId=%s orchestrator=langgraph nodeName=%s latencyMs=%.2f success=%s fallbackReason=%s toolName=%s toolProtocol=%s",
        merged.get("trace_id"),
        node_name,
        latency_ms,
        str(success).lower(),
        node_fallback_reason or "",
        _last_tool_name(merged),
        _last_tool_protocol(merged),
    )
    return update


def _node_status(success: bool, fallback_reason: Optional[str]) -> str:
    if not success:
        return "ERROR"
    if fallback_reason:
        return "FALLBACK"
    return "SUCCESS"


def _node_fallback_reason(
    state: CampusGraphState,
    update: Dict[str, Any],
    node_name: str,
    success: bool,
    previous_fallback_reason: Optional[str],
    fallback_reason: Optional[str],
) -> Optional[str]:
    if not success:
        return fallback_reason
    if not fallback_reason:
        return None
    if node_name == "fallback":
        return fallback_reason
    generation_record = update.get("generation_record")
    if node_name == "generate" and isinstance(generation_record, dict) and generation_record.get("fallbackReason"):
        return fallback_reason
    previous_records = state.get("fallback_records") if isinstance(state.get("fallback_records"), list) else []
    updated_records = update.get("fallback_records") if isinstance(update.get("fallback_records"), list) else []
    if len(updated_records) > len(previous_records):
        return fallback_reason
    if previous_fallback_reason != fallback_reason and node_name in {"execute_tools", "generate"}:
        return fallback_reason
    return None


def _fallback_reason_from_state(state: Dict[str, Any]) -> Optional[str]:
    reason = state.get("fallback_reason")
    if reason:
        return str(reason)
    generation_record = state.get("generation_record")
    if isinstance(generation_record, dict) and generation_record.get("fallbackReason"):
        return str(generation_record.get("fallbackReason"))
    records = state.get("fallback_records")
    if isinstance(records, list) and records:
        last = records[-1]
        if isinstance(last, dict) and last.get("reason"):
            return str(last.get("reason"))
    return None


def _exception_fallback_reason(node_name: str, exc: BaseException) -> str:
    name = type(exc).__name__
    if node_name == "retrieve":
        return "KNOWLEDGE_NOT_SYNCED"
    if "Timeout" in name or "timeout" in str(exc):
        return "TOOL_TIMEOUT"
    return "TOOL_UNAVAILABLE"


def _fallback_text(reason: str) -> str:
    if reason == "KNOWLEDGE_NOT_SYNCED":
        return legacy.KNOWLEDGE_UNINITIALIZED_MESSAGE
    if reason == "NO_SOURCE":
        return "暂未找到可靠来源，无法给出确定答案。请补充问题关键信息，或联系人工处理。"
    if reason == "PERMISSION_DENIED":
        return "当前账号无权查看该数据。"
    if reason == "TOOL_TIMEOUT":
        return "业务数据查询超时，请稍后再试。"
    return "业务数据暂时无法读取，请稍后再试。"


def _last_tool_name(state: Dict[str, Any]) -> str:
    records = state.get("execution_records")
    if isinstance(records, list) and records:
        last = records[-1]
        if isinstance(last, dict):
            return str(last.get("toolName") or "")
    return ""


def _last_tool_protocol(state: Dict[str, Any]) -> str:
    records = state.get("execution_records")
    if isinstance(records, list) and records:
        last = records[-1]
        if isinstance(last, dict):
            return str(last.get("toolProtocol") or "")
    return ""


def _last_tool_call_id(state: Dict[str, Any]) -> Optional[str]:
    records = state.get("execution_records")
    if isinstance(records, list) and records:
        last = records[-1]
        if isinstance(last, dict) and last.get("toolCallId"):
            return str(last.get("toolCallId"))
    return None


def _tool_signature(tool_name: str, arguments: Dict[str, object]) -> str:
    return tool_name + ":" + json.dumps(arguments, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _safe_execution_record(record: Dict[str, Any]) -> Dict[str, Any]:
    """Keep logs useful without writing model-selected argument values."""
    safe = dict(record)
    arguments = safe.pop("arguments", {})
    safe["argumentFields"] = sorted(arguments) if isinstance(arguments, dict) else []
    return safe


def _last_error_type(state: Dict[str, Any]) -> Optional[str]:
    errors = state.get("errors")
    if isinstance(errors, list) and errors:
        last = errors[-1]
        if isinstance(last, dict) and last.get("errorType"):
            return str(last.get("errorType"))
    records = state.get("execution_records")
    if isinstance(records, list) and records:
        last = records[-1]
        if isinstance(last, dict) and last.get("errorType"):
            return str(last.get("errorType"))
    return None
