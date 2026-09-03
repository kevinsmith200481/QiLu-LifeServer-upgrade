from __future__ import annotations

import logging
from functools import lru_cache

try:
    from langgraph.graph import END, START, StateGraph
except ImportError:  # Allows syntax checks before optional dependencies are installed.
    END = "__end__"
    START = "__start__"
    StateGraph = None

from agent.langgraph_nodes import (
    check_escalation_node,
    classify_intent_node,
    execute_tools_node,
    fallback_node,
    finalize_response_node,
    generate_clarification_node,
    generate_node,
    load_memory_node,
    plan_tools_node,
    retrieve_node,
    route_query_node,
    select_retrieval_policy_node,
)
from agent.langgraph_state import CampusGraphState, initial_graph_state
from agent.checkpoint_runtime import get_checkpoint_runtime
from app.acceptance_faults import consume_checkpoint_interrupt_after_tools
from app.schemas import CampusAssistantRequest, CampusAssistantResponse

logger = logging.getLogger(__name__)


@lru_cache(maxsize=8)
def build_langgraph_flow(
    interrupt_after: tuple[str, ...] = (),
    checkpoint_enabled: bool | None = None,
):
    if StateGraph is None:
        raise RuntimeError("langgraph is not installed")
    graph = StateGraph(CampusGraphState)
    graph.add_node("load_memory", load_memory_node)
    graph.add_node("classify_intent", classify_intent_node)
    graph.add_node("select_retrieval_policy", select_retrieval_policy_node)
    graph.add_node("route_query", route_query_node)
    graph.add_node("retrieve", retrieve_node)
    graph.add_node("check_escalation", check_escalation_node)
    graph.add_node("plan_tools", plan_tools_node)
    graph.add_node("execute_tools", execute_tools_node)
    graph.add_node("generate", generate_node)
    graph.add_node("generate_clarification", generate_clarification_node)
    graph.add_node("fallback", fallback_node)
    graph.add_node("finalize_response", finalize_response_node)

    graph.add_edge(START, "load_memory")
    graph.add_edge("load_memory", "classify_intent")
    graph.add_conditional_edges("classify_intent", _route_after_node, {"continue": "select_retrieval_policy", "fallback": "fallback"})
    graph.add_conditional_edges("select_retrieval_policy", _route_after_node, {"continue": "route_query", "fallback": "fallback"})
    graph.add_conditional_edges(
        "route_query",
        _route_query_branch,
        {
            "direct": "generate",
            "business": "check_escalation",
            "rag": "retrieve",
            "hybrid": "retrieve",
            "clarify": "generate_clarification",
            "fallback": "fallback",
        },
    )
    graph.add_conditional_edges("retrieve", _route_after_node, {"continue": "check_escalation", "fallback": "fallback"})
    graph.add_edge("check_escalation", "plan_tools")
    graph.add_edge("plan_tools", "execute_tools")
    graph.add_conditional_edges("execute_tools", _route_after_node, {"continue": "generate", "fallback": "fallback"})
    graph.add_conditional_edges(
        "generate",
        _route_after_generate,
        {"continue": "finalize_response", "repeat_tools": "execute_tools", "fallback": "fallback"},
    )
    graph.add_edge("fallback", "finalize_response")
    graph.add_edge("generate_clarification", "finalize_response")
    graph.add_edge("finalize_response", END)
    runtime = get_checkpoint_runtime()
    use_checkpointer = runtime.settings.enabled if checkpoint_enabled is None else checkpoint_enabled
    return graph.compile(
        checkpointer=runtime.saver if use_checkpointer else None,
        interrupt_after=list(interrupt_after),
    )


def run_langgraph_agent(request: CampusAssistantRequest) -> CampusAssistantResponse:
    runtime = get_checkpoint_runtime()
    if not runtime.settings.enabled or request.userId is None or not request.conversationId:
        # 未绑定用户或会话的请求没有稳定 thread_id，必须使用无 Checkpointer 的图执行。
        # 即使全局已启用 Checkpointer，也不能把空配置交给 LangGraph，否则会在运行前拒绝请求。
        return _invoke_graph(request, None, checkpoint_enabled=False)
    thread_id = runtime.thread_id(request.userId, request.conversationId)
    with runtime.acquire_thread(thread_id):
        recovered = runtime.prepare_thread(request.userId, request.conversationId)
        if recovered and request.memory is None and not request.history and not request.lastBusinessContext:
            # MySQL history remains authoritative. Checkpoint context is used only
            # when the Provider deliberately sends no Memory or business history.
            request.lastBusinessContext = recovered
        config = {"configurable": {"thread_id": thread_id, "checkpoint_ns": ""}}
        graph = build_langgraph_flow()
        snapshot = graph.get_state(config)
        resumed_from_interrupt = bool(snapshot.next)
        if resumed_from_interrupt:
            # A durable interrupt must resume from its saved next node. Passing
            # a new input here would start at START and could execute a tool a
            # second time, so recovery intentionally invokes the graph with None.
            response = _invoke_graph(request, config, resume=True)
        else:
            interrupt_after = ("execute_tools",) if consume_checkpoint_interrupt_after_tools() else ()
            response = _invoke_graph(request, config, interrupt_after=interrupt_after)
        runtime.touch_thread(request.userId, request.conversationId)
        response.checkpoint = {
            "enabled": True,
            "backend": "sqlite",
            "schemaVersion": runtime.settings.schema_version,
            "recovered": bool(recovered) or resumed_from_interrupt,
            "resumedFromInterrupt": resumed_from_interrupt,
        }
        return response


def _invoke_graph(
    request: CampusAssistantRequest,
    config,
    interrupt_after: tuple[str, ...] = (),
    resume: bool = False,
    checkpoint_enabled: bool | None = None,
) -> CampusAssistantResponse:
    state = initial_graph_state(request)
    logger.info("langgraph flow start traceId=%s", request.traceId)
    graph = build_langgraph_flow(interrupt_after, checkpoint_enabled)
    final_state = graph.invoke(None if resume else state, config)
    if config and graph.get_state(config).next:
        # The HTTP request fails at this controlled boundary, leaving the
        # checkpoint available for the next request/process to resume.
        raise RuntimeError("ACCEPTANCE_CHECKPOINT_INTERRUPTED_AFTER_TOOLS")
    response = final_state.get("response")
    if isinstance(response, CampusAssistantResponse):
        logger.info(
            "langgraph flow finished traceId=%s intent=%s fallbackReason=%s",
            response.traceId,
            response.intent,
            response.fallbackReason,
        )
        return response
    update = finalize_response_node(final_state)
    response = update.get("response")
    if isinstance(response, CampusAssistantResponse):
        return response
    raise RuntimeError("LangGraph flow did not produce CampusAssistantResponse")


def _route_after_node(state: CampusGraphState) -> str:
    return "fallback" if state.get("errors") else "continue"


def _route_after_generate(state: CampusGraphState) -> str:
    if state.get("errors"):
        return "fallback"
    if state.get("native_pending_generation") and state.get("planned_tools"):
        return "repeat_tools"
    return "continue"


def _route_query_branch(state: CampusGraphState) -> str:
    if state.get("errors"):
        return "fallback"
    retrieval_mode = str(state.get("retrieval_mode") or "")
    return {
        "DIRECT_LLM": "direct",
        "BUSINESS_ONLY": "business",
        "RAG_ONLY": "rag",
        "HYBRID": "hybrid",
        "CLARIFY": "clarify",
    }.get(retrieval_mode, "fallback")
