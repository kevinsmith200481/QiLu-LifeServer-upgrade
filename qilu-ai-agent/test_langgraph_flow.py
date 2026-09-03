from __future__ import annotations

import os
from contextlib import contextmanager

# Existing LangGraph regression is deterministic and must never spend a real
# model call. Native planner behavior has its own FakeChatModel contract suite.
os.environ["AI_PLANNER_MODE"] = "rule"

import agent.campus_support_agent as support_agent
import agent.intent_router as intent_router
from agent.langgraph_flow import build_langgraph_flow, run_langgraph_agent
from agent.langgraph_nodes import (
    classify_intent_node,
    execute_tools_node,
    finalize_response_node,
    generate_node,
    load_memory_node,
    plan_tools_node,
    retrieve_node,
    select_retrieval_policy_node,
)
from agent.langgraph_state import empty_graph_state, initial_graph_state
from app.metrics import metrics
from app.schemas import CampusAssistantRequest, CampusServicePoint, CampusTicket, KnowledgeSource


def assert_equal(actual, expected, message):
    if actual != expected:
        raise AssertionError("%s: expected %r got %r" % (message, expected, actual))


def assert_true(condition, message):
    if not condition:
        raise AssertionError(message)


def request(question: str = "repair my dorm door") -> CampusAssistantRequest:
    return CampusAssistantRequest(
        userId=2006,
        role="student",
        traceId="lg-test",
        traceParent="00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
        conversationId="conv-lg",
        question=question,
        servicePoints=[
            CampusServicePoint(
                id=1,
                name="Dorm repair center",
                categoryName="repair",
                address="Building 1",
                openHours="08:00-18:00",
                description="Dorm water, electricity, door and window repair",
            )
        ],
    )


def graph_state(question: str = "repair my dorm door"):
    state = initial_graph_state(request(question))
    state.update(load_memory_node(state))
    return state


def patch_attr(module, name, value):
    original = getattr(module, name)
    setattr(module, name, value)
    return original


def test_empty_state_initialization():
    state = empty_graph_state()
    for key in [
        "request",
        "trace_id",
        "traceparent",
        "orchestrator",
        "memory_summary",
        "intent_classification",
        "intent_decision",
        "intent_source",
        "intent_confidence",
        "intent_router_mode",
        "classifier_fallback_reason",
        "retrieval_mode",
        "routing_reason",
        "low_confidence",
        "permission_denied",
        "retrieved_context",
        "knowledge_sources",
        "intent",
        "confidence",
        "need_create_ticket",
        "planned_tools",
        "tool_results",
        "business_cards",
        "action_drafts",
        "fallback_reason",
        "execution_records",
        "lang_graph_nodes",
        "response",
        "errors",
    ]:
        assert_true(key in state, "missing graph state key %s" % key)
    assert_equal(state["intent"], "general", "default intent")
    assert_equal(state["orchestrator"], "langgraph", "default graph orchestrator")
    assert_equal(state["errors"], [], "default errors")


def test_request_trace_injection():
    state = initial_graph_state(request())
    assert_equal(state["trace_id"], "lg-test", "initial trace id")
    state.update(load_memory_node(state))
    assert_equal(state["trace_id"], "lg-test", "loaded trace id")
    assert_equal(state["traceparent"], "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01", "traceparent")
    assert_equal(state["memory_summary"]["conversationId"], "conv-lg", "memory conversation")


def test_finalize_converts_response_fields():
    state = graph_state()
    state.update(
        response="Use the dorm repair center.",
        intent="repair",
        escalate=True,
        knowledge_sources=[
            KnowledgeSource(type="knowledge", knowledgeId=1, title="Dorm repair", snippet="Bring room number.", score=0.9)
        ],
        recommended_service_points=state["service_points"],
        orchestrator="langgraph",
        lang_graph_nodes=[
            {
                "order": 1,
                "nodeName": "load_memory",
                "status": "SUCCESS",
                "latencyMs": 1.2,
            }
        ],
        execution_records=[
            {
                "toolName": "query_my_tickets",
                "arguments": {"ticketId": 12},
                "success": True,
                "count": 1,
                "latencyMs": 2.0,
                "errorType": None,
                "toolProtocol": "http_internal",
            }
        ],
        business_tool_results=[
            {
                "toolName": "query_my_tickets",
                "success": True,
                "data": [{"id": 12, "title": "Door issue", "statusText": "open"}],
                "count": 1,
            }
        ],
    )
    update = finalize_response_node(state)
    response = update["response"]
    assert_true(response.sources, "sources should be converted")
    assert_equal(response.orchestrator, "langgraph", "response orchestrator")
    assert_equal(response.langGraphNodes[0]["nodeName"], "load_memory", "response node record")
    assert_true("arguments" not in response.executionRecords[0], "execution record arguments sanitized")
    assert_equal(response.businessCards[0]["type"], "ticket", "business card type")
    assert_true(any(draft["type"] == "create_ticket_draft" for draft in response.actionDrafts), "ticket draft")
    assert_equal(update["business_cards"][0]["type"], "ticket", "state business cards")


