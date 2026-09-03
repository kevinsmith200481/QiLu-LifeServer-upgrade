from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence, Tuple

try:
    from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage
    from langchain_openai import ChatOpenAI
except ImportError:  # Keeps rule mode usable in a lightweight runtime.
    HumanMessage = None
    SystemMessage = None
    ToolMessage = None
    ChatOpenAI = None

from agent.tools.registry import ToolDefinition, get_tool, list_tools, tool_schemas_for_role, tools_for_intent
from app.metrics import elapsed_ms, metrics, now
from app.tracing import ai_span, record_exception


PLANNER_MODES = {"rule", "native", "hybrid"}
MAX_TOOL_ROUNDS = 2
MAX_TOOLS_PER_ROUND = 3


@dataclass
class NativePlanResult:
    calls: List[Dict[str, object]] = field(default_factory=list)
    planner_mode: str = "native"
    model_name: Optional[str] = None
    finish_reason: Optional[str] = None
    schema_validation: str = "not_run"
    fallback_reason: Optional[str] = None
    messages: List[Any] = field(default_factory=list)
    bound_model: Any = None


@dataclass
class NativeCompletionResult:
    content: Optional[str] = None
    calls: List[Dict[str, object]] = field(default_factory=list)
    finish_reason: Optional[str] = None
    schema_validation: str = "not_run"
    fallback_reason: Optional[str] = None
    messages: List[Any] = field(default_factory=list)


