from __future__ import annotations

import os
from typing import Any, Dict, List

from langchain_core.messages import AIMessage, ToolMessage

import agent.campus_support_agent as support_agent
import agent.native_function_calling as native
from agent.langgraph_nodes import execute_tools_node, finalize_response_node, generate_node, load_memory_node, plan_tools_node
from agent.langgraph_state import initial_graph_state
from agent.tools.registry import list_tools, tool_schemas_for_role
from app.metrics import metrics
from app.schemas import CampusAssistantRequest, KnowledgeSource


def assert_equal(actual, expected, message):
    if actual != expected:
        raise AssertionError("%s: expected %r got %r" % (message, expected, actual))


def assert_true(condition, message):
    if not condition:
        raise AssertionError(message)


def tool_response(*calls, content: str = "", finish_reason: str = "tool_calls") -> AIMessage:
    return AIMessage(
        content=content,
        tool_calls=[
            {"name": name, "args": arguments, "id": call_id, "type": "tool_call"}
            for name, arguments, call_id in calls
        ],
        response_metadata={"finish_reason": finish_reason},
    )


class FakeBoundModel:
    def __init__(self, responses: List[Any]):
        self.responses = list(responses)
        self.invocations: List[List[Any]] = []

    def invoke(self, messages):
        self.invocations.append(list(messages))
        response = self.responses.pop(0)
        if isinstance(response, BaseException):
            raise response
        return response


class FakeChatModel:
    def __init__(self, responses: List[Any]):
        self.bound = FakeBoundModel(responses)
        self.schemas: List[Dict[str, object]] = []

    def bind_tools(self, schemas, tool_choice="auto"):
        assert_equal(tool_choice, "auto", "tool choice")
        self.schemas = list(schemas)
        return self.bound


def state(role: str = "student", question: str = "查看我的工单") -> Dict[str, Any]:
    request = CampusAssistantRequest(
        userId=2006,
        role=role,
        traceId="native-test",
        conversationId="native-conversation",
        question=question,
    )
    graph_state = initial_graph_state(request)
    graph_state.update(load_memory_node(graph_state))
    graph_state["intent"] = "ticket_status"
    graph_state["knowledge_initialized"] = True
    return graph_state


def with_fake_model(fake: Any):
    original = native.create_chat_model
    native.create_chat_model = lambda: fake
    return original


def test_registry_uses_closed_role_filtered_json_schema():
    student_names = {schema["function"]["name"] for schema in tool_schemas_for_role("student")}
    assert_true("query_admin_operation_logs" not in student_names, "student discovered admin tool")
    assert_true("query_inbox_summary" in student_names, "student tool missing")
    for tool in list_tools():
        schema = tool.argument_schema
        assert_equal(schema["type"], "object", "%s schema type" % tool.name)
        assert_equal(schema["additionalProperties"], False, "%s closed schema" % tool.name)
        metadata = tool.public_metadata()
        assert_true(metadata["roleScope"], "%s role scope" % tool.name)
        assert_true(metadata["sourceType"], "%s source type" % tool.name)
        assert_true(metadata["cardType"], "%s card type" % tool.name)
        assert_true(metadata["timeout"] > 0, "%s timeout" % tool.name)
        serialized = str(schema)
        for trusted_name in ["userId", "role", "token", "traceId", "traceParent"]:
            assert_true(trusted_name not in serialized, "%s leaked into schema" % trusted_name)


def test_single_multiple_and_duplicate_tool_calls():
    single = tool_response(("query_my_tickets", {"limit": 5}, "call-1"))
    parsed = native.parse_and_validate_tool_calls(single, "student")
    assert_equal(parsed[0]["toolCallId"], "call-1", "tool call id")
    assert_equal(parsed[0]["schemaValidation"], "passed", "schema validation")

    multiple = tool_response(
        ("query_my_tickets", {"limit": 5}, "call-1"),
        ("query_inbox_summary", {"limit": 5}, "call-2"),
    )
    assert_equal(len(native.parse_and_validate_tool_calls(multiple, "student")), 2, "multiple calls")

    duplicate = tool_response(
        ("query_my_tickets", {"limit": 5}, "call-1"),
        ("query_my_tickets", {"limit": 5}, "call-2"),
    )
    deduplicated = native.parse_and_validate_tool_calls(duplicate, "student")
    assert_equal(len(deduplicated), 1, "duplicate calls")