def test_permission_denied_clears_sensitive_fields():
    state = graph_state("show ticket 999")
    state.update(
        response="permission denied",
        intent="ticket_status",
        knowledge_sources=[KnowledgeSource(type="knowledge", title="Hidden", snippet="should clear")],
        business_tool_results=[
            {
                "toolName": "query_ticket_detail",
                "success": False,
                "data": None,
                "message": "PERMISSION_DENIED",
                "count": 0,
            }
        ],
        fallback_records=[
            {
                "reason": "PERMISSION_DENIED",
                "stage": "tool_execute",
                "detail": {
                    "toolName": "query_ticket_detail",
                    "arguments": {"ticketId": 999},
                    "errorType": "PERMISSION_DENIED",
                    "toolProtocol": "http_internal",
                },
            }
        ],
    )
    response = finalize_response_node(state)["response"]
    assert_equal(response.fallbackReason, "PERMISSION_DENIED", "permission fallback")
    assert_equal(response.sources, [], "permission sources")
    assert_equal(response.businessCards, [], "permission cards")
    assert_true("arguments" not in response.fallbackRecords[0]["detail"], "fallback detail arguments sanitized")


def test_role_permission_is_denied_before_planner_and_tools():
    state = graph_state("查看最近后台操作日志")
    state.update(intent="admin_operation_logs", retrieval_mode="BUSINESS_ONLY")
    state.update(plan_tools_node(state))
    assert_equal(state["permission_denied"], True, "student admin permission")
    assert_equal(state["planned_tools"], [], "denied planned tools")
    state.update(execute_tools_node(state))
    state.update(generate_node(state))
    response = finalize_response_node(state)["response"]
    assert_equal(response.fallbackReason, "PERMISSION_DENIED", "denied fallback")
    assert_equal(response.sources, [], "denied sources")
    assert_equal(response.businessCards, [], "denied cards")
    assert_equal(response.recommendedServicePoints, [], "denied recommendations")
    assert_equal(response.actionDrafts, [], "denied action drafts")


def test_rag_hit_node():
    original = patch_attr(
        support_agent,
        "retrieve_context",
        lambda state: {
            "retrieved_context": "Dorm repair source",
            "knowledge_sources": [KnowledgeSource(type="knowledge", title="Dorm repair", snippet="source")],
            "knowledge_initialized": True,
        },
    )
    try:
        state = graph_state()
        state.update(retrieve_node(state))
        assert_equal(state["retrieved_context"], "Dorm repair source", "rag context")
        assert_equal(len(state["knowledge_sources"]), 1, "rag source count")
    finally:
        support_agent.retrieve_context = original


def test_no_source_and_knowledge_fallback_nodes():
    original_answer_model = patch_attr(
        support_agent,
        "invoke_llm_result",
        lambda *args, **kwargs: support_agent.ModelInvocationResult("当前没有可靠来源，请补充关键信息。"),
    )
    original_llm_enabled = patch_attr(support_agent, "llm_enabled", lambda: True)
    try:
        rag_no_source = graph_state("预约需要准备什么材料？")
        rag_no_source.update(intent="appointment_policy", retrieval_mode="RAG_ONLY")
        rag_no_source.update(generate_node(rag_no_source))
        assert_equal(
            rag_no_source["generation_record"]["fallbackReason"],
            "NO_SOURCE",
            "RAG no source fallback",
        )
        assert_equal(rag_no_source["generation_record"]["generationMode"], "llm_no_source", "no source model generation")
        assert_true(rag_no_source["generation_record"]["usedLLM"], "no source answer model used")
    finally:
        support_agent.invoke_llm_result = original_answer_model
        support_agent.llm_enabled = original_llm_enabled

    knowledge = graph_state("ticket help")
    knowledge["knowledge_initialized"] = False
    knowledge.update(generate_node(knowledge))
    assert_equal(knowledge["generation_record"]["fallbackReason"], "KNOWLEDGE_NOT_SYNCED", "knowledge fallback")


def test_tool_success_permission_and_timeout_nodes():
    calls = []

    def fake_tool(state, tool_name, arguments):
        calls.append((tool_name, arguments))
        return {
            "toolName": tool_name,
            "success": True,
            "data": [{"id": 7, "title": "Door repair", "statusText": "processing"}],
            "message": None,
            "count": 1,
            "latencyMs": 2.0,
            "toolProtocol": "http_internal",
        }

    original = patch_attr(support_agent, "call_business_tool", fake_tool)
    try:
        state = graph_state("show ticket 7")
        state.update(intent="ticket_status", planned_tools=[{"toolName": "query_ticket_detail", "arguments": {"ticketId": 7}}])
        state.update(execute_tools_node(state))
        assert_equal(state["tool_results"][0]["success"], True, "tool success")
        assert_equal(state["execution_records"][0]["toolProtocol"], "http_internal", "tool protocol")
        assert_equal(calls[0][1]["ticketId"], 7, "tool argument")
    finally:
        support_agent.call_business_tool = original

    for message, reason in [("PERMISSION_DENIED", "PERMISSION_DENIED"), ("TimeoutError", "TOOL_TIMEOUT")]:
        original = patch_attr(
            support_agent,
            "call_business_tool",
            lambda state, tool_name, arguments, message=message: {
                "toolName": tool_name,
                "success": False,
                "data": None,
                "message": message,
                "count": 0,
                "latencyMs": 5000.0,
                "errorType": message,
                "toolProtocol": "http_internal",
            },
        )
        try:
            state = graph_state("show ticket 999")
            state.update(intent="ticket_status", planned_tools=[{"toolName": "query_ticket_detail", "arguments": {"ticketId": 999}}])
            state.update(execute_tools_node(state))
            assert_equal(state["fallback_records"][0]["reason"], reason, "tool fallback %s" % reason)
        finally:
            support_agent.call_business_tool = original


