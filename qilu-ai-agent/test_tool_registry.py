from __future__ import annotations

from agent.tools.registry import list_tools, plan_tool_calls, tools_for_intent


EXPECTED_TOOL_NAMES = {
    "query_service_categories",
    "query_service_points",
    "query_service_point_slots",
    "query_my_tickets",
    "query_ticket_detail",
    "query_my_appointments",
    "query_appointment_detail",
    "query_inbox_summary",
    "query_station_comments",
    "query_admin_operation_logs",
    "query_admin_appointment_failure_logs",
}


def tool_names(calls):
    return [call["toolName"] for call in calls]


def assert_equal(actual, expected, message):
    if actual != expected:
        raise AssertionError("%s: expected %r got %r" % (message, expected, actual))


def assert_true(condition, message):
    if not condition:
        raise AssertionError(message)


def test_registered_tool_names():
    assert_equal({tool.name for tool in list_tools()}, EXPECTED_TOOL_NAMES, "registered tool names changed")


def test_each_registered_intent_has_tools():
    intents = {intent for tool in list_tools() for intent in tool.intents}
    for intent in intents:
        assert_true(tools_for_intent(intent), "intent %s has no registered tools" % intent)


def test_ticket_planning_by_entity_id():
    assert_equal(tool_names(plan_tool_calls("ticket_status", "我的工单进度")), ["query_my_tickets"], "ticket list planning")
    assert_equal(tool_names(plan_tool_calls("ticket_status", "查看 12 号工单详情")), ["query_ticket_detail"], "ticket detail planning")


def test_appointment_planning_by_entity_id():
    assert_equal(tool_names(plan_tool_calls("appointment_status", "我的预约")), ["query_my_appointments"], "appointment list planning")
    assert_equal(tool_names(plan_tool_calls("appointment_status", "查看 9 号预约")), ["query_appointment_detail"], "appointment detail planning")


def test_confirmed_entity_wins_over_text_and_memory_snapshot():
    calls = plan_tool_calls(
        "ticket_status",
        "查看 18 号工单",
        {"lastTicket": {"id": 18}},
        "student",
        {"appointmentId": None, "ticketId": 12, "servicePointId": None},
    )
    assert_equal(calls[0]["arguments"], {"ticketId": 12}, "confirmed ticket id")


def test_confirmed_service_point_queries_live_detail_by_id():
    calls = plan_tool_calls(
        "service_point_search",
        "第一个几点开门？",
        {},
        "student",
        {"appointmentId": None, "ticketId": None, "servicePointId": 990001},
    )
    assert_equal(
        calls,
        [{"toolName": "query_service_points", "arguments": {"limit": 10, "id": 990001}}],
        "confirmed service point live query",
    )


def test_admin_intents_only_use_admin_tools():
    admin_calls = plan_tool_calls("admin_operation_logs", "最近后台操作日志")
    failure_calls = plan_tool_calls("admin_appointment_failure_logs", "预约失败日志")
    assert_equal(tool_names(admin_calls), ["query_admin_operation_logs"], "admin operation log planning")
    assert_equal(tool_names(failure_calls), ["query_admin_appointment_failure_logs"], "admin failure log planning")


def test_other_intent_planning():
    expectations = {
        "service_categories": "query_service_categories",
        "repair": "query_service_points",
        "printing": "query_service_points",
        "express": "query_service_points",
        "consultation": "query_service_points",
        "service_point_search": "query_service_points",
        "service_point_comment_ranking": "query_service_points",
        "service_point_slots": "query_service_point_slots",
        "inbox_summary": "query_inbox_summary",
    }
    for intent, tool_name in expectations.items():
        assert_equal(tool_names(plan_tool_calls(intent, "测试问题")), [tool_name], "%s planning" % intent)
    assert_equal(tool_names(plan_tool_calls("station_comments", "服务点 1 有哪些评论")), ["query_station_comments"], "station comments planning")
    aggregate_calls = plan_tool_calls("service_point_comment_ranking", "哪个网点有留言")
    assert_equal(aggregate_calls[0]["arguments"], {"limit": 20}, "comment ranking planning")


def test_rag_only_intents_have_no_business_tools():
    for intent in ["appointment_policy", "ticket_policy", "campus_policy", "general"]:
        assert_equal(tools_for_intent(intent), [], "%s should not expose business tools" % intent)


def test_admin_tools_are_filtered_for_student_role():
    assert_true(tools_for_intent("admin_operation_logs"), "admin intent registration missing")
    assert_equal(tools_for_intent("admin_operation_logs", "student"), [], "student admin tools")


def main() -> int:
    tests = [
        test_registered_tool_names,
        test_each_registered_intent_has_tools,
        test_ticket_planning_by_entity_id,
        test_appointment_planning_by_entity_id,
        test_confirmed_entity_wins_over_text_and_memory_snapshot,
        test_confirmed_service_point_queries_live_detail_by_id,
        test_admin_intents_only_use_admin_tools,
        test_other_intent_planning,
        test_rag_only_intents_have_no_business_tools,
        test_admin_tools_are_filtered_for_student_role,
    ]
    for test in tests:
        test()
    print("tool registry tests passed: %d" % len(tests))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