def test_invalid_unknown_and_forbidden_calls_are_rejected():
    cases = [
        (tool_response(("delete_ticket", {}, "bad-1")), "student", "UNKNOWN_TOOL"),
        (tool_response(("query_my_tickets", {"limit": 5, "userId": 99}, "bad-2")), "student", "INVALID_ARGUMENTS"),
        (tool_response(("query_ticket_detail", {"ticketId": "12"}, "bad-3")), "student", "INVALID_ARGUMENTS"),
        (tool_response(("query_admin_operation_logs", {"limit": 5}, "bad-4")), "student", "FORBIDDEN_TOOL"),
    ]
    for response, role, expected_code in cases:
        try:
            native.parse_and_validate_tool_calls(response, role)
        except native.ToolCallValidationError as exc:
            assert_equal(exc.code, expected_code, "validation code")
        else:
            raise AssertionError("invalid tool call was accepted")

    try:
        native.parse_and_validate_tool_calls(
            tool_response(("query_inbox_summary", {"limit": 5}, "wrong-intent")),
            "student",
            "ticket_status",
        )
    except native.ToolCallValidationError as exc:
        assert_equal(exc.code, "INTENT_TOOL_MISMATCH", "intent mismatch code")
    else:
        raise AssertionError("intent-mismatched tool call was accepted")


def test_invalid_native_plan_never_calls_business_http():
    original_tool = support_agent.call_business_tool
    calls = []
    old_mode = os.environ.get("AI_PLANNER_MODE")
    try:
        os.environ["AI_PLANNER_MODE"] = "hybrid"
        support_agent.call_business_tool = lambda *args: calls.append(args)
        cases = [
            (tool_response(("delete_ticket", {}, "unknown")), "UNKNOWN_TOOL"),
            (tool_response(("query_my_tickets", {"limit": 5, "userId": 99}, "extra")), "INVALID_ARGUMENTS"),
            (tool_response(("query_ticket_detail", {"ticketId": "12"}, "type")), "INVALID_ARGUMENTS"),
            (tool_response(("query_admin_operation_logs", {"limit": 5}, "forbidden")), "FORBIDDEN_TOOL"),
        ]
        for response, expected_reason in cases:
            fake = FakeChatModel([response])
            original_model = with_fake_model(fake)
            try:
                graph_state = state("student", "show ticket 12")
                graph_state.update(plan_tools_node(graph_state))
                graph_state.update(execute_tools_node(graph_state))
                assert_equal(graph_state["planner_mode"], "rule_fallback", "invalid planner mode")
                assert_equal(graph_state["planner_fallback_reason"], expected_reason, "invalid reason")
            finally:
                native.create_chat_model = original_model
        assert_equal(calls, [], "forbidden HTTP calls")
    finally:
        support_agent.call_business_tool = original_tool
        _restore_env("AI_PLANNER_MODE", old_mode)


def test_tool_message_final_generation_and_trace_fields():
    fake = FakeChatModel([
        tool_response(("query_my_tickets", {"limit": 5}, "ticket-call")),
        tool_response(content="你的工单正在处理中。", finish_reason="stop"),
    ])
    original_model = with_fake_model(fake)
    original_tool = support_agent.call_business_tool
    old_mode = os.environ.get("AI_PLANNER_MODE")
    http_calls = []
    try:
        os.environ["AI_PLANNER_MODE"] = "hybrid"

        def fake_tool(current, tool_name, arguments):
            http_calls.append((tool_name, arguments))
            return {
                "toolName": tool_name,
                "success": True,
                "data": [{"id": 12, "statusText": "processing"}],
                "count": 1,
                "latencyMs": 1.0,
                "toolProtocol": "http_internal",
            }

        support_agent.call_business_tool = fake_tool
        graph_state = state()
        graph_state.update(plan_tools_node(graph_state))
        graph_state.update(execute_tools_node(graph_state))
        graph_state.update(generate_node(graph_state))
        assert_equal(graph_state["response"], "你的工单正在处理中。", "native final answer")
        assert_equal(graph_state["planner_mode"], "native", "native planner mode")
        assert_equal(len(http_calls), 1, "business tool HTTP count")
        assert_true(any(isinstance(message, ToolMessage) for message in fake.bound.invocations[1]), "ToolMessage missing")
        record = graph_state["execution_records"][0]
        assert_equal(record["toolCallId"], "ticket-call", "execution tool call id")
        assert_equal(record["schemaValidation"], "passed", "execution schema validation")
        exposed_names = {schema["function"]["name"] for schema in fake.schemas}
        assert_equal(exposed_names, {"query_my_tickets", "query_ticket_detail"}, "intent-narrowed schemas")
    finally:
        native.create_chat_model = original_model
        support_agent.call_business_tool = original_tool
        _restore_env("AI_PLANNER_MODE", old_mode)