def test_memory_followup_plans_tool_from_context():
    req = CampusAssistantRequest(
        userId=2006,
        role="student",
        traceId="lg-memory",
        conversationId="conv-memory",
        question="that ticket needs a supplement",
        lastBusinessContext={"lastTicket": {"id": 321, "title": "Dorm issue", "type": "ticket"}},
    )
    state = initial_graph_state(req)
    state.update(load_memory_node(state))
    state.update(classify_intent_node(state))
    state.update(select_retrieval_policy_node(state))
    state.update(plan_tools_node(state))
    assert_equal(state["intent"], "ticket_status", "memory intent")
    assert_equal(state["planned_tools"][0]["toolName"], "query_ticket_detail", "memory tool")
    assert_equal(state["planned_tools"][0]["arguments"]["ticketId"], 321, "memory ticket id")


def test_chinese_appointment_record_question_plans_business_tool():
    state = graph_state("\u6211\u7684\u9884\u7ea6\u8bb0\u5f55\u53d1\u751f\u4e86\u4ec0\u4e48")
    state.update(classify_intent_node(state))
    state.update(select_retrieval_policy_node(state))
    state.update(plan_tools_node(state))
    assert_equal(state["intent"], "appointment_status", "Chinese appointment intent")
    assert_equal(state["planned_tools"][0]["toolName"], "query_my_appointments", "appointment tool")
    answer = support_agent.format_appointment_tool_answer([
        {"id": 2600001, "status": 4, "statusText": "EXPIRED"}
    ])
    assert_true("\u5df2\u8fc7\u671f" in answer, "appointment status localization")
    source = support_agent.source_from_tool_item(
        "query_my_appointments",
        {"id": 2600001, "status": 4, "statusText": "EXPIRED"},
    )
    assert_equal(source.statusText, "\u5df2\u8fc7\u671f", "appointment source status localization")
    cards = support_agent.business_tool_cards([{
        "toolName": "query_my_appointments",
        "success": True,
        "data": [{"id": 2600001, "status": 4, "statusText": "EXPIRED"}],
    }])
    assert_equal(cards[0]["statusText"], "\u5df2\u8fc7\u671f", "appointment card status localization")
    assert_equal(
        support_agent.display_appointment_status("UNKNOWN", 1),
        "\u5df2\u9884\u7ea6",
        "unknown appointment text falls back to numeric status",
    )


def test_semantic_routing_target_graph_topology():
    graph = build_langgraph_flow().get_graph()
    edges = {(edge.source, edge.target) for edge in graph.edges}
    required_edges = {
        ("__start__", "load_memory"),
        ("load_memory", "classify_intent"),
        ("classify_intent", "select_retrieval_policy"),
        ("select_retrieval_policy", "route_query"),
        ("route_query", "generate"),
        ("route_query", "check_escalation"),
        ("route_query", "retrieve"),
        ("route_query", "generate_clarification"),
        ("retrieve", "check_escalation"),
        ("check_escalation", "plan_tools"),
        ("generate_clarification", "finalize_response"),
        ("finalize_response", "__end__"),
    }
    assert_true(required_edges.issubset(edges), "semantic routing graph edges")
    assert_true(("load_memory", "retrieve") not in edges, "old retrieval-first edge removed")
    assert_true(all(source != "detect_intent" and target != "detect_intent" for source, target in edges), "old intent node removed")


def semantic_router_output(question: str):
    common = {
        "confidence": 0.95,
        "entities": {"appointmentId": None, "ticketId": None, "servicePointId": None},
        "intentSource": "semantic_model",
    }
    if question == "我的预约记录发生了什么":
        return dict(common, intent="appointment_status", scope="user_private", candidateIntents=["appointment_status"])
    if question == "预约需要准备什么材料？":
        return dict(common, intent="appointment_policy", scope="public_knowledge", candidateIntents=["appointment_policy"])
    if question == "预约怎么了？":
        return dict(
            common,
            intent="ambiguous",
            scope="ambiguous",
            candidateIntents=["appointment_status", "appointment_policy"],
        )
    if question == "图书馆附近哪里可以打印材料？":
        return dict(common, intent="printing", scope="mixed", candidateIntents=["printing"])
    if question == "你好":
        return dict(common, intent="casual_chat", scope="conversational", candidateIntents=["casual_chat"])
    if question.startswith("intent:"):
        intent = intent_router.IntentName(question.split(":", 1)[1])
        return dict(
            common,
            intent=intent.value,
            scope=intent_router.INTENT_SCOPES[intent].value,
            candidateIntents=[intent.value],
        )
    raise AssertionError("unexpected semantic routing question")


