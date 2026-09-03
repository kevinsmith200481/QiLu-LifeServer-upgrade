from __future__ import annotations

from agent.mcp.adapter import mcp_tool_description
from agent.mcp.server import call_tool, list_tools


EXPECTED_DISCOVERABLE_TOOLS = {
    "query_service_points",
    "query_service_point_slots",
    "query_my_tickets",
    "query_ticket_detail",
    "query_my_appointments",
    "query_appointment_detail",
    "query_inbox_summary",
}


def assert_equal(actual, expected, message):
    if actual != expected:
        raise AssertionError("%s: expected %r got %r" % (message, expected, actual))


def assert_true(condition, message):
    if not condition:
        raise AssertionError(message)


def base_state():
    return {
        "trace_id": "trace-mcp-test",
        "user_id": 2006,
        "role": "student",
    }


def test_mcp_tool_discovery():
    tools = list_tools()
    names = {tool["name"] for tool in tools}
    assert_equal(names, EXPECTED_DISCOVERABLE_TOOLS, "discoverable MCP tools")
    assert_true("query_admin_operation_logs" not in names, "admin operation logs must not be discoverable")
    assert_true("query_admin_appointment_failure_logs" not in names, "admin failure logs must not be discoverable")


def test_mcp_tool_schema_shape():
    description = mcp_tool_description("query_ticket_detail")
    schema = description["inputSchema"]
    assert_equal(schema["type"], "object", "schema type")
    assert_equal(schema["properties"]["ticketId"]["type"], "integer", "ticketId schema")
    assert_true("ticketId" in schema["required"], "ticketId should be required")
    for field in ["roleScope", "sourceType", "cardType", "timeoutMs"]:
        assert_true(field in description, "missing descriptor field %s" % field)

    slot_schema = mcp_tool_description("query_service_point_slots")["inputSchema"]
    assert_true("servicePointId" not in slot_schema.get("required", []), "servicePointId should be optional")


def test_mcp_tool_call_success():
    seen = {}

    def fake_call(state, tool_name, arguments):
        seen["state"] = state
        seen["tool_name"] = tool_name
        seen["arguments"] = arguments
        return {
            "toolName": tool_name,
            "success": True,
            "data": [{"id": 1, "title": "T1"}],
            "message": None,
            "count": 1,
            "latencyMs": 2.5,
            "errorType": None,
            "toolProtocol": "http_internal",
        }

    result = call_tool(base_state(), "query_my_tickets", {"limit": 3}, call_business_tool_func=fake_call)
    assert_equal(seen["tool_name"], "query_my_tickets", "delegated tool name")
    assert_equal(seen["arguments"], {"limit": 3}, "delegated arguments")
    assert_true(result["success"], "MCP call should succeed")
    assert_equal(result["toolProtocol"], "mcp_adapter", "MCP protocol marker")
    assert_equal(result["traceId"], "trace-mcp-test", "trace id")
    assert_equal(result["count"], 1, "result count")


def test_mcp_permission_denial_is_sanitized():
    def fake_call(state, tool_name, arguments):
        return {
            "toolName": tool_name,
            "success": False,
            "data": [{"secret": "should not leak"}],
            "message": "PERMISSION_DENIED",
            "count": 1,
            "latencyMs": 1.0,
            "errorType": "PERMISSION_DENIED",
        }

    result = call_tool(base_state(), "query_ticket_detail", {"ticketId": 999}, call_business_tool_func=fake_call)
    assert_true(not result["success"], "permission denial should fail")
    assert_equal(result["errorType"], "PERMISSION_DENIED", "permission error type")
    assert_equal(result["data"], None, "permission denial data")
    assert_equal(result["count"], 0, "permission denial count")


def test_mcp_rejects_admin_and_unknown_tools():
    def fake_call(state, tool_name, arguments):
        raise AssertionError("undiscoverable tools must not be delegated")

    admin_result = call_tool(base_state(), "query_admin_operation_logs", {"limit": 10}, call_business_tool_func=fake_call)
    unknown_result = call_tool(base_state(), "not_a_tool", {}, call_business_tool_func=fake_call)
    assert_equal(admin_result["errorType"], "MCP_TOOL_NOT_DISCOVERABLE", "admin tool rejection")
    assert_equal(unknown_result["errorType"], "MCP_TOOL_NOT_DISCOVERABLE", "unknown tool rejection")


def test_mcp_timeout_and_exception_results():
    def timeout_call(state, tool_name, arguments):
        raise TimeoutError("slow")

    def broken_call(state, tool_name, arguments):
        raise RuntimeError("broken")

    timeout_result = call_tool(base_state(), "query_inbox_summary", {"limit": 5}, call_business_tool_func=timeout_call)
    broken_result = call_tool(base_state(), "query_inbox_summary", {"limit": 5}, call_business_tool_func=broken_call)
    assert_equal(timeout_result["errorType"], "TimeoutError", "timeout error type")
    assert_equal(broken_result["errorType"], "RuntimeError", "exception error type")
    assert_equal(timeout_result["toolProtocol"], "mcp_adapter", "timeout protocol")
    assert_equal(broken_result["toolProtocol"], "mcp_adapter", "exception protocol")


def main() -> int:
    tests = [
        test_mcp_tool_discovery,
        test_mcp_tool_schema_shape,
        test_mcp_tool_call_success,
        test_mcp_permission_denial_is_sanitized,
        test_mcp_rejects_admin_and_unknown_tools,
        test_mcp_timeout_and_exception_results,
    ]
    for test in tests:
        test()
    print("mcp adapter tests passed: %d" % len(tests))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
