from __future__ import annotations

import agent.campus_support_agent as support_agent
from app.metrics import metrics


def base_state():
    return {
        "messages": [],
        "user_input": "查看 999 号工单详情",
        "retrieved_context": "",
        "knowledge_sources": [],
        "response": "",
        "intent": "ticket_status",
        "escalate": False,
        "knowledge_initialized": True,
        "service_points": [],
        "tickets": [],
        "appointments": [],
        "recommended_service_points": [],
        "user_id": 2006,
        "role": "student",
        "trace_id": "trace-test",
        "trace_parent": None,
        "business_tool_results": [],
        "memory_context": {},
        "agent_plan": {},
        "execution_records": [],
        "generation_record": {},
        "fallback_records": [],
    }


def assert_equal(actual, expected, message):
    if actual != expected:
        raise AssertionError("%s: expected %r got %r" % (message, expected, actual))


def assert_true(condition, message):
    if not condition:
        raise AssertionError(message)


def test_permission_fallback_record():
    original = support_agent.call_business_tool
    try:
        support_agent.call_business_tool = lambda state, tool_name, arguments: {
            "toolName": tool_name,
            "success": False,
            "data": None,
            "message": "PERMISSION_DENIED",
            "count": 0,
            "latencyMs": 3.5,
            "errorType": "PERMISSION_DENIED",
            "toolProtocol": "http_internal",
        }
        state = base_state()
        state.update(support_agent.execute_business_tools(state))
        assert_equal(state["agent_plan"]["intent"], "ticket_status", "plan intent")
        assert_equal(state["agent_plan"]["planned_tools"], ["query_ticket_detail"], "planned tool")
        assert_equal(state["execution_records"][0]["toolName"], "query_ticket_detail", "execution tool")
        assert_equal(state["fallback_records"][0]["reason"], "PERMISSION_DENIED", "tool fallback")
        state.update(support_agent.generate_response(state))
        assert_equal(state["generation_record"]["fallbackReason"], "PERMISSION_DENIED", "generation fallback")
        assert_true(any(record["reason"] == "PERMISSION_DENIED" for record in state["fallback_records"]), "permission fallback record missing")
    finally:
        support_agent.call_business_tool = original


def test_tool_timeout_fallback_record():
    original = support_agent.call_business_tool
    try:
        support_agent.call_business_tool = lambda state, tool_name, arguments: {
            "toolName": tool_name,
            "success": False,
            "data": None,
            "message": "TimeoutError",
            "count": 0,
            "latencyMs": 5000.0,
            "errorType": "TimeoutError",
            "toolProtocol": "http_internal",
        }
        state = base_state()
        state.update(support_agent.execute_business_tools(state))
        assert_equal(state["fallback_records"][0]["reason"], "TOOL_TIMEOUT", "timeout fallback")
        state.update(support_agent.generate_response(state))
        assert_equal(state["generation_record"]["fallbackReason"], "TOOL_TIMEOUT", "timeout generation fallback")
    finally:
        support_agent.call_business_tool = original


def test_generation_fallback_records():
    no_source_state = base_state()
    no_source_state["intent"] = "general"
    no_source_state["user_input"] = "一个没有来源的问题"
    no_source_state.update(support_agent.generate_response(no_source_state))
    assert_equal(no_source_state["generation_record"]["fallbackReason"], "NO_SOURCE", "no source fallback")
    assert_true(any(record["reason"] == "NO_SOURCE" for record in no_source_state["fallback_records"]), "no source record")

    knowledge_state = base_state()
    knowledge_state["knowledge_initialized"] = False
    knowledge_state.update(support_agent.generate_response(knowledge_state))
    assert_equal(knowledge_state["generation_record"]["fallbackReason"], "KNOWLEDGE_NOT_SYNCED", "knowledge fallback")
    assert_true(any(record["reason"] == "KNOWLEDGE_NOT_SYNCED" for record in knowledge_state["fallback_records"]), "knowledge record")


def test_metrics_have_stage_keys():
    operations = metrics.snapshot().get("operations", {})
    for key in ["planner.total", "tool_plan.total", "tool_execute.total", "fallback.total"]:
        assert_true(key in operations, "missing metrics key %s" % key)


def main() -> int:
    tests = [
        test_permission_fallback_record,
        test_tool_timeout_fallback_record,
        test_generation_fallback_records,
        test_metrics_have_stage_keys,
    ]
    for test in tests:
        test()
    print("execution trace tests passed: %d" % len(tests))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