def run_semantic_route_case(
    orchestrator: str,
    question: str,
    direct_answer: str | None = None,
    memory: dict | None = None,
):
    counts = {"rag": 0, "tool": 0, "toolCalls": []}

    def fake_retrieve(state):
        counts["rag"] += 1
        return {
            "retrieved_context": "合成正式知识",
            "knowledge_sources": [KnowledgeSource(type="knowledge", knowledgeId=810001, title="合成知识", snippet="合成正式知识")],
            "knowledge_initialized": True,
        }

    def fake_tool(state, tool_name, arguments):
        counts["tool"] += 1
        counts["toolCalls"].append({"toolName": tool_name, "arguments": dict(arguments)})
        if tool_name == "query_my_appointments":
            data = [{"id": 820001, "servicePointName": "合成预约点", "statusText": "已过期"}]
        elif tool_name == "query_ticket_detail":
            data = {
                "id": arguments["ticketId"],
                "title": "实时工单",
                "statusText": "实时处理中",
                "studentReplyRequired": 0,
            }
        else:
            data = [{"id": 830001, "name": "合成打印服务点", "address": "合成地址"}]
        return {
            "toolName": tool_name,
            "success": True,
            "data": data,
            "message": None,
            "count": len(data) if isinstance(data, list) else 1,
            "latencyMs": 0.0,
            "toolProtocol": "stage_c_fake",
            "metricsRecorded": True,
        }

    original_retrieve = patch_attr(support_agent, "retrieve_context", fake_retrieve)
    original_tool = patch_attr(support_agent, "call_business_tool", fake_tool)
    original_invoker = patch_attr(intent_router, "invoke_semantic_model", lambda payload: semantic_router_output(payload["question"]))
    original_answer_model = patch_attr(
        support_agent,
        "invoke_llm_result",
        lambda system_prompt, user_prompt, trace_id=None: support_agent.ModelInvocationResult(
            direct_answer,
            None if direct_answer else "MODEL_UNAVAILABLE",
        ),
    )
    old_environment = {name: os.environ.get(name) for name in ["AI_INTENT_ROUTER_MODE", "AI_PLANNER_MODE", "AI_LLM_ENABLED"]}
    os.environ["AI_INTENT_ROUTER_MODE"] = "semantic"
    os.environ["AI_PLANNER_MODE"] = "rule"
    os.environ["AI_LLM_ENABLED"] = "false"
    try:
        req = CampusAssistantRequest(
            userId=None,
            role="student",
            traceId="stage-c-%s" % orchestrator,
            conversationId="session-stage-d" if memory else None,
            question=question,
            memory=memory,
        )
        if orchestrator == "langgraph":
            response = run_langgraph_agent(req)
        else:
            response = support_agent.CampusSupportAgent.__new__(support_agent.CampusSupportAgent)._chat_legacy(req)
        return response, counts
    finally:
        support_agent.retrieve_context = original_retrieve
        support_agent.call_business_tool = original_tool
        support_agent.invoke_llm_result = original_answer_model
        intent_router.invoke_semantic_model = original_invoker
        for name, value in old_environment.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


def test_langgraph_and_legacy_semantic_route_matrix():
    cases = [
        ("我的预约记录发生了什么", "appointment_status", "BUSINESS_ONLY", 0, 1),
        ("预约需要准备什么材料？", "appointment_policy", "RAG_ONLY", 1, 0),
        ("预约怎么了？", "ambiguous", "CLARIFY", 0, 0),
        ("图书馆附近哪里可以打印材料？", "printing", "HYBRID", 1, 1),
    ]
    for question, expected_intent, expected_mode, expected_rag, expected_tools in cases:
        graph_response, graph_counts = run_semantic_route_case("langgraph", question)
        legacy_response, legacy_counts = run_semantic_route_case("legacy", question)
        for response, counts in [(graph_response, graph_counts), (legacy_response, legacy_counts)]:
            assert_equal(response.intent, expected_intent, question + " intent")
            assert_equal(response.retrievalMode, expected_mode, question + " retrieval mode")
            assert_equal(response.intentSource, "semantic_model", question + " intent source")
            assert_equal(counts["rag"], expected_rag, question + " RAG calls")
            assert_equal(counts["tool"], expected_tools, question + " tool calls")
        assert_equal(graph_response.fallbackReason, legacy_response.fallbackReason, question + " fallback parity")
        if expected_mode == "CLARIFY":
            assert_equal(graph_response.sources, [], "clarify graph sources")
            assert_equal(legacy_response.sources, [], "clarify legacy sources")
            assert_equal(graph_response.businessCards, [], "clarify graph cards")
            assert_equal(legacy_response.businessCards, [], "clarify legacy cards")

    hybrid_response, _ = run_semantic_route_case("langgraph", "图书馆附近哪里可以打印材料？")
    assert_true("合成正式知识" in hybrid_response.answer, "hybrid answer knowledge")
    assert_true("合成打印服务点" in hybrid_response.answer, "hybrid answer business")
    assert_equal({source.type for source in hybrid_response.sources}, {"knowledge", "service_point"}, "hybrid source types")

    clarify_response, _ = run_semantic_route_case("langgraph", "预约怎么了？")
    node_names = [record["nodeName"] for record in clarify_response.langGraphNodes]
    assert_equal(
        node_names,
        [
            "load_memory",
            "classify_intent",
            "select_retrieval_policy",
            "route_query",
            "generate_clarification",
            "finalize_response",
        ],
        "clarify graph nodes",
    )


