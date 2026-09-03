from __future__ import annotations

from typing import Any, Dict, List, Optional, TypedDict

from app.schemas import CampusAssistantRequest, CampusAssistantResponse


FALLBACK_REASONS = {
    "NO_SOURCE",
    "KNOWLEDGE_NOT_SYNCED",
    "PERMISSION_DENIED",
    "TOOL_TIMEOUT",
    "TOOL_UNAVAILABLE",
}


class CampusGraphState(TypedDict, total=False):
    request: Optional[CampusAssistantRequest]
    trace_id: Optional[str]
    traceparent: Optional[str]
    orchestrator: str
    memory_summary: Dict[str, Any]
    intent_classification: Dict[str, Any]
    intent_decision: Dict[str, Any]
    intent_entities: Dict[str, Optional[int]]
    intent_source: str
    intent_confidence: float
    intent_router_mode: str
    classifier_fallback_reason: Optional[str]
    memory_resolution_source: Optional[str]
    memory_shadow_comparison: Dict[str, Any]
    retrieval_mode: str
    routing_reason: str
    low_confidence: bool
    permission_denied: bool
    retrieved_context: str
    knowledge_sources: List[Any]
    intent: str
    confidence: float
    need_create_ticket: bool
    planned_tools: List[Dict[str, Any]]
    planner_mode: str
    planner_fallback_reason: Optional[str]
    model_name: Optional[str]
    finish_reason: Optional[str]
    schema_validation: str
    native_round: int
    native_messages: List[Any]
    native_bound_model: Any
    native_seen_calls: List[str]
    native_result_cache: Dict[str, Dict[str, Any]]
    native_pending_generation: bool
    current_round_calls: List[Dict[str, Any]]
    current_round_results: List[Dict[str, Any]]
    tool_results: List[Dict[str, Any]]
    business_cards: List[Dict[str, Any]]
    action_drafts: List[Dict[str, Any]]
    fallback_reason: Optional[str]
    execution_records: List[Dict[str, Any]]
    lang_graph_nodes: List[Dict[str, Any]]
    response: Any
    errors: List[Dict[str, Any]]
    request_id: Optional[str]
    tool_execution_keys: List[str]

    # Compatibility keys consumed by the existing campus support functions.
    messages: List[Any]
    user_input: str
    trace_parent: Optional[str]
    escalate: bool
    knowledge_initialized: bool
    service_points: List[Any]
    tickets: List[Any]
    appointments: List[Any]
    recommended_service_points: List[Any]
    user_id: Optional[int]
    role: Optional[str]
    business_tool_results: List[Dict[str, Any]]
    memory_context: Dict[str, Any]
    agent_plan: Dict[str, Any]
    generation_record: Dict[str, Any]
    fallback_records: List[Dict[str, Any]]


def empty_graph_state() -> CampusGraphState:
    return CampusGraphState(
        request=None,
        trace_id=None,
        traceparent=None,
        orchestrator="langgraph",
        memory_summary={},
        intent_classification={},
        intent_decision={},
        intent_entities={
            "appointmentId": None,
            "ticketId": None,
            "servicePointId": None,
        },
        intent_source="rule_fallback",
        intent_confidence=0.0,
        intent_router_mode="keyword",
        classifier_fallback_reason=None,
        memory_resolution_source=None,
        memory_shadow_comparison={},
        retrieval_mode="",
        routing_reason="",
        low_confidence=False,
        permission_denied=False,
        retrieved_context="",
        knowledge_sources=[],
        intent="general",
        confidence=0.0,
        need_create_ticket=False,
        planned_tools=[],
        planner_mode="rule",
        planner_fallback_reason=None,
        model_name=None,
        finish_reason=None,
        schema_validation="not_applicable",
        native_round=0,
        native_messages=[],
        native_bound_model=None,
        native_seen_calls=[],
        native_result_cache={},
        native_pending_generation=False,
        current_round_calls=[],
        current_round_results=[],
        tool_results=[],
        business_cards=[],
        action_drafts=[],
        fallback_reason=None,
        execution_records=[],
        lang_graph_nodes=[],
        response=None,
        errors=[],
        request_id=None,
        tool_execution_keys=[],
        messages=[],
        user_input="",
        trace_parent=None,
        escalate=False,
        knowledge_initialized=True,
        service_points=[],
        tickets=[],
        appointments=[],
        recommended_service_points=[],
        user_id=None,
        role=None,
        business_tool_results=[],
        memory_context={},
        agent_plan={},
        generation_record={},
        fallback_records=[],
    )


def initial_graph_state(request: CampusAssistantRequest) -> CampusGraphState:
    state = empty_graph_state()
    state.update(
        request=request,
        trace_id=request.traceId,
        traceparent=request.traceParent,
        trace_parent=request.traceParent,
        request_id=request.traceId,
        user_input=request.question,
        user_id=request.userId,
        role=request.role,
    )
    return state


def response_to_state_fields(response: CampusAssistantResponse) -> Dict[str, Any]:
    return {
        "confidence": response.confidence,
        "intent_source": response.intentSource or "",
        "retrieval_mode": response.retrievalMode or "",
        "routing_reason": response.routingReason or "",
        "low_confidence": bool(response.lowConfidence),
        "need_create_ticket": response.needCreateTicket,
        "business_cards": list(response.businessCards or []),
        "action_drafts": list(response.actionDrafts or []),
        "fallback_reason": response.fallbackReason,
    }


def append_error(state: CampusGraphState, node_name: str, exc: BaseException) -> List[Dict[str, Any]]:
    errors = list(state.get("errors") or [])
    errors.append(
        {
            "nodeName": node_name,
            "errorType": type(exc).__name__,
            "message": str(exc),
        }
    )
    return errors