def test_model_failures_and_empty_tools_fall_back_to_rule():
    cases = [
        ([TimeoutError("planner timeout")], "MODEL_TIMEOUT"),
        ([tool_response(content="直接回答", finish_reason="stop")], "EMPTY_TOOL_CALLS"),
    ]
    old_mode = os.environ.get("AI_PLANNER_MODE")
    try:
        os.environ["AI_PLANNER_MODE"] = "hybrid"
        for responses, expected_reason in cases:
            fake = FakeChatModel(responses)
            original_model = with_fake_model(fake)
            try:
                result = native.start_native_plan(state(), [{"toolName": "query_my_tickets", "arguments": {"limit": 10}}])
                assert_equal(result.planner_mode, "rule_fallback", "failure planner mode")
                assert_equal(result.fallback_reason, expected_reason, "failure reason")
                assert_true(result.calls, "rule fallback calls")
            finally:
                native.create_chat_model = original_model

        # A general no-tool question still reaches a genuine tools-capable
        # model, but only one harmless schema is needed to prove it emitted no
        # tool_calls. This keeps the real-model smoke within the 8s call budget.
        fake = FakeChatModel([tool_response(content="你好", finish_reason="stop")])
        original_model = with_fake_model(fake)
        try:
            general_state = state(question="你好")
            general_state["intent"] = "general"
            result = native.start_native_plan(general_state, [])
            exposed_names = {schema["function"]["name"] for schema in fake.schemas}
            assert_equal(exposed_names, {"query_service_categories"}, "general schema narrowing")
            assert_equal(result.fallback_reason, "EMPTY_TOOL_CALLS", "general no-tool fallback")
            assert_equal(result.calls, [], "general no-tool calls")
        finally:
            native.create_chat_model = original_model
    finally:
        _restore_env("AI_PLANNER_MODE", old_mode)


def test_rag_only_exposes_no_tools_and_skips_model():
    fake = FakeChatModel([tool_response(("query_my_tickets", {"limit": 5}, "unexpected"))])
    original_model = with_fake_model(fake)
    old_mode = os.environ.get("AI_PLANNER_MODE")
    try:
        os.environ["AI_PLANNER_MODE"] = "hybrid"
        graph_state = state(question="预约需要什么材料")
        graph_state.update(intent="appointment_policy", retrieval_mode="RAG_ONLY")
        result = native.start_native_plan(graph_state, [])
        assert_equal(result.calls, [], "RAG-only calls")
        assert_equal(result.planner_mode, "rule", "RAG-only planner mode")
        assert_equal(fake.schemas, [], "RAG-only schemas")
        assert_equal(fake.bound.invocations, [], "RAG-only model invocations")
    finally:
        native.create_chat_model = original_model
        _restore_env("AI_PLANNER_MODE", old_mode)


def test_hybrid_tool_success_hides_second_round_model_failure():
    fake = FakeChatModel([
        tool_response(("query_service_points", {"limit": 10}, "point-call")),
        ConnectionError("generation unavailable"),
    ])
    original_model = with_fake_model(fake)
    original_tool = support_agent.call_business_tool
    old_mode = os.environ.get("AI_PLANNER_MODE")
    try:
        os.environ["AI_PLANNER_MODE"] = "hybrid"
        support_agent.call_business_tool = lambda current, tool_name, arguments: {
            "toolName": tool_name,
            "success": True,
            "data": [{"id": 830001, "name": "合成打印服务点", "address": "合成地址"}],
            "count": 1,
            "latencyMs": 1.0,
            "toolProtocol": "http_internal",
        }
        graph_state = state(question="图书馆附近哪里可以打印材料")
        graph_state.update(
            intent="printing",
            retrieval_mode="HYBRID",
            retrieved_context="合成正式知识",
            knowledge_sources=[
                KnowledgeSource(
                    type="knowledge",
                    title="合成知识",
                    snippet="合成正式知识",
                    knowledgeVersion="formal-v1",
                )
            ],
        )
        graph_state.update(plan_tools_node(graph_state))
        graph_state.update(execute_tools_node(graph_state))
        graph_state.update(generate_node(graph_state))
        response = finalize_response_node(graph_state)["response"]
        assert_true("合成正式知识" in response.answer, "hybrid knowledge answer")
        assert_true("合成打印服务点" in response.answer, "hybrid business answer")
        assert_equal(response.fallbackReason, None, "hybrid successful fallback reason")
        assert_equal(response.errorCode, None, "hybrid successful error code")
        assert_true(any(record["reason"] == "MODEL_UNAVAILABLE" for record in response.fallbackRecords), "second-round planner telemetry")
    finally:
        native.create_chat_model = original_model
        support_agent.call_business_tool = original_tool
        _restore_env("AI_PLANNER_MODE", old_mode)