def stage_d_ticket_memory(ticket_ids):
    return {
        "mode": "v2",
        "schemaVersion": "2",
        "conversationId": "session-stage-d",
        "recentTurns": [
            {
                "turnId": "turn-stage-d-1",
                "question": "查看工单",
                "answer": "已通过实时工具查询",
                "intent": "ticket_status",
            }
        ],
        "rollingSummary": "此前咨询过校园维修工单。",
        "entities": {
            "tickets": [
                {
                    "id": ticket_id,
                    "lastSeenTurnId": "turn-stage-d-%s" % index,
                    "lastSeenMessageId": 100 + index,
                }
                for index, ticket_id in enumerate(ticket_ids, start=1)
            ],
            "appointments": [],
            "servicePoints": [],
            "pendingActionDraft": None,
        },
        "lastProcessedMessageId": 120,
        "summaryVersion": 4,
        "truncated": False,
        "estimatedTokens": 280,
    }


def test_memory_v2_resolution_is_identical_across_orchestrators():
    cases = [
        ("刚才那个工单怎么样？", [12], "ticket_status", "BUSINESS_ONLY", "memory_unique_entity", 12),
        ("12 号那个怎么样？", [18, 12], "ticket_status", "BUSINESS_ONLY", "explicit_id", 12),
        ("其中一个怎么样？", [18, 12], "ambiguous", "CLARIFY", "memory_multiple_candidates", None),
    ]
    for question, ticket_ids, intent, mode, source, expected_id in cases:
        results = []
        for orchestrator in ["langgraph", "legacy"]:
            response, counts = run_semantic_route_case(
                orchestrator,
                question,
                memory=stage_d_ticket_memory(ticket_ids),
            )
            assert_equal(response.intent, intent, orchestrator + " memory intent")
            assert_equal(response.retrievalMode, mode, orchestrator + " memory route")
            assert_equal(counts["rag"], 0, orchestrator + " memory RAG calls")
            assert_equal(response.memoryDiagnostics.resolutionSource, source, orchestrator + " resolution source")
            if expected_id is None:
                assert_equal(counts["tool"], 0, orchestrator + " clarify tool calls")
                assert_equal(counts["toolCalls"], [], orchestrator + " clarify tool candidates")
            else:
                assert_equal(counts["tool"], 1, orchestrator + " detail tool calls")
                assert_equal(
                    counts["toolCalls"],
                    [{"toolName": "query_ticket_detail", "arguments": {"ticketId": expected_id}}],
                    orchestrator + " confirmed tool candidate",
                )
            results.append((response.intent, response.retrievalMode, counts["toolCalls"]))
        assert_equal(results[0], results[1], question + " orchestrator parity")


def test_casual_chat_calls_answer_model_without_rag_or_business_tools():
    answer = "你好，我是校园服务智能助手。"
    for orchestrator in ["langgraph", "legacy"]:
        response, counts = run_semantic_route_case(orchestrator, "你好", direct_answer=answer)
        assert_equal(response.intent, "casual_chat", orchestrator + " casual intent")
        assert_equal(response.retrievalMode, "DIRECT_LLM", orchestrator + " direct mode")
        assert_equal(response.intentSource, "semantic_model", orchestrator + " intent source")
        assert_equal(response.answer, answer, orchestrator + " direct model answer")
        assert_equal(response.sources, [], orchestrator + " direct sources")
        assert_equal(response.businessCards, [], orchestrator + " direct cards")
        assert_equal(response.fallbackReason, None, orchestrator + " direct fallback")
        assert_equal(counts, {"rag": 0, "tool": 0, "toolCalls": []}, orchestrator + " direct call counts")

    graph_response, _ = run_semantic_route_case("langgraph", "你好", direct_answer=answer)
    assert_equal(
        [record["nodeName"] for record in graph_response.langGraphNodes],
        [
            "load_memory",
            "classify_intent",
            "select_retrieval_policy",
            "route_query",
            "generate",
            "finalize_response",
        ],
        "direct graph nodes",
    )


def test_langgraph_and_legacy_all_intent_policy_parity():
    checked = 0
    for intent, retrieval_mode in intent_router.INTENT_RETRIEVAL_MODES.items():
        question = "intent:" + intent.value
        graph_response, graph_counts = run_semantic_route_case("langgraph", question)
        legacy_response, legacy_counts = run_semantic_route_case("legacy", question)
        assert_equal(graph_response.intent, legacy_response.intent, question + " intent parity")
        assert_equal(graph_response.retrievalMode, legacy_response.retrievalMode, question + " mode parity")
        assert_equal(graph_response.intentSource, legacy_response.intentSource, question + " source parity")
        assert_equal(graph_response.fallbackReason, legacy_response.fallbackReason, question + " fallback parity")
        assert_equal(graph_response.intent, intent.value, question + " expected intent")
        assert_equal(graph_response.retrievalMode, retrieval_mode.value, question + " expected mode")
        assert_equal(graph_counts, legacy_counts, question + " call-count parity")
        checked += 1
    assert_equal(checked, len(intent_router.IntentName), "all intent policies checked")