class ToolCallValidationError(ValueError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def planner_mode() -> str:
    configured = os.getenv("AI_PLANNER_MODE", "hybrid").strip().lower()
    return configured if configured in PLANNER_MODES else "hybrid"


def model_name() -> str:
    return os.getenv("AI_NATIVE_MODEL") or os.getenv("AI_MODEL", "gpt-4o-mini")


def create_chat_model():
    if ChatOpenAI is None:
        raise RuntimeError("MODEL_TOOLS_UNSUPPORTED")
    return ChatOpenAI(
        model=model_name(),
        temperature=float(os.getenv("AI_TEMPERATURE", "0.2")),
        base_url=os.getenv("OPENAI_BASE_URL") or os.getenv("OPENAI_API_BASE"),
        timeout=float(os.getenv("AI_MODEL_TIMEOUT_SECONDS", "8")),
        max_retries=int(os.getenv("AI_MODEL_MAX_RETRIES", "0")),
        # Tool selection and the short grounded answer do not need deep model
        # reasoning. Bounding both effort and output keeps two native turns
        # inside the bounded 12s Provider / 13s RPC / 15s main-service hierarchy.
        reasoning_effort=os.getenv("AI_NATIVE_REASONING_EFFORT", "none"),
        max_completion_tokens=int(os.getenv("AI_NATIVE_MAX_COMPLETION_TOKENS", "256")),
    )


def start_native_plan(state: Dict[str, Any], rule_calls: List[Dict[str, object]]) -> NativePlanResult:
    """Ask the model for genuine tool_calls, falling back to the rule plan safely.

    The model only receives role-filtered schemas. userId, token and trace fields
    remain in trusted state and are never exposed as model-controlled arguments.
    """
    requested_mode = planner_mode()
    if requested_mode == "rule":
        return NativePlanResult(calls=rule_calls, planner_mode="rule", schema_validation="not_applicable")

    start = now()
    try:
        if not os.getenv("OPENAI_API_KEY"):
            raise RuntimeError("MODEL_UNAVAILABLE")
        schemas = _tool_schemas_for_state(state)
        if not schemas:
            return NativePlanResult(calls=[], planner_mode="rule", schema_validation="not_applicable")
        model = create_chat_model()
        bound_model = model.bind_tools(schemas, tool_choice="auto")
        messages = _initial_messages(state)
        with ai_span(
            "qilu.ai.agent.native_planner",
            ai_trace_id=state.get("trace_id"),
            ai_model=model_name(),
            ai_planner_mode=requested_mode,
            ai_tool_round=1,
        ) as span:
            try:
                response = bound_model.invoke(messages)
            except Exception as exc:
                record_exception(span, exc)
                raise
        latency_ms = elapsed_ms(start)
        metrics.record("native_planner.model", latency_ms, success=True)
        calls = parse_and_validate_tool_calls(response, state.get("role"), str(state.get("intent") or ""))
        if not calls:
            return _rule_fallback(rule_calls, "EMPTY_TOOL_CALLS", latency_ms, response)
        metrics.record("native_planner", latency_ms, success=True)
        metrics.record("native_planner.tool_loop_round", latency_ms, success=True)
        return NativePlanResult(
            calls=calls,
            planner_mode="native",
            model_name=model_name(),
            finish_reason=_finish_reason(response),
            schema_validation="passed",
            messages=messages + [response],
            bound_model=bound_model,
        )
    except ToolCallValidationError as exc:
        latency_ms = elapsed_ms(start)
        operation = "native_planner.invalid_tool" if exc.code in {"UNKNOWN_TOOL", "FORBIDDEN_TOOL", "INTENT_TOOL_MISMATCH"} else "native_planner.invalid_arguments"
        metrics.record(operation, latency_ms, success=False, error=exc)
        return _rule_fallback(rule_calls, exc.code, latency_ms)
    except Exception as exc:
        latency_ms = elapsed_ms(start)
        metrics.record("native_planner.model", latency_ms, success=False, error=exc)
        return _rule_fallback(rule_calls, _model_error_code(exc), latency_ms)


def continue_native_plan(
    state: Dict[str, Any],
    tool_messages: Sequence[Any],
) -> NativeCompletionResult:
    """Continue the model/tool loop after trusted tools produced results."""
    start = now()
    previous_messages = list(state.get("native_messages") or [])
    try:
        bound_model = state.get("native_bound_model")
        if bound_model is None:
            raise RuntimeError("MODEL_TOOLS_UNSUPPORTED")
        messages = previous_messages + list(tool_messages)
        with ai_span(
            "qilu.ai.agent.native_planner",
            ai_trace_id=state.get("trace_id"),
            ai_model=model_name(),
            ai_planner_mode="native",
            ai_tool_round=int(state.get("native_round") or 1) + 1,
        ) as span:
            try:
                response = bound_model.invoke(messages)
            except Exception as exc:
                record_exception(span, exc)
                raise
        latency_ms = elapsed_ms(start)
        metrics.record("native_planner.model", latency_ms, success=True)
        calls = parse_and_validate_tool_calls(response, state.get("role"), str(state.get("intent") or ""))
        if calls:
            if int(state.get("native_round") or 1) >= MAX_TOOL_ROUNDS:
                raise ToolCallValidationError("TOOL_ROUND_LIMIT", "native tool round limit exceeded")
            metrics.record("native_planner.tool_loop_round", latency_ms, success=True)
            return NativeCompletionResult(
                calls=calls,
                finish_reason=_finish_reason(response),
                schema_validation="passed",
                messages=messages + [response],
            )
        content = _content(response)
        if not content:
            raise RuntimeError("MODEL_RESPONSE_INVALID")
        metrics.record("native_planner", latency_ms, success=True)
        return NativeCompletionResult(
            content=content,
            finish_reason=_finish_reason(response),
            schema_validation="passed",
            messages=messages + [response],
        )
    except ToolCallValidationError as exc:
        latency_ms = elapsed_ms(start)
        operation = "native_planner.invalid_tool" if exc.code in {"UNKNOWN_TOOL", "FORBIDDEN_TOOL", "INTENT_TOOL_MISMATCH"} else "native_planner.invalid_arguments"
        metrics.record(operation, latency_ms, success=False, error=exc)
        metrics.record("native_planner", latency_ms, success=False, fallback=True, error=exc)
        return NativeCompletionResult(fallback_reason=exc.code, schema_validation="failed")
    except Exception as exc:
        latency_ms = elapsed_ms(start)
        metrics.record("native_planner.model", latency_ms, success=False, error=exc)
        metrics.record("native_planner", latency_ms, success=False, fallback=True, error=exc)
        return NativeCompletionResult(fallback_reason=_model_error_code(exc), schema_validation="failed")


def build_tool_messages(
    calls: Sequence[Dict[str, object]],
    results: Sequence[Dict[str, object]],
) -> List[Any]:
    if ToolMessage is None:
        raise RuntimeError("MODEL_TOOLS_UNSUPPORTED")
    messages: List[Any] = []
    for call, result in zip(calls, results):
        # The tool result is passed only back to the model in-process. It is not
        # copied into public Trace records or logs.
        content = json.dumps(
            {
                "success": bool(result.get("success")),
                "data": result.get("data"),
                "count": int(result.get("count") or 0),
                "errorCode": result.get("errorCode"),
            },
            ensure_ascii=False,
            default=str,
        )
        messages.append(
            ToolMessage(
                content=content,
                tool_call_id=str(call.get("toolCallId") or "missing-tool-call-id"),
                name=str(call.get("toolName") or "unknown_tool"),
            )
        )
    return messages


def parse_and_validate_tool_calls(
    response: Any,
    role: Optional[str],
    allowed_intent: Optional[str] = None,
) -> List[Dict[str, object]]:
    raw_calls = _raw_tool_calls(response)
    if len(raw_calls) > MAX_TOOLS_PER_ROUND:
        raise ToolCallValidationError("TOOL_COUNT_LIMIT", "too many tool calls in one round")
    validated: List[Dict[str, object]] = []
    seen: set = set()
    for index, raw_call in enumerate(raw_calls):
        name, arguments, call_id = _normalize_tool_call(raw_call, index)
        definition = get_tool(name)
        if definition is None:
            raise ToolCallValidationError("UNKNOWN_TOOL", "model selected an unregistered tool")
        if not definition.supports_role(role):
            raise ToolCallValidationError("FORBIDDEN_TOOL", "model selected a role-forbidden tool")
        if allowed_intent and allowed_intent not in definition.intents:
            raise ToolCallValidationError("INTENT_TOOL_MISMATCH", "model selected a tool outside the routed intent")
        validate_arguments(definition, arguments)
        signature = (name, json.dumps(arguments, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
        if signature in seen:
            continue
        seen.add(signature)
        validated.append(
            {
                "toolName": name,
                "arguments": arguments,
                "toolCallId": call_id,
                "schemaValidation": "passed",
            }
        )
    return validated


def validate_arguments(definition: ToolDefinition, arguments: object) -> None:
    if not isinstance(arguments, dict):
        raise ToolCallValidationError("INVALID_ARGUMENTS", "tool arguments must be an object")
    schema = definition.argument_schema
    properties = schema.get("properties") if isinstance(schema.get("properties"), dict) else {}
    required = schema.get("required") if isinstance(schema.get("required"), list) else []
    unknown = set(arguments) - set(properties)
    if unknown:
        raise ToolCallValidationError("INVALID_ARGUMENTS", "tool arguments contain additional properties")
    missing = [name for name in required if name not in arguments]
    if missing:
        raise ToolCallValidationError("INVALID_ARGUMENTS", "tool arguments are missing required properties")
    for name, value in arguments.items():
        property_schema = properties.get(name, {})
        expected_type = property_schema.get("type") if isinstance(property_schema, dict) else None
        if expected_type == "integer" and (not isinstance(value, int) or isinstance(value, bool)):
            raise ToolCallValidationError("INVALID_ARGUMENTS", "tool argument has the wrong type")
        if expected_type == "string" and not isinstance(value, str):
            raise ToolCallValidationError("INVALID_ARGUMENTS", "tool argument has the wrong type")
        if isinstance(value, (int, float)) and isinstance(property_schema, dict):
            if "minimum" in property_schema and value < property_schema["minimum"]:
                raise ToolCallValidationError("INVALID_ARGUMENTS", "tool argument is below its minimum")
            if "maximum" in property_schema and value > property_schema["maximum"]:
                raise ToolCallValidationError("INVALID_ARGUMENTS", "tool argument exceeds its maximum")


def _initial_messages(state: Dict[str, Any]) -> List[Any]:
    if SystemMessage is None or HumanMessage is None:
        raise RuntimeError("MODEL_TOOLS_UNSUPPORTED")
    role = str(state.get("role") or "student")
    system = (
        "你是校园服务工具规划器。只能选择已提供的只读工具，不得猜测工具或参数。"
        "身份由服务端确定；不要请求、生成或修改 userId、role、token、traceId、traceParent。"
        "需要业务事实时调用工具；拿到工具结果后用中文给出简洁、忠实的最终回答。"
        "当前已验证角色：" + role
    )
    return [SystemMessage(content=system), HumanMessage(content=str(state.get("user_input") or ""))]


def _tool_schemas_for_state(state: Dict[str, Any]) -> List[Dict[str, object]]:
    """Narrow schemas by deterministic intent, then enforce the role boundary.

    Intent narrowing reduces model input and latency without choosing the tool:
    the model still emits the native tool_call (for example list vs detail), and
    validation still checks the full role-filtered registry before HTTP.
    General questions retain one harmless role-visible schema so the model's
    no-tool behavior is still verified without sending the entire registry.
    """
    intent = str(state.get("intent") or "general")
    role = state.get("role")
    retrieval_mode = str(state.get("retrieval_mode") or "")
    if retrieval_mode in {"DIRECT_LLM", "RAG_ONLY", "CLARIFY"}:
        return []
    candidates = tools_for_intent(intent, role)
    if intent == "general" and not candidates:
        # A greeting needs a real tools-capable model call to prove that the
        # model emits no tool_calls. One irrelevant read-only schema is enough
        # for that proof and avoids latency spikes from all role-visible tools.
        representative = get_tool("query_service_categories")
        if representative is not None and representative.supports_role(role):
            return [representative.openai_schema()]
    if not candidates and retrieval_mode:
        return []
    if not candidates:
        return tool_schemas_for_role(role)
    return [tool.openai_schema() for tool in candidates]


def _rule_fallback(
    rule_calls: List[Dict[str, object]],
    reason: str,
    latency_ms: float,
    response: Any = None,
) -> NativePlanResult:
    error = RuntimeError(reason)
    metrics.record("native_planner", latency_ms, success=False, fallback=True, error=error)
    # Schema or authorization failures are fail-closed: the rule planner still
    # supplies the response path, but it must not turn an invalid model request
    # into any business HTTP call during the same request.
    unsafe_call_reasons = {
        "UNKNOWN_TOOL",
        "FORBIDDEN_TOOL",
        "INTENT_TOOL_MISMATCH",
        "INVALID_ARGUMENTS",
        "MODEL_RESPONSE_INVALID",
        "TOOL_COUNT_LIMIT",
    }
    return NativePlanResult(
        calls=[] if reason in unsafe_call_reasons else rule_calls,
        planner_mode="rule_fallback",
        model_name=model_name(),
        finish_reason=_finish_reason(response) if response is not None else None,
        schema_validation="failed" if reason != "EMPTY_TOOL_CALLS" else "not_applicable",
        fallback_reason=reason,
    )


def _raw_tool_calls(response: Any) -> List[Any]:
    calls = getattr(response, "tool_calls", None)
    if isinstance(calls, list):
        return calls
    additional = getattr(response, "additional_kwargs", None)
    if isinstance(additional, dict) and isinstance(additional.get("tool_calls"), list):
        return additional["tool_calls"]
    return []


def _normalize_tool_call(raw_call: Any, index: int) -> Tuple[str, Dict[str, object], str]:
    if not isinstance(raw_call, dict):
        raise ToolCallValidationError("MODEL_RESPONSE_INVALID", "tool call is not an object")
    function = raw_call.get("function") if isinstance(raw_call.get("function"), dict) else {}
    name = str(raw_call.get("name") or function.get("name") or "").strip()
    arguments: Any = raw_call.get("args") if "args" in raw_call else function.get("arguments", {})
    if isinstance(arguments, str):
        try:
            arguments = json.loads(arguments)
        except json.JSONDecodeError as exc:
            raise ToolCallValidationError("INVALID_ARGUMENTS", "tool arguments are not valid JSON") from exc
    call_id = str(raw_call.get("id") or raw_call.get("tool_call_id") or "tool-call-%d" % (index + 1))
    if not name:
        raise ToolCallValidationError("UNKNOWN_TOOL", "tool call name is missing")
    if not isinstance(arguments, dict):
        raise ToolCallValidationError("INVALID_ARGUMENTS", "tool arguments must be an object")
    return name, arguments, call_id


def _finish_reason(response: Any) -> Optional[str]:
    metadata = getattr(response, "response_metadata", None)
    if isinstance(metadata, dict) and metadata.get("finish_reason"):
        return str(metadata.get("finish_reason"))
    additional = getattr(response, "additional_kwargs", None)
    if isinstance(additional, dict) and additional.get("finish_reason"):
        return str(additional.get("finish_reason"))
    return None


def _content(response: Any) -> Optional[str]:
    content = getattr(response, "content", None)
    return content.strip() if isinstance(content, str) and content.strip() else None


def _model_error_code(exc: BaseException) -> str:
    text = (type(exc).__name__ + " " + str(exc)).upper()
    if "TIMEOUT" in text:
        return "MODEL_TIMEOUT"
    if "TOOLS_UNSUPPORTED" in text or "BIND_TOOLS" in text:
        return "MODEL_TOOLS_UNSUPPORTED"
    if "RESPONSE_INVALID" in text:
        return "MODEL_RESPONSE_INVALID"
    return "MODEL_UNAVAILABLE"