def test_successful_rule_tool_answer_does_not_expose_model_failure():
    fake = FakeChatModel([ConnectionError("planner unavailable")])
    original_model = with_fake_model(fake)
    original_tool = support_agent.call_business_tool
    old_mode = os.environ.get("AI_PLANNER_MODE")
    try:
        os.environ["AI_PLANNER_MODE"] = "hybrid"
        support_agent.call_business_tool = lambda current, tool_name, arguments: {
            "toolName": tool_name,
            "success": True,
            "data": [{"id": 2600001, "status": 4, "statusText": "EXPIRED"}],
            "count": 1,
            "latencyMs": 1.0,
            "toolProtocol": "http_internal",
        }
        graph_state = state(question="my ticket status")
        graph_state.update(plan_tools_node(graph_state))
        graph_state.update(execute_tools_node(graph_state))
        graph_state.update(generate_node(graph_state))
        response = finalize_response_node(graph_state)["response"]
        assert_equal(response.fallbackReason, None, "successful business fallback reason")
        assert_equal(response.errorCode, None, "successful business error code")
        assert_true(any(record["reason"] == "MODEL_UNAVAILABLE" for record in response.fallbackRecords), "planner telemetry")
    finally:
        native.create_chat_model = original_model
        support_agent.call_business_tool = original_tool
        _restore_env("AI_PLANNER_MODE", old_mode)


def test_unsupported_model_and_round_limit():
    class UnsupportedModel:
        pass

    old_mode = os.environ.get("AI_PLANNER_MODE")
    try:
        os.environ["AI_PLANNER_MODE"] = "native"
        original_model = with_fake_model(UnsupportedModel())
        try:
            result = native.start_native_plan(state(), [])
            assert_equal(result.planner_mode, "rule_fallback", "unsupported fallback mode")
            assert_equal(result.fallback_reason, "MODEL_TOOLS_UNSUPPORTED", "unsupported reason")
        finally:
            native.create_chat_model = original_model

        fake = FakeChatModel([tool_response(("query_my_tickets", {"limit": 5}, "round-3"))])
        limited_state = state()
        limited_state.update(native_bound_model=fake.bound, native_messages=[], native_round=2)
        completion = native.continue_native_plan(limited_state, [])
        assert_equal(completion.fallback_reason, "TOOL_ROUND_LIMIT", "round limit")
    finally:
        _restore_env("AI_PLANNER_MODE", old_mode)


def test_metrics_expose_native_planner_operations():
    prometheus = metrics.prometheus()
    assert_true('operation="native_planner"' in prometheus, "native planner metric")
    assert_true('operation="native_planner.model"' in prometheus, "model latency metric")
    assert_true('operation="native_planner.invalid_tool"' in prometheus, "invalid tool metric")


def _restore_env(name: str, value):
    if value is None:
        os.environ.pop(name, None)
    else:
        os.environ[name] = value


def main() -> int:
    old_key = os.environ.get("OPENAI_API_KEY")
    os.environ["OPENAI_API_KEY"] = "fake-native-test-key"
    tests = [
        test_registry_uses_closed_role_filtered_json_schema,
        test_single_multiple_and_duplicate_tool_calls,
        test_invalid_unknown_and_forbidden_calls_are_rejected,
        test_invalid_native_plan_never_calls_business_http,
        test_tool_message_final_generation_and_trace_fields,
        test_model_failures_and_empty_tools_fall_back_to_rule,
        test_rag_only_exposes_no_tools_and_skips_model,
        test_successful_rule_tool_answer_does_not_expose_model_failure,
        test_hybrid_tool_success_hides_second_round_model_failure,
        test_unsupported_model_and_round_limit,
        test_metrics_expose_native_planner_operations,
    ]
    try:
        for test in tests:
            test()
    finally:
        _restore_env("OPENAI_API_KEY", old_key)
    print("native function calling tests passed: %d" % len(tests))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