def test_retrieval_mode_strictly_filters_sources_and_cards():
    knowledge = KnowledgeSource(
        type="knowledge",
        knowledgeId=810001,
        title="正式知识",
        snippet="正式知识内容",
        knowledgeVersion="formal-v1",
    )
    business_result = {
        "toolName": "query_service_points",
        "success": True,
        "data": [{"id": 830001, "name": "打印服务点", "address": "图书馆一层"}],
        "count": 1,
    }

    business_state = graph_state()
    business_state.update(
        response="business",
        intent="appointment_status",
        retrieval_mode="BUSINESS_ONLY",
        knowledge_sources=[knowledge],
        business_tool_results=[business_result],
    )
    business_response = finalize_response_node(business_state)["response"]
    assert_true(business_response.sources, "business sources")
    assert_true(all(source.type != "knowledge" for source in business_response.sources), "BUSINESS_ONLY knowledge leak")

    rag_state = graph_state()
    rag_state.update(
        response="rag",
        user_input="预约怎么取消？",
        intent="appointment_policy",
        retrieval_mode="RAG_ONLY",
        knowledge_sources=[knowledge],
        business_tool_results=[business_result],
    )
    rag_response = finalize_response_node(rag_state)["response"]
    assert_equal([source.type for source in rag_response.sources], ["knowledge"], "RAG_ONLY sources")
    assert_equal(rag_response.businessCards, [], "RAG_ONLY cards")
    assert_equal(rag_response.actionDrafts, [], "RAG_ONLY action drafts")
    assert_equal(rag_response.sources[0].knowledgeVersion, "formal-v1", "RAG active version")

    hybrid_state = graph_state()
    hybrid_state.update(
        response="hybrid",
        intent="printing",
        retrieval_mode="HYBRID",
        knowledge_sources=[knowledge],
        business_tool_results=[business_result],
    )
    hybrid_response = finalize_response_node(hybrid_state)["response"]
    assert_equal([source.type for source in hybrid_response.sources], ["knowledge", "service_point"], "HYBRID sources")
    assert_equal(hybrid_response.sources[0].knowledgeVersion, "formal-v1", "HYBRID active version")


def test_hybrid_answer_model_receives_knowledge_and_read_only_business_context():
    prompts = []
    original_answer_model = patch_attr(
        support_agent,
        "invoke_llm_result",
        lambda system_prompt, *args, **kwargs: (
            prompts.append(system_prompt)
            or support_agent.ModelInvocationResult("请到图书馆二楼打印服务点办理打印。")
        ),
    )
    original_llm_enabled = patch_attr(support_agent, "llm_enabled", lambda: True)
    try:
        state = graph_state("图书馆附近哪里可以打印？")
        state.update(
            intent="printing",
            retrieval_mode="HYBRID",
            retrieved_context="正式知识要求先准备待打印文件。",
            tickets=[CampusTicket(id=930001, title="不可进入混合回答的历史工单", statusText="处理中")],
            business_tool_results=[{
                "toolName": "query_service_points",
                "success": True,
                "data": [{
                    "id": 940001,
                    "name": "图书馆打印服务点",
                    "address": "图书馆二楼东侧",
                    "openHours": "08:30-22:00",
                    "description": "提供打印服务",
                }],
                "count": 1,
            }],
        )
        state.update(generate_node(state))
    finally:
        support_agent.invoke_llm_result = original_answer_model
        support_agent.llm_enabled = original_llm_enabled

    assert_equal(state["generation_record"]["generationMode"], "llm_hybrid", "HYBRID model generation")
    assert_true(state["generation_record"]["usedLLM"], "HYBRID answer model used")
    assert_true("准备待打印文件" in prompts[0], "HYBRID prompt knowledge context")
    assert_true("图书馆二楼东侧" in prompts[0], "HYBRID prompt business context")
    assert_true("不可进入混合回答的历史工单" not in prompts[0], "HYBRID prompt excludes unrelated memory ticket")


def test_classifier_failure_is_explicit_and_does_not_execute_business_tools():
    original_invoker = patch_attr(intent_router, "invoke_semantic_model", lambda payload: (_ for _ in ()).throw(TimeoutError("router timeout")))
    original_tool = patch_attr(
        support_agent,
        "call_business_tool",
        lambda state, tool_name, arguments: {
            "toolName": tool_name,
            "success": True,
            "data": [{"id": 7, "title": "宿舍报修", "statusText": "处理中"}],
            "count": 1,
            "latencyMs": 1.0,
            "toolProtocol": "stage_d_fake",
        },
    )
    old_mode = os.environ.get("AI_INTENT_ROUTER_MODE")
    try:
        os.environ["AI_INTENT_ROUTER_MODE"] = "semantic"
        state = graph_state("查看我的工单进度")
        state.update(classify_intent_node(state))
        state.update(select_retrieval_policy_node(state))
        state.update(support_agent.generate_clarification(state))
        response = finalize_response_node(state)["response"]
        assert_equal(response.intent, "ambiguous", "classifier technical failure intent")
        assert_equal(response.fallbackReason, "MODEL_TIMEOUT", "classifier final fallback")
        assert_equal(response.errorCode, "MODEL_TIMEOUT", "classifier final error")
        assert_equal(response.answer, "语义识别服务暂时不可用，请稍后重试。", "classifier failure answer")
        assert_equal(response.executionRecords, [], "classifier failure tool records")
        router_records = [record for record in response.fallbackRecords if record["stage"] == "intent_router"]
        assert_equal(len(router_records), 1, "classifier fallback telemetry")
        assert_equal(router_records[0]["reason"], "MODEL_TIMEOUT", "classifier fallback reason")
        assert_equal(router_records[0]["detail"]["component"], "intent_router", "classifier safe detail")
    finally:
        intent_router.invoke_semantic_model = original_invoker
        support_agent.call_business_tool = original_tool
        if old_mode is None:
            os.environ.pop("AI_INTENT_ROUTER_MODE", None)
        else:
            os.environ["AI_INTENT_ROUTER_MODE"] = old_mode


def test_comment_aggregate_business_response_uses_live_comment_counts():
    state = graph_state("哪个网点有留言")
    state["intent"] = "service_point_comment_ranking"
    state["business_tool_results"] = [{
        "toolName": "query_service_points",
        "success": True,
        "data": [
            {"id": 1, "name": "维修中心", "commentCount": 2},
            {"id": 2, "name": "打印点", "commentCount": 0},
            {"id": 3, "name": "快递站", "commentCount": 5},
        ],
        "count": 3,
    }]
    answer = support_agent.build_business_tool_response(state)
    assert_true("有留言的网点共 2 个" in answer, "commented station count")
    assert_true("快递站 5 条" in answer, "comment ranking top item")
    assert_true("维修中心 2 条" in answer, "comment ranking second item")
    assert_true("打印点" not in answer, "zero-comment station excluded")


def test_printing_hybrid_filters_unrelated_service_points_and_leads_with_operation():
    state = graph_state("我想要打印东西应该怎么操作")
    state.update(
        intent="printing",
        retrieval_mode="HYBRID",
        retrieved_context="打印扣费成功但未出纸时，请保留订单号。",
        knowledge_sources=[KnowledgeSource(type="knowledge", knowledgeId=3, title="打印退款", snippet="打印异常处理")],
    )
    result = support_agent.filter_business_tool_result(state, {
        "toolName": "query_service_points",
        "success": True,
        "data": [
            {"id": 1, "name": "图书馆电子阅览室", "categoryName": "打印服务", "description": "提供自助打印", "address": "图书馆二楼"},
            {"id": 2, "name": "宿舍网络服务点", "categoryName": "网络服务", "description": "处理网络故障"},
            {"id": 3, "name": "快递站", "categoryName": "快递取件", "description": "处理包裹"},
            {"id": 4, "name": "食堂充值点", "categoryName": "校园卡服务", "description": "提供消费明细打印"},
        ],
        "count": 3,
    })
    state["business_tool_results"] = [result]
    business_answer = support_agent.build_business_tool_response(state)
    answer = support_agent.build_hybrid_response(state, business_answer)
    response = support_agent.build_structured_response(dict(state, response=answer))

    assert_true(answer.startswith("打印操作："), "printing answer starts with operation")
    assert_true("上传" not in business_answer and "支付" not in business_answer and "取纸" not in business_answer, "printing answer has no inferred device steps")
    assert_true("图书馆电子阅览室" in answer, "printing point retained")
    assert_true("宿舍网络服务点" not in answer and "快递站" not in answer and "食堂充值点" not in answer, "unrelated points removed")
    assert_equal(result["count"], 1, "filtered tool count")
    assert_equal([card["name"] for card in response.businessCards], ["图书馆电子阅览室"], "filtered cards")
    assert_equal([source.name for source in response.sources if source.type == "service_point"], ["图书馆电子阅览室"], "filtered sources")


def test_routing_metrics_and_trace_are_bounded():
    spans = []

    class RecordingSpan:
        def __init__(self, name, attributes):
            self.name = name
            self.attributes = dict(attributes)

        def set_attribute(self, key, value):
            self.attributes[key] = value

        def record_exception(self, exc):
            self.attributes["exception.type"] = type(exc).__name__

    @contextmanager
    def recording_span(name, trace_parent=None, **attributes):
        span = RecordingSpan(name, attributes)
        spans.append(span)
        yield span

    original_span = patch_attr(support_agent, "ai_span", recording_span)
    old_mode = os.environ.get("AI_INTENT_ROUTER_MODE")
    try:
        os.environ["AI_INTENT_ROUTER_MODE"] = "keyword"
        state = graph_state("图书馆附近打印服务点 trace-private-question")
        state.update(classify_intent_node(state))
        state.update(select_retrieval_policy_node(state))
    finally:
        support_agent.ai_span = original_span
        if old_mode is None:
            os.environ.pop("AI_INTENT_ROUTER_MODE", None)
        else:
            os.environ["AI_INTENT_ROUTER_MODE"] = old_mode

    router_span = next(span for span in spans if span.name == "qilu.ai.agent.intent_router")
    policy_span = next(span for span in spans if span.name == "qilu.ai.agent.retrieval_policy")
    assert_equal(router_span.attributes["ai.intent.router_mode"], "keyword", "router trace mode")
    assert_equal(router_span.attributes["ai.intent.source"], "rule_fallback", "router trace source")
    assert_true(router_span.attributes["ai.intent.confidence_bucket"] in {"high", "accepted", "low"}, "trace confidence bucket")
    assert_equal(policy_span.attributes["ai.retrieval.mode"], "HYBRID", "policy trace retrieval mode")
    assert_equal(policy_span.attributes["ai.routing.reason"], "intent_policy:rule_fallback", "policy trace reason")
    assert_true("trace-private-question" not in str([(span.name, span.attributes) for span in spans]), "question leaked to trace")

    prometheus = metrics.prometheus()
    assert_true('intent_classification_total{mode="keyword",source="rule_fallback"' in prometheus, "intent classification metric")
    assert_true('retrieval_route_total{mode="HYBRID",reason="intent_policy:rule_fallback",low_confidence="false"}' in prometheus, "retrieval route metric")
    snapshot = metrics.snapshot()
    assert_true("intent_classification" in snapshot["operations"], "classification latency metric")
    assert_true("retrieval_policy" in snapshot["operations"], "routing latency metric")


def test_graph_and_chat_orchestrator_modes():
    original_retrieve = patch_attr(
        support_agent,
        "retrieve_context",
        lambda state: {
            "retrieved_context": "",
            "knowledge_sources": [],
            "knowledge_initialized": True,
        },
    )
    original_tool = patch_attr(
        support_agent,
        "call_business_tool",
        lambda state, tool_name, arguments: {
            "toolName": tool_name,
            "success": True,
            "data": [{"id": 1, "name": "Dorm repair center", "address": "Building 1"}],
            "message": None,
            "count": 1,
            "latencyMs": 1.0,
            "toolProtocol": "http_internal",
        },
    )
    old_mode = os.environ.get("AGENT_ORCHESTRATOR")
    try:
        build_langgraph_flow()
        os.environ["AGENT_ORCHESTRATOR"] = "langgraph"
        langgraph_response = run_langgraph_agent(request())
        assert_equal(langgraph_response.traceId, "lg-test", "langgraph trace")
        assert_equal(langgraph_response.orchestrator, "langgraph", "langgraph response orchestrator")
        assert_true(len(langgraph_response.langGraphNodes) >= 5, "langgraph node records")
        assert_true(langgraph_response.answer, "langgraph answer")

        from app import main as app_main

        endpoint_response = app_main.chat(request())
        assert_equal(endpoint_response.traceId, "lg-test", "endpoint langgraph trace")
        os.environ["AGENT_ORCHESTRATOR"] = "legacy"
        legacy_response = app_main.chat(request())
        assert_equal(legacy_response.traceId, "lg-test", "endpoint legacy trace")
        assert_equal(legacy_response.orchestrator, "legacy", "legacy response orchestrator")
        assert_equal(legacy_response.langGraphNodes, [], "legacy node records")
        snapshot = metrics.snapshot()
        assert_true(snapshot["orchestrators"]["langgraph"]["total"] >= 1, "langgraph orchestrator metric")
        assert_true(snapshot["orchestrators"]["legacy"]["total"] >= 1, "legacy orchestrator metric")
        prometheus = metrics.prometheus()
        assert_true('agent_orchestrator_total{mode="langgraph"}' in prometheus, "prometheus langgraph orchestrator")
        assert_true('agent_orchestrator_total{mode="legacy"}' in prometheus, "prometheus legacy orchestrator")
        assert_true('langgraph_node_total{node="retrieve"}' in prometheus, "prometheus retrieve node")
        assert_true('langgraph_node_latency_ms{node="execute_tools"}' in prometheus, "prometheus execute latency")
    finally:
        support_agent.retrieve_context = original_retrieve
        support_agent.call_business_tool = original_tool
        if old_mode is None:
            os.environ.pop("AGENT_ORCHESTRATOR", None)
        else:
            os.environ["AGENT_ORCHESTRATOR"] = old_mode


def test_default_orchestrator_is_langgraph():
    old_mode = os.environ.get("AGENT_ORCHESTRATOR")
    try:
        os.environ.pop("AGENT_ORCHESTRATOR", None)
        assert_equal(support_agent.agent_orchestrator_mode(), "langgraph", "default orchestrator")
    finally:
        if old_mode is not None:
            os.environ["AGENT_ORCHESTRATOR"] = old_mode


def test_anonymous_request_graph_does_not_bind_checkpointer():
    graph = build_langgraph_flow((), False)
    assert_equal(graph.checkpointer, None, "anonymous graph checkpointer")


def main() -> int:
    tests = [
        test_empty_state_initialization,
        test_request_trace_injection,
        test_finalize_converts_response_fields,
        test_permission_denied_clears_sensitive_fields,
        test_role_permission_is_denied_before_planner_and_tools,
        test_rag_hit_node,
        test_no_source_and_knowledge_fallback_nodes,
        test_tool_success_permission_and_timeout_nodes,
        test_memory_followup_plans_tool_from_context,
        test_chinese_appointment_record_question_plans_business_tool,
        test_semantic_routing_target_graph_topology,
        test_langgraph_and_legacy_semantic_route_matrix,
        test_memory_v2_resolution_is_identical_across_orchestrators,
        test_casual_chat_calls_answer_model_without_rag_or_business_tools,
        test_langgraph_and_legacy_all_intent_policy_parity,
        test_retrieval_mode_strictly_filters_sources_and_cards,
        test_hybrid_answer_model_receives_knowledge_and_read_only_business_context,
        test_classifier_failure_is_explicit_and_does_not_execute_business_tools,
        test_comment_aggregate_business_response_uses_live_comment_counts,
        test_printing_hybrid_filters_unrelated_service_points_and_leads_with_operation,
        test_routing_metrics_and_trace_are_bounded,
        test_graph_and_chat_orchestrator_modes,
        test_default_orchestrator_is_langgraph,
        test_anonymous_request_graph_does_not_bind_checkpointer,
    ]
    for test in tests:
        test()
    print("langgraph flow tests passed: %d" % len(tests))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
