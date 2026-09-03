"""
Campus service support agent adapted from the Customer Support Agent prototype.

Original prototype: agents/13-customer-support-agent in 500-AI-Agents-Projects.
Adaptation target: Qilu campus service assistant with RAG, escalation routing,
conversation state, and structured HTTP response for the Java AI gateway.
"""

from __future__ import annotations

import os
import socket
import json
import logging
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Annotated, Any, Dict, List, Optional, Tuple, TypedDict

try:
    if os.getenv("AI_LIGHTWEIGHT_RUNTIME", "").strip().lower() in {"1", "true", "yes", "on"}:
        raise ImportError("optional AI runtime disabled for constrained acceptance")
    from dotenv import load_dotenv
    from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    from langgraph.graph import END, StateGraph
    from langgraph.graph.message import add_messages
except ImportError:  # Allows syntax checks before Python dependencies are installed.
    load_dotenv = None
    AIMessage = None
    HumanMessage = None
    SystemMessage = None
    ChatOpenAI = None
    OpenAIEmbeddings = None
    END = None
    StateGraph = None

    def add_messages(left, right):
        return (left or []) + (right or [])

from app.schemas import CampusAppointment, CampusAssistantRequest, CampusAssistantResponse, CampusMemoryDiagnostics, CampusServicePoint, CampusTicket, KnowledgeReloadItem, KnowledgeSource
from app.metrics import elapsed_ms, metrics, now
from app.acceptance_faults import delay_tool_if_configured, force_model_timeout, force_model_unavailable
from app.failures import failure_contract
from app.tracing import ai_span, inject_traceparent, record_exception
from agent.execution import FallbackRecord, ToolExecutionRecord, build_agent_plan
from agent.intent_router import (
    IntentClassification,
    RetrievalMode,
    classify_intent as classify_semantic_intent,
    confidence_bucket,
    router_mode,
    select_retrieval_policy,
)
from agent.memory import build_request_memory_context
from agent.tools.registry import plan_tool_calls, tools_for_intent
from rag.retriever import (
    CampusKnowledgeRetriever,
    KnowledgeDocument,
    KnowledgeHit,
    KnowledgeReloadResult,
    filter_usable_hits,
    vector_dependencies_enabled,
)

if load_dotenv and os.getenv("AI_SKIP_DOTENV", "").strip().lower() not in {"1", "true", "yes", "on"}:
    load_dotenv()

logger = logging.getLogger(__name__)
logger.setLevel(os.getenv("AI_LOG_LEVEL", "INFO").upper())
if not logger.handlers:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    logger.addHandler(handler)

SAMPLE_KB = [
    "Campus card: If a campus card is lost, report the loss at the campus card service center and bring student ID for replacement.",
    "Dorm repair: For water, electricity, door, window, or network repair, create a service ticket with building, room number, issue description, and available visit time.",
    "Printing: Library printing points support self-service printing and binding. If campus card payment fails, contact the printing point staff.",
    "Express pickup: Campus express station handles parcel pickup and exception handling. Bring pickup code and valid identity proof.",
    "Career consultation: Career consultation office provides resume review, interview preparation, and employment policy support.",
]

KNOWLEDGE_UNINITIALIZED_MESSAGE = "\u667a\u80fd\u77e5\u8bc6\u5e93\u5c1a\u672a\u540c\u6b65\uff0c\u8bf7\u5148\u5728\u7ba1\u7406\u540e\u53f0\u540c\u6b65\u77e5\u8bc6\u5e93\u3002"
DIRECT_LLM_UNAVAILABLE_MESSAGE = "AI 回答服务暂时不可用，请稍后重试。"

ESCALATION_KEYWORDS = [
    "urgent",
    "danger",
    "unsafe",
    "fraud",
    "complaint",
    "data loss",
    "broken",
    "leak",
    "emergency",
]

TICKET_CATEGORIES = {"repair", "printing", "express", "consultation", "general"}

INTENT_TOPIC_KEYWORDS: Dict[str, Tuple[str, ...]] = {
    "repair": ("repair", "fix", "broken", "leak", "维修", "报修", "漏水", "水电", "故障"),
    "printing": ("print", "printer", "printing", "copy", "打印", "复印", "扫描", "电子阅览"),
    "express": ("express", "parcel", "package", "pickup", "快递", "包裹", "取件", "退件"),
    "consultation": ("career", "resume", "job", "interview", "就业", "求职", "简历", "面试", "咨询"),
}


def llm_enabled() -> bool:
    if os.getenv("AI_LLM_ENABLED", "").strip().lower() in {"0", "false", "no", "off"}:
        return False
    # Acceptance model faults do not need to import the real model SDK.
    if force_model_timeout() or force_model_unavailable():
        return bool(os.getenv("OPENAI_API_KEY"))
    return bool(ChatOpenAI and HumanMessage and SystemMessage and os.getenv("OPENAI_API_KEY"))


def vector_rag_enabled() -> bool:
    return vector_dependencies_enabled()


def runtime_status() -> Dict[str, object]:
    from agent.native_function_calling import planner_mode

    orchestrator = agent_orchestrator_mode()
    return {
        "llmEnabled": llm_enabled(),
        "vectorRagEnabled": vector_rag_enabled(),
        "mode": "llm" if llm_enabled() else "local-fallback",
        "model": os.getenv("AI_MODEL", "gpt-4o-mini") if llm_enabled() else None,
        "baseUrlConfigured": bool(openai_base_url()),
        "orchestrator": orchestrator,
        "plannerMode": planner_mode() if orchestrator == "langgraph" else "rule",
    }


def openai_base_url() -> Optional[str]:
    return os.getenv("OPENAI_BASE_URL") or os.getenv("OPENAI_API_BASE")


def agent_orchestrator_mode() -> str:
    value = os.getenv("AGENT_ORCHESTRATOR", "langgraph").strip().lower()
    return "langgraph" if value == "langgraph" else "legacy"


@dataclass(frozen=True)
class KnowledgePolicy:
    mode: str
    require_ai_knowledge_sync: bool
    allow_sample_kb: bool


def knowledge_policy() -> KnowledgePolicy:
    configured_mode = os.getenv("CAMPUS_KB_MODE", "production").strip().lower()
    mode = "demo" if configured_mode in {"demo", "sample"} else "production"
    require_sync = os.getenv("CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC", "").strip().lower() in {"1", "true", "yes", "on"}
    sample_requested = os.getenv("CAMPUS_ALLOW_SAMPLE_KB", "").strip().lower() in {"1", "true", "yes", "on"}
    if mode == "production":
        return KnowledgePolicy(mode=mode, require_ai_knowledge_sync=True, allow_sample_kb=False)
    return KnowledgePolicy(
        mode=mode,
        require_ai_knowledge_sync=require_sync,
        allow_sample_kb=sample_requested and not require_sync,
    )


def sample_kb_allowed() -> bool:
    return knowledge_policy().allow_sample_kb


def knowledge_sync_required() -> bool:
    return knowledge_policy().require_ai_knowledge_sync


class CampusSupportState(TypedDict):
    messages: Annotated[list, add_messages]
    user_input: str
    retrieved_context: str
    knowledge_sources: List[KnowledgeSource]
    response: str
    intent: str
    intent_classification: Dict[str, object]
    intent_decision: Dict[str, object]
    intent_entities: Dict[str, Optional[int]]
    intent_source: str
    intent_confidence: float
    intent_router_mode: str
    classifier_fallback_reason: Optional[str]
    memory_resolution_source: Optional[str]
    memory_shadow_comparison: Dict[str, object]
    retrieval_mode: str
    routing_reason: str
    low_confidence: bool
    permission_denied: bool
    escalate: bool
    knowledge_initialized: bool
    service_points: List[CampusServicePoint]
    tickets: List[CampusTicket]
    appointments: List[CampusAppointment]
    recommended_service_points: List[CampusServicePoint]
    user_id: Optional[int]
    role: Optional[str]
    trace_id: Optional[str]
    trace_parent: Optional[str]
    orchestrator: str
    lang_graph_nodes: List[Dict[str, object]]
    business_tool_results: List[Dict[str, object]]
    memory_context: Dict[str, object]
    agent_plan: Dict[str, object]
    execution_records: List[Dict[str, object]]
    generation_record: Dict[str, object]
    fallback_records: List[Dict[str, object]]


def load_kb_texts(kb_dir: Optional[str], allow_sample: bool = False) -> List[str]:
    if not allow_sample:
        return []
    if not kb_dir:
        return SAMPLE_KB
    root = Path(kb_dir)
    try:
        if root.is_symlink() or not root.is_dir():
            return []
        paths = sorted(root.iterdir(), key=lambda path: (path.name.casefold(), path.name))
    except OSError as exc:
        logger.warning("local knowledge directory unavailable errorType=%s", type(exc).__name__)
        return []

    texts = []
    for path in paths:
        try:
            if path.name.startswith(".") or path.is_symlink() or not path.is_file():
                continue
            if path.suffix.lower() not in {".txt", ".md"}:
                continue
            texts.append(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError) as exc:
            logger.warning("local knowledge file rejected errorType=%s", type(exc).__name__)
            return []
    return texts


def retrieve_context(state: CampusSupportState) -> CampusSupportState:
    query = state["user_input"]
    trace_id = state.get("trace_id")
    retrieval_mode = str(state.get("retrieval_mode") or "")
    start = now()
    with ai_span("qilu.ai.agent.rag", ai_trace_id=trace_id, ai_question_length=len(query or "")) as span:
        if retrieval_mode in {RetrievalMode.BUSINESS_ONLY.value, RetrievalMode.CLARIFY.value}:
            existing_retriever = getattr(retrieve_context, "retriever", None)
            initialized = existing_retriever.is_initialized() if existing_retriever is not None else True
            if span is not None:
                span.set_attribute("ai.rag.hit_count", 0)
                span.set_attribute("ai.knowledge_initialized", initialized)
            logger.info(
                "rag retrieve skipped traceId=%s reason=retrieval_policy mode=%s elapsedMs=%.2f",
                trace_id,
                retrieval_mode,
                elapsed_ms(start),
            )
            return {
                "retrieved_context": "",
                "knowledge_sources": [],
                "knowledge_initialized": initialized,
            }
        retriever = getattr(retrieve_context, "retriever", None)
        if retriever is None:
            policy = knowledge_policy()
            retriever = CampusKnowledgeRetriever(
                SAMPLE_KB if policy.allow_sample_kb else [],
                use_default_knowledge=False,
                knowledge_source="sample-dev" if policy.allow_sample_kb else "uninitialized",
            )
            retrieve_context.retriever = retriever
        if not retrieval_mode and should_skip_knowledge_retrieval(query):
            if span is not None:
                span.set_attribute("ai.rag.hit_count", 0)
                span.set_attribute("ai.knowledge_initialized", retriever.is_initialized())
            logger.info("rag retrieve skipped traceId=%s reason=business_admin_query elapsedMs=%.2f", trace_id, elapsed_ms(start))
            return {
                "retrieved_context": "",
                "knowledge_sources": [],
                "knowledge_initialized": retriever.is_initialized(),
            }
        intent = str(state.get("intent") or "")
        hits = retriever.retrieve_documents(
            query,
            limit=rag_top_k(),
            topic_keywords=INTENT_TOPIC_KEYWORDS.get(intent, ()),
            trace_span=span,
        )
        if span is not None:
            span.set_attribute("ai.rag.hit_count", len(hits))
            span.set_attribute("ai.knowledge_initialized", retriever.is_initialized())
        logger.info("rag retrieve finished traceId=%s hitCount=%s elapsedMs=%.2f", trace_id, len(hits), elapsed_ms(start))
        return {
            "retrieved_context": "\n".join(hit.content for hit in hits),
            "knowledge_sources": knowledge_hits_to_sources(hits),
            "knowledge_initialized": retriever.is_initialized(),
        }


def should_skip_knowledge_retrieval(query: str) -> bool:
    text = (query or "").lower()
    return any(word in text for word in [
        "my appointment", "appointment status", "appointment record", "reservation",
        "my ticket", "ticket status", "ticket progress", "inbox", "notification", "unread",
        "operation log", "admin log",
        "\u9884\u7ea6", "\u9884\u7ea6\u8bb0\u5f55", "\u5de5\u5355", "\u8fdb\u5ea6",
        "\u6536\u4ef6\u7bb1", "\u901a\u77e5", "\u672a\u8bfb", "\u64cd\u4f5c\u65e5\u5fd7",
        "\u9884\u7ea6\u5931\u8d25", "\u5931\u8d25\u539f\u56e0",
    ])


def rag_top_k() -> int:
    try:
        configured = int(os.getenv("RAG_TOP_K", "3"))
    except ValueError:
        return 3
    return max(1, min(configured, 10))


def check_escalation(state: CampusSupportState) -> CampusSupportState:
    text = state["user_input"].lower()
    return {"escalate": any(keyword in text for keyword in ESCALATION_KEYWORDS)}


def detect_intent(state: CampusSupportState) -> CampusSupportState:
    text = state["user_input"].lower()
    memory_context = state.get("memory_context", {})
    has_memory_ticket = bool(isinstance(memory_context, dict) and memory_context.get("businessContext", {}).get("lastTicket"))
    has_memory_appointment = bool(isinstance(memory_context, dict) and memory_context.get("businessContext", {}).get("lastAppointment"))
    if has_memory_ticket and any(word in text for word in ["that ticket", "last ticket", "\u521a\u624d\u90a3\u4e2a\u5de5\u5355", "\u90a3\u4e2a\u5de5\u5355", "\u8865\u5145"]):
        intent = "ticket_status"
    elif has_memory_appointment and any(word in text for word in ["that appointment", "last appointment", "\u7ee7\u7eed\u67e5\u9884\u7ea6", "\u90a3\u4e2a\u9884\u7ea6"]):
        intent = "appointment_status"
    elif any(word in text for word in ["operation log", "admin log", "\u64cd\u4f5c\u65e5\u5fd7"]):
        intent = "admin_operation_logs"
    elif any(word in text for word in ["failure log", "appointment failure", "\u9884\u7ea6\u5931\u8d25", "\u5931\u8d25\u539f\u56e0"]):
        intent = "admin_appointment_failure_logs"
    elif any(word in text for word in ["inbox", "notification", "unread", "\u6536\u4ef6\u7bb1", "\u901a\u77e5", "\u672a\u8bfb"]):
        intent = "inbox_summary"
    elif any(word in text for word in ["slot", "quota", "capacity", "\u65f6\u6bb5", "\u4f59\u91cf", "\u5bb9\u91cf"]):
        intent = "service_point_slots"
    elif any(word in text for word in ["category", "categories", "\u5206\u7c7b", "\u7c7b\u522b"]):
        intent = "service_categories"
    elif (
        not extract_first_number(text)
        and any(word in text for word in ["comment", "\u7559\u8a00", "\u8bc4\u8bba"])
        and (
            any(word in text for word in ["most comments", "comment count", "comment ranking", "\u7559\u8a00\u6700\u591a", "\u8bc4\u8bba\u6700\u591a", "\u7559\u8a00\u6570", "\u8bc4\u8bba\u6570", "\u7559\u8a00\u6392\u884c", "\u8bc4\u8bba\u6392\u884c"])
            or (
                any(word in text for word in ["station", "service point", "\u7f51\u70b9", "\u670d\u52a1\u70b9"])
                and any(word in text for word in ["has comments", "have comments", "\u6709\u7559\u8a00", "\u6709\u8bc4\u8bba"])
            )
        )
    ):
        intent = "service_point_comment_ranking"
    elif any(word in text for word in ["comment", "\u7559\u8a00", "\u8bc4\u8bba"]):
        intent = "station_comments"
    elif any(word in text for word in ["repair", "fix", "broken", "leak", "dorm", "complaint", "\u7ef4\u4fee", "\u62a5\u4fee", "\u5bbf\u820d", "\u6f0f\u6c34", "\u6545\u969c", "\u6295\u8bc9"]):
        intent = "repair"
    elif any(word in text for word in ["print", "printer", "copy", "\u6253\u5370", "\u590d\u5370", "\u6253\u5370\u673a", "\u8bfb\u5361"]):
        intent = "printing"
    elif any(word in text for word in ["express", "parcel", "package", "\u5feb\u9012", "\u5305\u88f9", "\u53d6\u4ef6"]):
        intent = "express"
    elif any(word in text for word in ["career", "resume", "job", "interview", "\u5c31\u4e1a", "\u7b80\u5386", "\u9762\u8bd5", "\u54a8\u8be2"]):
        intent = "consultation"
    elif any(word in text for word in ["appointment", "reservation", "\u9884\u7ea6", "\u9884\u7ea6\u5355"]):
        intent = "appointment_status"
    elif any(word in text for word in ["ticket", "status", "progress", "\u5de5\u5355", "\u8fdb\u5ea6", "\u72b6\u6001"]):
        intent = "ticket_status"
    else:
        intent = "general"
    return {"intent": intent, "recommended_service_points": recommend_points(intent, state.get("service_points", []))}


def classify_query_intent(state: CampusSupportState) -> CampusSupportState:
    start = now()
    request = state.get("request")
    scene = getattr(request, "scene", None) if request is not None else None
    with ai_span(
        "qilu.ai.agent.intent_router",
        ai_trace_id=state.get("trace_id"),
        ai_intent_router_mode=router_mode(),
    ) as span:
        classification = classify_semantic_intent(
            question=state.get("user_input", ""),
            memory_summary=state.get("memory_context", {}),
            role=state.get("role"),
            scene=scene,
        )
        shadow_comparison = compare_shadow_memory(state, classification, scene)
        decision = classification.decision
        bucket = confidence_bucket(decision.confidence)
        if span is not None:
            span.set_attribute("ai.intent.router_mode", classification.mode)
            # 模型名属于非敏感运行身份，用于证明语义分类确实走真实兼容模型；端点和密钥永不写入 Trace。
            if classification.mode in {"semantic", "semantic_shadow"}:
                span.set_attribute("ai.intent.model", os.getenv("AI_INTENT_MODEL") or os.getenv("AI_MODEL", ""))
            span.set_attribute("ai.intent.name", decision.intent.value)
            span.set_attribute("ai.intent.source", decision.intentSource.value)
            span.set_attribute("ai.intent.confidence_bucket", bucket)
            span.set_attribute("ai.intent.low_confidence", classification.low_confidence)
            span.set_attribute("ai.intent.fallback_reason", classification.fallback_reason or "")
    metrics.record(
        "intent_classification",
        elapsed_ms(start),
        success=True,
        fallback=bool(classification.fallback_reason),
    )
    metrics.record_intent_classification(
        classification.mode,
        decision.intentSource.value,
        bucket,
        bool(classification.fallback_reason),
    )
    resolution_source = classification.memory_resolution_source or "none"
    entity_type = {
        "ticket_status": "ticket",
        "appointment_status": "appointment",
        "service_point_slots": "service_point",
        "service_point_search": "service_point",
    }.get(decision.intent.value, "none")
    metrics.record_memory_resolution(entity_type, resolution_source)
    if shadow_comparison:
        metrics.record_memory_shadow_comparison(shadow_comparison)
        logger.info(
            "memory shadow comparison traceId=%s entityMatch=%s routeMatch=%s "
            "toolMatch=%s budgetWithinLimit=%s legacyTokens=%s v2Tokens=%s",
            state.get("trace_id"),
            shadow_comparison["entityMatch"],
            shadow_comparison["routeMatch"],
            shadow_comparison["toolMatch"],
            shadow_comparison["budgetWithinLimit"],
            shadow_comparison["legacyEstimatedTokens"],
            shadow_comparison["v2EstimatedTokens"],
        )
    fallback_records = list(state.get("fallback_records", []))
    if classification.fallback_reason:
        fallback_record = FallbackRecord(
            reason=classification.fallback_reason,
            stage="intent_router",
            detail={
                "component": "intent_router",
                "intentSource": decision.intentSource.value,
                "routerMode": classification.mode,
            },
        )
        fallback_records.append(fallback_record.to_dict())
        logger.info(
            "fallback record traceId=%s record=%s",
            state.get("trace_id"),
            json.dumps(fallback_record.to_dict(), ensure_ascii=False),
        )
    return {
        "intent_classification": classification.to_dict(),
        "intent_decision": decision.model_dump(mode="json"),
        "intent_entities": decision.entities.model_dump(mode="json"),
        "intent": decision.intent.value,
        "intent_source": decision.intentSource.value,
        "intent_confidence": decision.confidence,
        "confidence": decision.confidence,
        "intent_router_mode": classification.mode,
        "classifier_fallback_reason": classification.fallback_reason,
        "memory_resolution_source": classification.memory_resolution_source,
        "memory_shadow_comparison": shadow_comparison,
        "low_confidence": classification.low_confidence,
        "fallback_records": fallback_records,
    }


def compare_shadow_memory(
    state: CampusSupportState,
    actual: IntentClassification,
    scene: Optional[str],
) -> Dict[str, object]:
    """在 shadow 模式下只计算 v2 候选，不改变 legacy 的实际路由和工具执行。"""
    memory = state.get("memory_context")
    shadow = memory.get("shadowV2") if isinstance(memory, dict) else None
    if not isinstance(shadow, dict):
        return {}
    shadow_classification = classify_semantic_intent(
        question=state.get("user_input", ""),
        memory_summary=shadow,
        role=state.get("role"),
        scene=scene,
    )
    actual_policy = select_retrieval_policy(actual)
    shadow_policy = select_retrieval_policy(shadow_classification)
    actual_entities = actual.decision.entities.model_dump(mode="json")
    shadow_entities = shadow_classification.decision.entities.model_dump(mode="json")
    actual_calls = plan_tool_calls(
        actual_policy.effective_intent.value,
        state.get("user_input", ""),
        memory.get("businessContext") if isinstance(memory, dict) else {},
        state.get("role"),
        resolved_entities=actual_entities,
    )
    shadow_calls = plan_tool_calls(
        shadow_policy.effective_intent.value,
        state.get("user_input", ""),
        shadow.get("businessContext") if isinstance(shadow, dict) else {},
        state.get("role"),
        resolved_entities=shadow_entities,
    )
    try:
        token_limit = max(1, int(os.getenv("AI_MEMORY_MAX_INPUT_TOKENS", "3000")))
    except ValueError:
        token_limit = 3000
    legacy_tokens = int(memory.get("estimatedTokens") or 0)
    v2_tokens = int(shadow.get("estimatedTokens") or 0)
    return {
        "entityMatch": actual_entities == shadow_entities
        and actual.memory_resolution_source == shadow_classification.memory_resolution_source,
        "routeMatch": actual_policy.retrieval_mode == shadow_policy.retrieval_mode
        and actual_policy.effective_intent == shadow_policy.effective_intent,
        "toolMatch": actual_calls == shadow_calls,
        "budgetWithinLimit": legacy_tokens <= token_limit and v2_tokens <= token_limit,
        "legacyEstimatedTokens": max(0, legacy_tokens),
        "v2EstimatedTokens": max(0, v2_tokens),
    }


def select_query_retrieval_policy(state: CampusSupportState) -> CampusSupportState:
    start = now()
    raw_classification = state.get("intent_classification")
    if not isinstance(raw_classification, dict) or not raw_classification:
        raise ValueError("intent classification is missing")
    classification = IntentClassification.from_dict(raw_classification)
    policy = select_retrieval_policy(classification)
    decision = classification.decision
    intent_source = decision.intentSource.value
    intent_confidence = decision.confidence
    if (
        policy.effective_intent != decision.intent
        and classification.model_decision is not None
        and policy.effective_intent == classification.model_decision.intent
    ):
        intent_source = classification.model_decision.intentSource.value
        intent_confidence = classification.model_decision.confidence
    recommended = (
        recommend_points(policy.effective_intent.value, state.get("service_points", []))
        if policy.retrieval_mode == RetrievalMode.HYBRID
        else []
    )
    with ai_span(
        "qilu.ai.agent.retrieval_policy",
        ai_trace_id=state.get("trace_id"),
        ai_intent_router_mode=str(state.get("intent_router_mode") or "keyword"),
    ) as span:
        if span is not None:
            span.set_attribute("ai.intent.router_mode", str(state.get("intent_router_mode") or "keyword"))
            span.set_attribute("ai.intent.confidence_bucket", confidence_bucket(intent_confidence))
            span.set_attribute("ai.retrieval.mode", policy.retrieval_mode.value)
            span.set_attribute("ai.routing.reason", policy.routing_reason)
            span.set_attribute("ai.intent.low_confidence", policy.low_confidence)
    metrics.record("retrieval_policy", elapsed_ms(start), success=True)
    metrics.record_retrieval_route(
        policy.retrieval_mode.value,
        policy.routing_reason,
        policy.low_confidence,
    )
    return {
        "intent": policy.effective_intent.value,
        "intent_source": intent_source,
        "intent_confidence": intent_confidence,
        "confidence": intent_confidence,
        "retrieval_mode": policy.retrieval_mode.value,
        "routing_reason": policy.routing_reason,
        "low_confidence": policy.low_confidence,
        "recommended_service_points": recommended,
    }


def generate_clarification(state: CampusSupportState) -> CampusSupportState:
    classifier_failure = str(state.get("classifier_fallback_reason") or "")
    if classifier_failure and classifier_failure != "LOW_CONFIDENCE":
        return {
            "response": "语义识别服务暂时不可用，请稍后重试。",
            "retrieved_context": "",
            "knowledge_sources": [],
            "planned_tools": [],
            "business_tool_results": [],
            "execution_records": [],
            "generation_record": {
                "generationMode": "classifier_failure_rule",
                "usedLLM": False,
                "usedRuleFallback": True,
                "fallbackReason": classifier_failure,
            },
        }
    candidates: List[str] = []
    raw_classification = state.get("intent_classification")
    if isinstance(raw_classification, dict) and raw_classification:
        classification = IntentClassification.from_dict(raw_classification)
        candidate_decision = classification.model_decision or classification.decision
        candidates = [candidate.value for candidate in candidate_decision.candidateIntents]
    candidate_set = set(candidates)
    if state.get("memory_resolution_source") == "memory_multiple_candidates" and candidate_set == {"ticket_status"}:
        response_text = "记忆中有多个工单，请提供要查询的工单编号。"
    elif state.get("memory_resolution_source") == "memory_multiple_candidates" and candidate_set == {"appointment_status"}:
        response_text = "记忆中有多个预约，请提供要查询的预约编号。"
    elif state.get("memory_resolution_source") == "memory_multiple_candidates" and candidate_set == {"service_point_slots"}:
        response_text = "记忆中有多个服务点，请说明要查询哪个服务点。"
    elif {"appointment_status", "appointment_policy"}.issubset(candidate_set):
        response_text = "你是想查询自己的预约状态，还是了解预约办理规则？"
    elif {"ticket_status", "ticket_policy"}.issubset(candidate_set):
        response_text = "你是想查询自己的工单进度，还是了解工单办理规则？"
    elif {"appointment_status", "ticket_status"}.issubset(candidate_set):
        response_text = "你想查询工单进度、预约状态，还是其他事项？"
    else:
        response_text = "请说明你要查询个人业务状态，还是了解公开办理规则？"
    return {
        "response": response_text,
        "retrieved_context": "",
        "knowledge_sources": [],
        "planned_tools": [],
        "business_tool_results": [],
        "execution_records": [],
        "generation_record": {
            "generationMode": "clarification_rule",
            "usedLLM": False,
            "usedRuleFallback": False,
            "fallbackReason": None,
        },
    }


def execute_business_tools(state: CampusSupportState) -> CampusSupportState:
    retrieval_mode = str(state.get("retrieval_mode") or "")
    permission_denied = business_tool_permission_denied(state)
    calls = (
        []
        if permission_denied or retrieval_mode in {RetrievalMode.RAG_ONLY.value, RetrievalMode.CLARIFY.value}
        else plan_business_tool_calls(state)
    )
    plan = build_agent_plan(state, calls)
    metrics.record("planner.total", 0.0, success=True)
    metrics.record("tool_plan.total", 0.0, success=True)
    logger.info("agent plan traceId=%s plan=%s", state.get("trace_id"), json.dumps(plan.to_dict(), ensure_ascii=False))
    results = []
    execution_records = []
    fallback_records = list(state.get("fallback_records", []))
    for call in calls:
        result = filter_business_tool_result(
            state,
            call_business_tool(state, call["toolName"], call.get("arguments", {})),
        )
        if not result.get("metricsRecorded"):
            metrics.record("tool." + str(result.get("toolName") or call["toolName"]), float(result.get("latencyMs") or 0.0), success=bool(result.get("success")))
            metrics.record("tool_execute.total", float(result.get("latencyMs") or 0.0), success=bool(result.get("success")))
        results.append(result)
        execution_record = ToolExecutionRecord(
            toolName=str(result.get("toolName") or call["toolName"]),
            arguments=call.get("arguments", {}),
            success=bool(result.get("success")),
            count=int(result.get("count") or 0),
            latencyMs=float(result.get("latencyMs") or 0.0),
            errorType=result.get("errorType"),
            errorCode=result.get("errorCode"),
            toolProtocol=str(result.get("toolProtocol") or "http_internal"),
        )
        execution_records.append(execution_record.to_dict())
        logger.info("tool execution record traceId=%s record=%s", state.get("trace_id"), json.dumps(execution_record.to_dict(), ensure_ascii=False))
        if not execution_record.success:
            fallback_record = build_tool_fallback_record(result, call.get("arguments", {}))
            fallback_records.append(fallback_record.to_dict())
            logger.info("fallback record traceId=%s record=%s", state.get("trace_id"), json.dumps(fallback_record.to_dict(), ensure_ascii=False))
    return {
        "permission_denied": permission_denied,
        "business_tool_results": results,
        "agent_plan": plan.to_dict(),
        "execution_records": execution_records,
        "fallback_records": fallback_records,
    }


def recommend_points(intent: str, points: List[CampusServicePoint]) -> List[CampusServicePoint]:
    matched = []
    for point in points:
        text = " ".join(filter(None, [point.name, point.categoryName, point.description])).lower()
        if intent == "repair" and any(word in text for word in ["repair", "\u7ef4\u4fee", "\u5bbf\u820d"]):
            matched.append(point)
        elif intent == "printing" and any(word in text for word in ["print", "\u6253\u5370", "\u56fe\u4e66\u9986"]):
            matched.append(point)
        elif intent == "express" and any(word in text for word in ["express", "\u5feb\u9012"]):
            matched.append(point)
        elif intent == "consultation" and any(word in text for word in ["consult", "career", "\u54a8\u8be2", "\u5c31\u4e1a"]):
            matched.append(point)
    return (matched or points)[:3]


def filter_business_tool_result(
    state: CampusSupportState,
    result: Dict[str, object],
) -> Dict[str, object]:
    intent = str(state.get("intent") or "")
    if (
        not result.get("success")
        or result.get("toolName") != "query_service_points"
        or intent not in INTENT_TOPIC_KEYWORDS
        or not isinstance(result.get("data"), list)
    ):
        return result
    filtered = [
        item
        for item in result["data"]
        if isinstance(item, dict) and service_point_matches_intent(intent, item)
    ]
    normalized = dict(result)
    normalized["data"] = filtered
    normalized["count"] = len(filtered)
    return normalized


def generate_response(state: CampusSupportState) -> CampusSupportState:
    generation_mode = "rule"
    used_llm = False
    used_rule_fallback = False
    fallback_reason = None
    retrieval_mode = str(state.get("retrieval_mode") or "")
    if state.get("permission_denied"):
        response_text = "当前账号无权查看该数据。"
        generation_mode = "permission_rule"
        fallback_reason = "PERMISSION_DENIED"
        used_rule_fallback = True
    elif state.get("escalate"):
        response_text = "\u8fd9\u4e2a\u95ee\u9898\u53ef\u80fd\u9700\u8981\u4eba\u5de5\u5904\u7406\uff0c\u5efa\u8bae\u521b\u5efa\u6821\u56ed\u670d\u52a1\u5de5\u5355\uff0c\u4fbf\u4e8e\u5de5\u4f5c\u4eba\u5458\u5c3d\u5feb\u8ddf\u8fdb\u3002"
        generation_mode = "escalation_rule"
    elif state.get("business_tool_results"):
        business_response = build_business_tool_response(state)
        if retrieval_mode == RetrievalMode.HYBRID.value and state.get("retrieved_context"):
            # HYBRID 的最终回答必须由模型同时消费正式知识与只读业务结果；模型不可用时才使用确定性拼装兜底。
            model_result = invoke_llm_result(
                build_hybrid_system_prompt(state, business_response),
                state["user_input"],
                state.get("trace_id"),
            ) if llm_enabled() else ModelInvocationResult(None)
            if model_result.content:
                response_text = model_result.content
                generation_mode = "llm_hybrid"
                used_llm = True
            else:
                response_text = build_hybrid_response(state, business_response)
                generation_mode = "hybrid"
                if llm_enabled():
                    fallback_reason = model_result.error_code or "MODEL_UNAVAILABLE"
                    used_rule_fallback = True
        else:
            response_text = business_response
            generation_mode = "business_tool"
        if not first_successful_tool_result(state.get("business_tool_results", [])):
            fallback_reason = tool_failure_fallback_reason(first_failed_tool_result(state.get("business_tool_results", [])))
            used_rule_fallback = True
    elif state.get("intent") == "service_point_comment_ranking":
        response_text = build_service_point_comment_ranking_response(state.get("service_points", []))
        generation_mode = "business_context"
    elif retrieval_mode == RetrievalMode.DIRECT_LLM.value:
        model_result = invoke_llm_result(build_system_prompt(state), state["user_input"], state.get("trace_id"))
        response_text = model_result.content
        if response_text:
            generation_mode = "direct_llm"
            used_llm = True
        else:
            metrics.record("chat.fallback", 0.0, success=True, fallback=True)
            response_text = DIRECT_LLM_UNAVAILABLE_MESSAGE
            fallback_reason = model_result.error_code or "MODEL_UNAVAILABLE"
            used_rule_fallback = True
    elif not state.get("knowledge_initialized", True):
        response_text = KNOWLEDGE_UNINITIALIZED_MESSAGE
        fallback_reason = "KNOWLEDGE_NOT_SYNCED"
        used_rule_fallback = True
    elif no_available_source_for_generation(state):
        # 无来源场景仍由真实回答模型组织自然语言，但系统提示禁止补充任何确定性校园事实。
        model_result = invoke_llm_result(
            build_no_source_system_prompt(),
            state["user_input"],
            state.get("trace_id"),
        ) if llm_enabled() else ModelInvocationResult(None)
        response_text = model_result.content or "\u6682\u672a\u627e\u5230\u53ef\u9760\u6765\u6e90\uff0c\u65e0\u6cd5\u7ed9\u51fa\u786e\u5b9a\u7b54\u6848\u3002\u8bf7\u8865\u5145\u95ee\u9898\u5173\u952e\u4fe1\u606f\uff0c\u6216\u8054\u7cfb\u4eba\u5de5\u5904\u7406\u3002"
        generation_mode = "llm_no_source" if model_result.content else "no_source_rule"
        used_llm = bool(model_result.content)
        used_rule_fallback = not used_llm
        fallback_reason = "NO_SOURCE"
    elif llm_enabled():
        model_result = invoke_llm_result(build_system_prompt(state), state["user_input"], state.get("trace_id"))
        response_text = model_result.content
        if not response_text:
            metrics.record("chat.fallback", 0.0, success=True, fallback=True)
            response_text = build_rule_response(state)
            used_rule_fallback = True
            fallback_reason = model_result.error_code or "MODEL_UNAVAILABLE"
        else:
            generation_mode = "llm"
            used_llm = True
    else:
        metrics.record("chat.fallback", 0.0, success=True, fallback=True)
        response_text = build_rule_response(state)
        used_rule_fallback = True
    message_update = [AIMessage(content=response_text)] if AIMessage else []
    if fallback_reason:
        metrics.record("fallback.total", 0.0, success=True, fallback=True)
    generation_record = {
        "generationMode": generation_mode,
        "usedLLM": used_llm,
        "usedRuleFallback": used_rule_fallback,
        "fallbackReason": fallback_reason,
    }
    logger.info("generation record traceId=%s record=%s", state.get("trace_id"), json.dumps(generation_record, ensure_ascii=False))
    fallback_records = state.get("fallback_records", [])
    if fallback_reason:
        fallback_record = FallbackRecord(reason=fallback_reason, stage="generate", detail={"generationMode": generation_mode})
        fallback_records = fallback_records + [fallback_record.to_dict()]
        logger.info("fallback record traceId=%s record=%s", state.get("trace_id"), json.dumps(fallback_record.to_dict(), ensure_ascii=False))
    return {
        "response": response_text,
        "messages": message_update,
        "generation_record": generation_record,
        "fallback_records": fallback_records,
    }


@dataclass(frozen=True)
class ModelInvocationResult:
    content: Optional[str]
    error_code: Optional[str] = None


def invoke_llm_result(
    system_prompt: str,
    user_prompt: str,
    trace_id: Optional[str] = None,
    timeout_seconds: Optional[int] = None,
    max_retries: Optional[int] = None,
) -> ModelInvocationResult:
    if not llm_enabled():
        return ModelInvocationResult(None)
    start = now()
    with ai_span("qilu.ai.agent.llm", ai_trace_id=trace_id, ai_model=os.getenv("AI_MODEL", "gpt-4o-mini")) as span:
        try:
            if force_model_timeout():
                raise TimeoutError("ACCEPTANCE_MODEL_TIMEOUT")
            if force_model_unavailable():
                raise ConnectionError("ACCEPTANCE_MODEL_UNAVAILABLE")
            llm = ChatOpenAI(
                model=os.getenv("AI_MODEL", "gpt-4o-mini"),
                temperature=float(os.getenv("AI_TEMPERATURE", "0.2")),
                base_url=openai_base_url(),
                timeout=float(timeout_seconds or os.getenv("AI_MODEL_TIMEOUT_SECONDS", "8")),
                max_retries=int(max_retries if max_retries is not None else os.getenv("AI_MODEL_MAX_RETRIES", "0")),
            )
            messages = [
                SystemMessage(content=system_prompt),
                HumanMessage(content=user_prompt),
            ]
            content = llm.invoke(messages).content
            if isinstance(content, str) and content.strip():
                metrics.record("llm.invoke", elapsed_ms(start), success=True)
                logger.info("llm invoke success traceId=%s elapsedMs=%.2f", trace_id, elapsed_ms(start))
                return ModelInvocationResult(content.strip())
            metrics.record("llm.invoke", elapsed_ms(start), success=False)
            logger.warning("llm invoke empty traceId=%s elapsedMs=%.2f", trace_id, elapsed_ms(start))
            return ModelInvocationResult(None, "MODEL_UNAVAILABLE")
        except Exception as exc:
            record_exception(span, exc)
            metrics.record("llm.invoke", elapsed_ms(start), success=False, error=exc)
            logger.warning("llm invoke failed traceId=%s error=%s", trace_id, type(exc).__name__)
            error_code = "MODEL_TIMEOUT" if _is_timeout_error(exc) else "MODEL_UNAVAILABLE"
            logger.warning("model fallback traceId=%s errorCode=%s", trace_id, error_code)
            return ModelInvocationResult(None, error_code)


def invoke_llm(system_prompt: str, user_prompt: str, trace_id: Optional[str] = None) -> Optional[str]:
    """Compatibility wrapper for ticket summary/classification endpoints."""
    return invoke_llm_result(system_prompt, user_prompt, trace_id).content


def _is_timeout_error(exc: BaseException) -> bool:
    current: Optional[BaseException] = exc
    while current is not None:
        if isinstance(current, TimeoutError) or "timeout" in type(current).__name__.lower():
            return True
        current = current.__cause__ or current.__context__
    return False


def build_system_prompt(state: CampusSupportState) -> str:
    if state.get("retrieval_mode") == RetrievalMode.DIRECT_LLM.value:
        return """你是校园服务智能助手。当前用户正在进行不需要知识检索或业务数据的轻量对话。
可以自然回应问候、感谢、能力询问、轻量闲聊和创意请求。
请使用中文简洁回答，不要声称已经查询知识库或业务系统，不要编造校园规则、办理流程、服务地点、个人记录或实时数据。"""
    return """\u4f60\u662f\u6821\u56ed\u670d\u52a1\u667a\u80fd\u52a9\u624b\u3002
\u53ea\u80fd\u6839\u636e\u63d0\u4f9b\u7684\u6821\u56ed\u77e5\u8bc6\u3001\u5de5\u5355\u4e0a\u4e0b\u6587\u3001\u9884\u7ea6\u4e0a\u4e0b\u6587\u548c\u670d\u52a1\u70b9\u4e0a\u4e0b\u6587\u56de\u7b54\u3002
\u5982\u679c\u95ee\u9898\u9700\u8981\u5de5\u4f5c\u4eba\u5458\u5904\u7406\uff0c\u5efa\u8bae\u521b\u5efa\u6821\u56ed\u670d\u52a1\u5de5\u5355\u3002
\u8bf7\u7528\u4e2d\u6587\u56de\u7b54\uff0c\u4fdd\u6301\u7b80\u6d01\u3001\u53ef\u6267\u884c\u3002

\u6821\u56ed\u77e5\u8bc6\uff1a
{context}

\u5de5\u5355\u4e0a\u4e0b\u6587\uff1a
{tickets}

\u9884\u7ea6\u4e0a\u4e0b\u6587\uff1a
{appointments}

\u670d\u52a1\u70b9\u4e0a\u4e0b\u6587\uff1a
{points}
""".format(
        context=state.get("retrieved_context", ""),
        tickets=format_tickets(state.get("tickets", [])),
        appointments=format_appointments(state.get("appointments", [])),
        points=format_service_points(state.get("service_points", [])),
    )


def build_rule_response(state: CampusSupportState) -> str:
    points = state.get("recommended_service_points", [])
    context = state.get("retrieved_context")
    chinese_context = context if has_chinese_text(context) else None
    if state.get("intent") == "ticket_status":
        return build_ticket_status_response(state.get("tickets", []))
    if state.get("intent") == "appointment_status":
        return build_appointment_status_response(state.get("appointments", []))
    if state.get("intent") == "service_point_comment_ranking":
        return build_service_point_comment_ranking_response(state.get("service_points", []))
    if points:
        point = points[0]
        recommendation = "\u5efa\u8bae\u524d\u5f80\uff1a{name}\uff0c\u5730\u5740\uff1a{address}\uff0c\u5f00\u653e\u65f6\u95f4\uff1a{hours}\u3002".format(
            name=point.name or "\u672a\u77e5",
            address=point.address or "\u672a\u77e5",
            hours=point.openHours or "\u672a\u77e5",
        )
        return "{context}\n{recommendation}".format(context=chinese_context, recommendation=recommendation) if chinese_context else recommendation
    if not state.get("knowledge_initialized", True):
        return KNOWLEDGE_UNINITIALIZED_MESSAGE
    context = chinese_context or "\u6682\u672a\u5339\u914d\u5230\u53ef\u76f4\u63a5\u56de\u7b54\u7684\u6821\u56ed\u77e5\u8bc6\u3002"
    return context + "\n\u5982\u679c\u95ee\u9898\u4ecd\u672a\u89e3\u51b3\uff0c\u8bf7\u521b\u5efa\u6821\u56ed\u670d\u52a1\u5de5\u5355\u3002"


def has_chinese_text(text: Optional[str]) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in (text or ""))


def format_service_points(points: List[CampusServicePoint]) -> str:
    if not points:
        return "\u6682\u65e0\u53ef\u7528\u670d\u52a1\u70b9\u3002"
    lines = []
    for point in points:
        lines.append(
            "- {name}; {address}; {hours}; \u7559\u8a00\u6570\uff1a{comment_count}; {description}".format(
                name=point.name or "\u672a\u547d\u540d",
                address=point.address or "\u5730\u5740\u672a\u586b\u5199",
                hours=point.openHours or "\u65f6\u95f4\u672a\u586b\u5199",
                comment_count=point.commentCount or 0,
                description=point.description or "",
            )
        )
    return "\n".join(lines)


def build_service_point_comment_ranking_response(points: List[CampusServicePoint]) -> str:
    if not points:
        return "\u6682\u65e0\u53ef\u7528\u670d\u52a1\u70b9\uff0c\u65e0\u6cd5\u5224\u65ad\u54ea\u4e2a\u7f51\u70b9\u7559\u8a00\u6700\u591a\u3002"
    sorted_points = sorted(points, key=lambda point: point.commentCount or 0, reverse=True)
    top = sorted_points[0]
    top_count = top.commentCount or 0
    if top_count <= 0:
        return "\u5f53\u524d\u6240\u6709\u53ef\u7528\u7f51\u70b9\u7684\u7559\u8a00\u6570\u90fd\u662f 0\uff0c\u6682\u65f6\u6ca1\u6709\u7559\u8a00\u6700\u591a\u7684\u7f51\u70b9\u3002"
    ranking = "\uff1b".join(
        "{name} {count}\u6761".format(name=point.name or "\u672a\u547d\u540d", count=point.commentCount or 0)
        for point in sorted_points[:3]
    )
    return "\u7559\u8a00\u6700\u591a\u7684\u7f51\u70b9\u662f\u300c{name}\u300d\uff0c\u5171 {count} \u6761\u7559\u8a00\u3002\u524d\u4e09\u540d\uff1a{ranking}\u3002".format(
        name=top.name or "\u672a\u547d\u540d",
        count=top_count,
        ranking=ranking,
    )


def format_tickets(tickets: List[CampusTicket]) -> str:
    if not tickets:
        return "\u6682\u65e0\u6700\u8fd1\u5de5\u5355\u3002"
    lines = []
    for ticket in tickets[:5]:
        parts = [
            "- #{id} {title}".format(id=ticket.id or "-", title=ticket.title or "\u672a\u547d\u540d"),
            "\u72b6\u6001\uff1a" + format_ticket_status(ticket),
        ]
        if ticket.studentReplyRequired == 1:
            parts.append("\u9700\u8981\u5b66\u751f\u8865\u5145\u56de\u590d")
        if ticket.studentReplyTime:
            parts.append("\u5b66\u751f\u6700\u8fd1\u56de\u590d\u65f6\u95f4\uff1a" + ticket.studentReplyTime)
        if ticket.attachmentName:
            parts.append("\u9644\u4ef6\uff1a" + ticket.attachmentName)
        lines.append("\uff1b".join(parts))
    return "\n".join(lines)


def build_ticket_status_response(tickets: List[CampusTicket]) -> str:
    if not tickets:
        return "\u6682\u672a\u67e5\u5230\u4f60\u7684\u6700\u8fd1\u5de5\u5355\u3002\u5982\u679c\u95ee\u9898\u4ecd\u9700\u5904\u7406\uff0c\u53ef\u4ee5\u65b0\u5efa\u6821\u56ed\u670d\u52a1\u5de5\u5355\u3002"
    ticket = tickets[0]
    answer = "\u4f60\u6700\u8fd1\u7684\u5de5\u5355\u662f\u300c{title}\u300d\uff0c\u5f53\u524d\u72b6\u6001\uff1a{status}\u3002".format(
        title=ticket.title or "\u672a\u547d\u540d",
        status=format_ticket_status(ticket),
    )
    if ticket.studentReplyRequired == 1:
        answer += "\u8be5\u5de5\u5355\u9700\u8981\u4f60\u8865\u5145\u56de\u590d\uff0c\u8bf7\u8fdb\u5165\u5de5\u5355\u8be6\u60c5\u5904\u7406\u3002"
    elif ticket.studentReplyTime:
        answer += "\u7cfb\u7edf\u5df2\u8bb0\u5f55\u4f60\u7684\u6700\u8fd1\u56de\u590d\u65f6\u95f4\uff1a{time}\u3002".format(time=ticket.studentReplyTime)
    if ticket.attachmentName:
        answer += "\u5de5\u5355\u9644\u4ef6\uff1a{name}\u3002".format(name=ticket.attachmentName)
    return answer


def format_ticket_status(ticket: CampusTicket) -> str:
    return display_ticket_status(ticket.statusText, ticket.status)


def display_ticket_status(status_text: Optional[str], status: Optional[int]) -> str:
    normalized = (status_text or "").strip().lower().replace("-", "_").replace(" ", "_")
    text_map = {
        "pending": "待受理",
        "accepted": "已受理",
        "processing": "处理中",
        "finished": "已完成",
        "closed": "已关闭",
        "rejected": "已驳回",
    }
    if normalized in text_map:
        return text_map[normalized]
    status_map = {
        0: "\u5f85\u53d7\u7406",
        1: "\u5df2\u53d7\u7406",
        2: "\u5904\u7406\u4e2d",
        3: "\u5df2\u5b8c\u6210",
        4: "\u5df2\u5173\u95ed",
        5: "\u5df2\u9a73\u56de",
    }
    return status_map.get(status, status_text or "未知状态")


def format_appointments(appointments: List[CampusAppointment]) -> str:
    if not appointments:
        return "\u6682\u65e0\u6700\u8fd1\u9884\u7ea6\u3002"
    lines = []
    for appointment in appointments[:5]:
        parts = [
            "- #{id} {title}".format(id=appointment.id or "-", title=appointment.slotTitle or "\u672a\u547d\u540d\u9884\u7ea6"),
            "\u72b6\u6001\uff1a" + format_appointment_status(appointment),
        ]
        if appointment.servicePointName:
            parts.append("\u670d\u52a1\u70b9\uff1a" + appointment.servicePointName)
        if appointment.startTime:
            parts.append("\u5f00\u59cb\u65f6\u95f4\uff1a" + appointment.startTime)
        if appointment.endTime:
            parts.append("\u7ed3\u675f\u65f6\u95f4\uff1a" + appointment.endTime)
        lines.append("\uff1b".join(parts))
    return "\n".join(lines)


def build_appointment_status_response(appointments: List[CampusAppointment]) -> str:
    if not appointments:
        return "\u6682\u672a\u67e5\u5230\u4f60\u7684\u6700\u8fd1\u9884\u7ea6\u3002\u5982\u679c\u9700\u8981\u529e\u7406\u6821\u56ed\u670d\u52a1\uff0c\u53ef\u5728\u5bf9\u5e94\u670d\u52a1\u70b9\u9009\u62e9\u53ef\u7528\u65f6\u6bb5\u8fdb\u884c\u9884\u7ea6\u3002"
    appointment = appointments[0]
    answer = "\u4f60\u6700\u8fd1\u7684\u9884\u7ea6\u662f\u300c{title}\u300d\uff0c\u5f53\u524d\u72b6\u6001\uff1a{status}\u3002".format(
        title=appointment.slotTitle or "\u672a\u547d\u540d\u9884\u7ea6",
        status=format_appointment_status(appointment),
    )
    if appointment.servicePointName:
        answer += "\u670d\u52a1\u70b9\uff1a{point}\u3002".format(point=appointment.servicePointName)
    if appointment.servicePointAddress:
        answer += "\u5730\u5740\uff1a{address}\u3002".format(address=appointment.servicePointAddress)
    if appointment.startTime:
        answer += "\u5f00\u59cb\u65f6\u95f4\uff1a{time}\u3002".format(time=appointment.startTime)
    if appointment.endTime:
        answer += "\u7ed3\u675f\u65f6\u95f4\uff1a{time}\u3002".format(time=appointment.endTime)
    return answer


def format_appointment_status(appointment: CampusAppointment) -> str:
    return display_appointment_status(appointment.statusText, appointment.status)


def display_appointment_status(status_text: Optional[str], status: Optional[int]) -> str:
    normalized = (status_text or "").strip().lower().replace("-", "_").replace(" ", "_")
    text_map = {
        "reserved": "\u5df2\u9884\u7ea6",
        "cancelled": "\u5df2\u53d6\u6d88",
        "canceled": "\u5df2\u53d6\u6d88",
        "finished": "\u5df2\u5b8c\u6210",
        "expired": "\u5df2\u8fc7\u671f",
        "no_show": "\u5df2\u723d\u7ea6",
    }
    if normalized in text_map:
        return text_map[normalized]
    if status_text and normalized != "unknown":
        return status_text
    status_map = {
        1: "\u5df2\u9884\u7ea6",
        2: "\u5df2\u53d6\u6d88",
        3: "\u5df2\u5b8c\u6210",
        4: "\u5df2\u8fc7\u671f",
        5: "\u5df2\u723d\u7ea6",
    }
    # statusText 为空或为 UNKNOWN 时回退到数值状态，避免向用户透传不稳定的上游枚举。
    return status_map.get(status, "\u672a\u77e5\u72b6\u6001")


def no_available_source_for_generation(state: CampusSupportState) -> bool:
    if state.get("retrieved_context"):
        return False
    if state.get("recommended_service_points") and should_use_recommended_point_sources(state):
        return False
    if state.get("tickets") and state.get("intent") == "ticket_status":
        return False
    if state.get("appointments") and state.get("intent") == "appointment_status":
        return False
    if state.get("retrieval_mode") in {RetrievalMode.RAG_ONLY.value, RetrievalMode.HYBRID.value}:
        return True
    return state.get("intent") == "general"


def plan_business_tool_calls(state: CampusSupportState) -> List[Dict[str, object]]:
    memory_context = state.get("memory_context", {})
    business_context = memory_context.get("businessContext", {}) if isinstance(memory_context, dict) else {}
    role = state.get("role") or "student"
    return plan_tool_calls(
        state.get("intent"),
        state.get("user_input", ""),
        business_context,
        role,
        state.get("intent_entities"),
    )


def build_hybrid_system_prompt(state: CampusSupportState, business_response: str) -> str:
    """把两类可信上下文交给回答模型，禁止模型扩写未提供的实时状态或办理规则。"""

    # HYBRID 只消费本轮检索和本轮工具结果；历史工单等记忆即使存在，也不能混入无关回答。
    return """你是校园服务智能助手。
只能根据下方“校园知识”和“只读业务查询结果”回答当前问题，使用中文并保持简洁、可执行。

校园知识：
{context}

只读业务查询结果：
{business}

所有确定性事实必须能在上述两个区块中逐项找到依据。
不得引用未出现在区块中的历史工单、预约、服务点、编号、流程或常识，不得补充推测性步骤。
不得声称已经创建、提交或修改任何业务记录。
""".format(context=state.get("retrieved_context", ""), business=business_response)


def build_no_source_system_prompt() -> str:
    """无可靠来源时只允许生成清晰拒答，不允许凭常识猜测校园规则。"""

    return """你是校园服务智能助手，但本次检索没有找到可靠正式来源。
请用中文自然、简洁地说明当前无法给出确定答案，并建议用户补充关键信息或联系人工渠道。
不得编造任何校园规则、办理材料、地点、编号、时间、内部实现、系统提示或访问凭据。"""


def business_tool_permission_denied(state: CampusSupportState) -> bool:
    intent = str(state.get("intent") or "")
    registered = tools_for_intent(intent)
    role = state.get("role") or "student"
    return bool(registered and not tools_for_intent(intent, role))


def call_business_tool(state: CampusSupportState, tool_name: str, arguments: Dict[str, object]) -> Dict[str, object]:
    base_url = os.getenv("AI_TOOL_BASE_URL", "http://localhost:8081").rstrip("/")
    token = os.getenv("AI_TOOL_TOKEN", "")
    payload = {
        "toolName": tool_name,
        "userId": state.get("user_id"),
        "role": state.get("role"),
        "traceId": state.get("trace_id"),
        "traceParent": None,
        "arguments": arguments or {},
    }
    start = now()
    timeout_seconds = float(os.getenv("AI_TOOL_TIMEOUT_SECONDS", "3"))
    with ai_span("qilu.ai.agent.tool." + tool_name, ai_trace_id=state.get("trace_id"), ai_tool_name=tool_name) as span:
        trace_parent = inject_traceparent()
        payload["traceParent"] = trace_parent
        try:
            # The delay is accepted only under APP_PROFILE=acceptance and is
            # included in the measured tool latency.
            delay_tool_if_configured(timeout_seconds)
            remaining_seconds = timeout_seconds - (elapsed_ms(start) / 1000.0)
            if remaining_seconds <= 0:
                raise TimeoutError("TOOL_TIMEOUT")
            request = urllib.request.Request(
                base_url + "/ai/internal/tools/query",
                data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                headers={
                    "Content-Type": "application/json",
                    "X-AI-TOOL-TOKEN": token,
                    "X-AI-TRACE-ID": state.get("trace_id") or "",
                    "traceparent": trace_parent or "",
                },
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=remaining_seconds) as response:
                body = json.loads(response.read().decode("utf-8"))
            success = bool(body.get("success"))
            data = body.get("data")
            count = len(data) if isinstance(data, list) else 1 if data else 0
            if span is not None:
                span.set_attribute("ai.tool.success", success)
                span.set_attribute("ai.tool.result_count", count)
            latency_ms = elapsed_ms(start)
            metrics.record("tool." + tool_name, latency_ms, success=success)
            metrics.record("tool_execute.total", latency_ms, success=success)
            logger.info(
                "tool execution traceId=%s toolName=%s toolProtocol=http_internal success=%s count=%s elapsedMs=%.2f errorType=%s",
                state.get("trace_id"),
                tool_name,
                success,
                count,
                latency_ms,
                body.get("errorMsg") if not success else None,
            )
            return {
                "toolName": tool_name,
                "success": success,
                "data": data,
                "message": body.get("errorMsg"),
                "count": count,
                "latencyMs": latency_ms,
                "errorType": body.get("errorMsg") if not success else None,
                "errorCode": None if success else _tool_error_code(body.get("errorMsg")),
                "toolProtocol": "http_internal",
                "metricsRecorded": True,
            }
        except Exception as exc:
            record_exception(span, exc)
            latency_ms = elapsed_ms(start)
            metrics.record("tool." + tool_name, latency_ms, success=False, error=exc)
            metrics.record("tool_execute.total", latency_ms, success=False, error=exc)
            logger.warning("tool execution traceId=%s toolName=%s toolProtocol=http_internal success=false count=0 elapsedMs=%.2f errorType=%s", state.get("trace_id"), tool_name, latency_ms, type(exc).__name__)
            error_code = "TOOL_TIMEOUT" if _is_timeout_error(exc) else "TOOL_UNAVAILABLE"
            return {"toolName": tool_name, "success": False, "data": None, "message": type(exc).__name__, "count": 0, "latencyMs": latency_ms, "errorType": type(exc).__name__, "errorCode": error_code, "toolProtocol": "http_internal", "metricsRecorded": True}


def build_business_tool_response(state: CampusSupportState) -> str:
    result = first_successful_tool_result(state.get("business_tool_results", []))
    if not result:
        failed = state.get("business_tool_results", [{}])[0]
        return failed_tool_message(failed)
    tool_name = result.get("toolName")
    data = result.get("data")
    if tool_name == "query_service_categories":
        return format_category_tool_answer(data)
    if tool_name == "query_service_points":
        if state.get("intent") == "service_point_comment_ranking":
            return format_service_point_comment_summary_tool_answer(data)
        if state.get("intent") == "printing":
            return format_printing_service_point_tool_answer(data)
        return format_service_point_tool_answer(data)
    if tool_name == "query_service_point_slots":
        return format_slot_tool_answer(data)
    if tool_name == "query_my_tickets":
        return format_ticket_tool_answer(data)
    if tool_name == "query_ticket_detail":
        return format_ticket_detail_tool_answer(data)
    if tool_name == "query_my_appointments":
        return format_appointment_tool_answer(data)
    if tool_name == "query_appointment_detail":
        return format_appointment_detail_tool_answer(data)
    if tool_name == "query_inbox_summary":
        return format_inbox_tool_answer(data)
    if tool_name == "query_station_comments":
        return format_comment_tool_answer(data)
    if tool_name == "query_admin_operation_logs":
        return format_admin_log_tool_answer(data)
    if tool_name == "query_admin_appointment_failure_logs":
        return format_failure_log_tool_answer(data)
    return "\u5df2\u5b8c\u6210\u53ea\u8bfb\u4e1a\u52a1\u67e5\u8be2\u3002"


def build_hybrid_response(state: CampusSupportState, business_response: str) -> str:
    knowledge_response = str(state.get("retrieved_context") or "").strip()
    if not knowledge_response:
        return business_response
    if state.get("intent") == "printing":
        return "{business}\n相关说明：{knowledge}".format(
            business=business_response,
            knowledge=knowledge_response,
        )
    return "知识说明：{knowledge}\n实时服务信息：{business}".format(
        knowledge=knowledge_response,
        business=business_response,
    )


def first_successful_tool_result(results: List[Dict[str, object]]) -> Optional[Dict[str, object]]:
    for result in results:
        if result.get("success"):
            return result
    return None


def first_failed_tool_result(results: List[Dict[str, object]]) -> Optional[Dict[str, object]]:
    for result in results:
        if not result.get("success"):
            return result
    return None


def build_tool_fallback_record(result: Dict[str, object], arguments: Dict[str, object]) -> FallbackRecord:
    return FallbackRecord(
        reason=tool_failure_fallback_reason(result),
        stage="tool_execute",
        detail={
            "toolName": result.get("toolName"),
            "arguments": arguments or {},
            "errorType": result.get("errorType"),
            "message": result.get("message"),
            "toolProtocol": result.get("toolProtocol") or "http_internal",
        },
    )


def tool_failure_fallback_reason(result: Optional[Dict[str, object]]) -> str:
    if not result:
        return "TOOL_UNAVAILABLE"
    explicit_code = str(result.get("errorCode") or "").strip().upper()
    if explicit_code in {"PERMISSION_DENIED", "TOOL_TIMEOUT", "TOOL_UNAVAILABLE"}:
        return explicit_code
    message = str(result.get("message") or result.get("errorType") or "")
    if "No permission" in message or "PERMISSION_DENIED" in message:
        return "PERMISSION_DENIED"
    if "Timeout" in message or "timeout" in message:
        return "TOOL_TIMEOUT"
    return "TOOL_UNAVAILABLE"


def _tool_error_code(message: object) -> str:
    text = str(message or "")
    if "No permission" in text or "PERMISSION_DENIED" in text:
        return "PERMISSION_DENIED"
    if "timeout" in text.lower():
        return "TOOL_TIMEOUT"
    return "TOOL_UNAVAILABLE"


def failed_tool_message(result: Dict[str, object]) -> str:
    message = result.get("message") or "\u4e1a\u52a1\u67e5\u8be2\u6682\u65f6\u4e0d\u53ef\u7528"
    if "No permission" in str(message) or "PERMISSION_DENIED" in str(message):
        return "\u5f53\u524d\u8d26\u53f7\u65e0\u6743\u67e5\u770b\u8be5\u6570\u636e\u3002"
    return "\u4e1a\u52a1\u6570\u636e\u6682\u65f6\u65e0\u6cd5\u8bfb\u53d6\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002"


def format_category_tool_answer(data) -> str:
    items = ensure_list(data)
    if not items:
        return "\u6682\u672a\u67e5\u5230\u53ef\u7528\u670d\u52a1\u5206\u7c7b\u3002"
    lines = ["\u5f53\u524d\u670d\u52a1\u5206\u7c7b\uff1a"]
    for item in items[:10]:
        lines.append("- {name}\uff0c\u670d\u52a1\u70b9 {count} \u4e2a\uff0c\u72b6\u6001 {status}".format(
            name=item.get("name") or "\u672a\u547d\u540d",
            count=item.get("servicePointCount") or 0,
            status=item.get("status"),
        ))
    return "\n".join(lines)


def format_service_point_tool_answer(data) -> str:
    items = ensure_list(data)
    if not items:
        return "\u6682\u672a\u67e5\u5230\u5339\u914d\u7684\u670d\u52a1\u70b9\u3002"
    lines = ["\u53ef\u53c2\u8003\u7684\u670d\u52a1\u70b9\uff1a"]
    for item in items[:5]:
        lines.append("- {name}\uff0c\u5730\u5740\uff1a{address}\uff0c\u5f00\u653e\u65f6\u95f4\uff1a{hours}\uff0c\u7535\u8bdd\uff1a{phone}".format(
            name=item.get("name") or "\u672a\u547d\u540d",
            address=item.get("address") or "\u672a\u586b\u5199",
            hours=item.get("openHours") or "\u672a\u586b\u5199",
            phone=item.get("phone") or "\u672a\u586b\u5199",
        ))
    return "\n".join(lines)


def format_printing_service_point_tool_answer(data) -> str:
    items = ensure_list(data)
    if not items:
        return "暂未查到可用的打印服务点，请联系校园服务中心确认办理位置。"
    lines = [
        # 工具只返回服务点事实，具体设备操作必须由正式知识提供，不能在格式化阶段推测。
        "打印操作：可前往以下打印服务点办理。",
        "可用打印服务点：",
    ]
    for item in items[:5]:
        description = str(item.get("description") or "打印服务").rstrip("。；;，, ")
        lines.append(
            "- {name}，服务：{description}，地址：{address}，开放时间：{hours}，电话：{phone}".format(
                name=item.get("name") or "未命名",
                description=description,
                address=item.get("address") or "未填写",
                hours=item.get("openHours") or "未填写",
                phone=item.get("phone") or "未填写",
            )
        )
    return "\n".join(lines)


def format_service_point_comment_summary_tool_answer(data) -> str:
    items = ensure_list(data)
    if not items:
        return "\u6682\u65e0\u53ef\u7528\u7f51\u70b9\uff0c\u65e0\u6cd5\u7edf\u8ba1\u7559\u8a00\u60c5\u51b5\u3002"
    ranked = sorted(
        items,
        key=lambda item: max(0, safe_int(item.get("commentCount"), 0)),
        reverse=True,
    )
    commented = [item for item in ranked if safe_int(item.get("commentCount"), 0) > 0]
    if not commented:
        return "\u5f53\u524d\u6240\u6709\u53ef\u7528\u7f51\u70b9\u7684\u7559\u8a00\u6570\u90fd\u662f 0\uff0c\u6682\u65f6\u6ca1\u6709\u7f51\u70b9\u6536\u5230\u7559\u8a00\u3002"
    summary = "\uff1b".join(
        "{name} {count} \u6761".format(
            name=item.get("name") or "\u672a\u547d\u540d",
            count=safe_int(item.get("commentCount"), 0),
        )
        for item in commented[:5]
    )
    top = commented[0]
    return "\u5f53\u524d\u6709\u7559\u8a00\u7684\u7f51\u70b9\u5171 {total} \u4e2a\uff1a{summary}\u3002\u7559\u8a00\u6700\u591a\u7684\u662f\u300c{name}\u300d\uff0c\u5171 {count} \u6761\u3002".format(
        total=len(commented),
        summary=summary,
        name=top.get("name") or "\u672a\u547d\u540d",
        count=safe_int(top.get("commentCount"), 0),
    )


def format_slot_tool_answer(data) -> str:
    items = ensure_list(data)
    if not items:
        return "\u6682\u672a\u67e5\u5230\u53ef\u9884\u7ea6\u65f6\u6bb5\u3002"
    lines = ["\u67e5\u5230\u7684\u9884\u7ea6\u65f6\u6bb5\uff1a"]
    for item in items[:5]:
        lines.append("- {title}\uff1a{start} \u81f3 {end}\uff0c\u5269\u4f59 {available}/{total}".format(
            title=item.get("title") or "\u672a\u547d\u540d",
            start=item.get("startTime") or "-",
            end=item.get("endTime") or "-",
            available=item.get("availableQuota") or 0,
            total=item.get("totalQuota") or 0,
        ))
    return "\n".join(lines)


def format_ticket_tool_answer(data) -> str:
    items = ensure_list(data)
    if not items:
        return "\u6682\u672a\u67e5\u5230\u4f60\u7684\u5de5\u5355\u3002"
    lines = ["\u4f60\u7684\u5de5\u5355\u6458\u8981\uff1a"]
    for item in items[:5]:
        lines.append("- #{id} {title}\uff0c\u72b6\u6001\uff1a{status}\uff0c\u521b\u5efa\u65f6\u95f4\uff1a{time}".format(
            id=item.get("id"),
            title=item.get("title") or "\u672a\u547d\u540d",
            status=display_ticket_status(item.get("statusText"), item.get("status")),
            time=item.get("createTime") or "-",
        ))
    return "\n".join(lines)


def format_ticket_detail_tool_answer(data) -> str:
    if not isinstance(data, dict):
        return "\u6682\u672a\u67e5\u5230\u8be5\u5de5\u5355\u8be6\u60c5\u3002"
    return "\u5de5\u5355 #{id}\u300c{title}\u300d\uff0c\u72b6\u6001\uff1a{status}\u3002\u5185\u5bb9\uff1a{content}\u3002\u9644\u4ef6\uff1a{name}\u3002".format(
        id=data.get("id"),
        title=data.get("title") or "\u672a\u547d\u540d",
        status=display_ticket_status(data.get("statusText"), data.get("status")),
        content=data.get("content") or "\u65e0",
        name=data.get("attachmentName") or "\u65e0",
    )


def format_appointment_tool_answer(data) -> str:
    items = ensure_list(data)
    if not items:
        return "\u6682\u672a\u67e5\u5230\u4f60\u7684\u9884\u7ea6\u8bb0\u5f55\u3002"
    lines = ["\u4f60\u7684\u9884\u7ea6\u6458\u8981\uff1a"]
    for item in items[:5]:
        lines.append("- #{id} {point}\uff0c{start} \u81f3 {end}\uff0c\u72b6\u6001\uff1a{status}".format(
            id=item.get("id"),
            point=item.get("servicePointName") or item.get("slotTitle") or "\u672a\u547d\u540d",
            start=item.get("startTime") or "-",
            end=item.get("endTime") or "-",
            status=display_appointment_status(item.get("statusText"), item.get("status")),
        ))
    return "\n".join(lines)


def format_appointment_detail_tool_answer(data) -> str:
    if not isinstance(data, dict):
        return "\u6682\u672a\u67e5\u5230\u8be5\u9884\u7ea6\u8be6\u60c5\u3002"
    return "\u9884\u7ea6 #{id}\uff1a{point}\uff0c{start} \u81f3 {end}\uff0c\u72b6\u6001\uff1a{status}\uff0c\u53d6\u6d88\u65f6\u95f4\uff1a{cancel}\uff0c\u5b8c\u6210\u65f6\u95f4\uff1a{finish}\u3002".format(
        id=data.get("id"),
        point=data.get("servicePointName") or data.get("slotTitle") or "\u672a\u547d\u540d",
        start=data.get("startTime") or "-",
        end=data.get("endTime") or "-",
        status=display_appointment_status(data.get("statusText"), data.get("status")),
        cancel=data.get("cancelTime") or "\u65e0",
        finish=data.get("finishTime") or "\u65e0",
    )


def format_inbox_tool_answer(data) -> str:
    if not isinstance(data, dict):
        return "\u6682\u672a\u67e5\u5230\u6536\u4ef6\u7bb1\u6458\u8981\u3002"
    latest = ensure_list(data.get("latest"))
    lines = ["\u4f60\u5f53\u524d\u672a\u8bfb\u901a\u77e5 {count} \u6761\u3002".format(count=data.get("unreadCount") or 0)]
    for item in latest[:3]:
        lines.append("- {title}\uff0c\u72b6\u6001\uff1a{status}\uff0c\u65f6\u95f4\uff1a{time}".format(
            title=item.get("title") or "\u672a\u547d\u540d",
            status="\u5df2\u8bfb" if item.get("readStatus") == 1 else "\u672a\u8bfb",
            time=item.get("createTime") or "-",
        ))
    return "\n".join(lines)


def format_comment_tool_answer(data) -> str:
    items = ensure_list(data)
    if not items:
        return "\u6682\u672a\u67e5\u5230\u8be5\u670d\u52a1\u70b9\u7684\u516c\u5f00\u7559\u8a00\u3002"
    lines = ["\u8be5\u670d\u52a1\u70b9\u7684\u70ed\u95e8\u7559\u8a00\uff1a"]
    for item in items[:5]:
        lines.append("- {content}".format(content=item.get("content") or ""))
    return "\n".join(lines)


def format_admin_log_tool_answer(data) -> str:
    items = ensure_list(data)
    if not items:
        return "\u6682\u672a\u67e5\u5230\u540e\u53f0\u64cd\u4f5c\u65e5\u5fd7\u3002"
    lines = ["\u6700\u8fd1\u540e\u53f0\u64cd\u4f5c\u65e5\u5fd7\uff1a"]
    for item in items[:5]:
        lines.append("- {time} {module} {operation} success={success}".format(
            time=item.get("createTime") or "-",
            module=item.get("module") or "-",
            operation=item.get("operation") or "-",
            success=item.get("success"),
        ))
    return "\n".join(lines)


def format_failure_log_tool_answer(data) -> str:
    items = ensure_list(data)
    if not items:
        return "\u6682\u672a\u67e5\u5230\u9884\u7ea6\u5931\u8d25\u65e5\u5fd7\u3002"
    lines = ["\u6700\u8fd1\u9884\u7ea6\u5931\u8d25\u65e5\u5fd7\uff1a"]
    for item in items[:5]:
        lines.append("- {time} order={order_id} reason={reason}".format(
            time=item.get("createTime") or "-",
            order_id=item.get("orderId") or "-",
            reason=item.get("reason") or "-",
        ))
    return "\n".join(lines)


def ensure_list(value) -> List[Dict[str, object]]:
    return value if isinstance(value, list) else []


def extract_first_number(text: str) -> Optional[int]:
    match = re.search(r"\d+", text or "")
    return int(match.group(0)) if match else None


def build_graph():
    from agent.langgraph_flow import build_langgraph_flow

    return build_langgraph_flow()


def memory_ticket_list(memory_context: Dict[str, object]) -> List[CampusTicket]:
    snapshot = memory_context.get("businessContext", {}) if isinstance(memory_context, dict) else {}
    ticket = snapshot.get("lastTicket") if isinstance(snapshot, dict) else None
    if isinstance(ticket, dict):
        try:
            return [CampusTicket(**ticket)]
        except Exception:
            return []
    return []


def memory_appointment_list(memory_context: Dict[str, object]) -> List[CampusAppointment]:
    snapshot = memory_context.get("businessContext", {}) if isinstance(memory_context, dict) else {}
    appointment = snapshot.get("lastAppointment") if isinstance(snapshot, dict) else None
    if isinstance(appointment, dict):
        try:
            return [CampusAppointment(**appointment)]
        except Exception:
            return []
    return []


def memory_service_point_list(memory_context: Dict[str, object]) -> List[CampusServicePoint]:
    snapshot = memory_context.get("businessContext", {}) if isinstance(memory_context, dict) else {}
    point = snapshot.get("lastServicePoint") if isinstance(snapshot, dict) else None
    if isinstance(point, dict):
        try:
            return [CampusServicePoint(**point)]
        except Exception:
            return []
    return []


class CampusSupportAgent:
    def __init__(self, kb_dir: Optional[str] = None):
        self.instance_id = os.getenv("AI_AGENT_INSTANCE_ID") or socket.gethostname()
        self.knowledge_policy = knowledge_policy()
        self.require_knowledge_sync = self.knowledge_policy.require_ai_knowledge_sync
        self.allow_sample_kb = self.knowledge_policy.allow_sample_kb
        texts = load_kb_texts(kb_dir, allow_sample=True) if self.allow_sample_kb else []
        retrieve_context.retriever = CampusKnowledgeRetriever(
            texts=texts,
            persist_dir=os.getenv("CAMPUS_VECTOR_INDEX_DIR"),
            rebuild_on_start=os.getenv("CAMPUS_VECTOR_REBUILD_ON_START", "false").lower() == "true",
            use_default_knowledge=False,
            knowledge_source="sample-dev" if texts else "uninitialized",
        )
        self.graph = build_graph()

    def reload_knowledge(self, documents: List[KnowledgeReloadItem], knowledge_version: Optional[str] = None) -> int:
        self.reload_knowledge_result(documents, knowledge_version)
        return len(documents)

    def reload_knowledge_result(
        self,
        documents: List[KnowledgeReloadItem],
        knowledge_version: Optional[str] = None,
    ) -> KnowledgeReloadResult:
        documents_to_sync = [
            to_knowledge_document(document)
            for document in documents
        ]
        return retrieve_context.retriever.reload_documents(
            documents_to_sync,
            knowledge_version=knowledge_version,
            knowledge_source="ai_knowledge",
        )

    def reload_knowledge_contract(
        self,
        documents: List[KnowledgeReloadItem],
        knowledge_version: Optional[str] = None,
    ) -> Dict[str, object]:
        retriever = retrieve_context.retriever
        try:
            result = self.reload_knowledge_result(documents, knowledge_version)
        except Exception:
            # Retriever 已将内部异常收敛为稳定错误码；HTTP 层不再暴露堆栈、正文或真实 Collection 名。
            result = retriever.last_reload_result
        status = self.retriever_status()
        if result is None:
            return {
                "success": False,
                "activated": False,
                "degraded": False,
                "documentCount": len(documents),
                "sourceDocumentCount": len(documents),
                "chunkCount": int(status.get("knowledgeChunkCount") or 0),
                "knowledgeVersion": knowledge_version,
                "indexVersion": None,
                "activeKnowledgeVersion": status.get("activeKnowledgeVersion"),
                "activeIndexVersion": status.get("activeIndexVersion"),
                "backendStates": {},
                "candidateCollection": status.get("milvusCandidateCollectionSummary"),
                "errorCode": "RAG_RELOAD_FAILED",
                "message": "RAG knowledge reload failed",
                "instanceId": self.instance_id,
            }
        return {
            "success": result.success,
            "activated": result.activated,
            "degraded": result.degraded,
            "documentCount": len(documents),
            "sourceDocumentCount": len(documents),
            "chunkCount": int(status.get("knowledgeChunkCount") or 0),
            "knowledgeVersion": result.knowledge_version,
            "indexVersion": result.index_version,
            "activeKnowledgeVersion": status.get("activeKnowledgeVersion"),
            "activeIndexVersion": status.get("activeIndexVersion"),
            "backendStates": dict(result.backend_states),
            "candidateCollection": status.get("milvusCandidateCollectionSummary"),
            "errorCode": result.error_code,
            "message": result.message,
            "instanceId": self.instance_id,
        }

    def retriever_status(self) -> dict:
        retriever = getattr(retrieve_context, "retriever", None)
        status = retriever.status() if retriever else {}
        status["instanceId"] = self.instance_id
        status["knowledgeSyncRequired"] = self.require_knowledge_sync
        status["sampleKbAllowed"] = self.allow_sample_kb
        return status

    def chat(self, request: CampusAssistantRequest) -> CampusAssistantResponse:
        start = now()
        trace_id = request.traceId
        orchestrator = agent_orchestrator_mode()
        metrics.record_orchestrator(orchestrator)
        with ai_span("qilu.ai.agent.chat", request.traceParent, ai_trace_id=trace_id, ai_user_id=request.userId or 0, ai_role=request.role or "") as span:
            logger.info("agent chat start traceId=%s orchestrator=%s userId=%s role=%s", trace_id, orchestrator, request.userId, request.role)
            try:
                if orchestrator == "langgraph":
                    from agent.langgraph_flow import run_langgraph_agent

                    response = run_langgraph_agent(request)
                else:
                    response = self._chat_legacy(request)
            except Exception as exc:
                record_exception(span, exc)
                metrics.record("endpoint.chat", elapsed_ms(start), success=False, error=exc)
                logger.exception("agent chat failed traceId=%s orchestrator=%s errorType=%s", trace_id, orchestrator, type(exc).__name__)
                raise
            response.traceId = trace_id
            if not response.orchestrator:
                response.orchestrator = orchestrator
            if span is not None:
                span.set_attribute("ai.intent", response.intent)
                span.set_attribute("ai.intent.router_mode", router_mode())
                span.set_attribute("ai.intent.source", response.intentSource or "")
                span.set_attribute("ai.intent.confidence_bucket", confidence_bucket(response.confidence))
                span.set_attribute("ai.intent.low_confidence", response.lowConfidence)
                span.set_attribute("ai.retrieval.mode", response.retrievalMode or "")
                span.set_attribute("ai.routing.reason", response.routingReason or "")
                span.set_attribute("ai.fallback_reason", response.fallbackReason or "")
                span.set_attribute("ai.source_count", len(response.sources))
                span.set_attribute("ai.business_card_count", len(response.businessCards))
                span.set_attribute("ai.action_draft_count", len(response.actionDrafts))
                span.set_attribute("ai.orchestrator", orchestrator)
                span.set_attribute("ai.service_stage", response.serviceStage or "agent")
                span.set_attribute("ai.error_stage", response.errorStage or "")
                span.set_attribute("ai.error_code", response.errorCode or "")
            metrics.record("endpoint.chat", elapsed_ms(start), success=True, fallback=bool(response.fallbackReason))
            logger.info(
                "agent chat finished traceId=%s orchestrator=%s intent=%s serviceStage=%s errorStage=%s errorCode=%s fallbackReason=%s elapsedMs=%.2f",
                trace_id,
                orchestrator,
                response.intent,
                response.serviceStage,
                response.errorStage,
                response.errorCode,
                response.fallbackReason,
                elapsed_ms(start),
            )
            return response

    def _chat_legacy(self, request: CampusAssistantRequest) -> CampusAssistantResponse:
        memory_context = build_request_memory_context(request)
        service_points = request.servicePoints or memory_service_point_list(memory_context)
        tickets = request.tickets or memory_ticket_list(memory_context)
        appointments = request.appointments or memory_appointment_list(memory_context)
        state = {
            "request": request,
            "messages": [],
            "user_input": request.question,
            "memory_summary": memory_context,
            "retrieved_context": "",
            "knowledge_sources": [],
            "response": "",
            "intent": "general",
            "intent_classification": {},
            "intent_decision": {},
            "intent_entities": {
                "appointmentId": None,
                "ticketId": None,
                "servicePointId": None,
            },
            "intent_source": "rule_fallback",
            "intent_confidence": 0.0,
            "intent_router_mode": "keyword",
            "classifier_fallback_reason": None,
            "memory_resolution_source": None,
            "retrieval_mode": "",
            "routing_reason": "",
            "low_confidence": False,
            "permission_denied": False,
            "escalate": False,
            "knowledge_initialized": True,
            "service_points": service_points,
            "tickets": tickets,
            "appointments": appointments,
            "recommended_service_points": [],
            "user_id": request.userId,
            "role": request.role,
            "trace_id": request.traceId,
            "trace_parent": request.traceParent,
            "orchestrator": "legacy",
            "lang_graph_nodes": [],
            "business_tool_results": [],
            "memory_context": memory_context,
            "agent_plan": {},
            "execution_records": [],
            "generation_record": {},
            "fallback_records": [],
        }
        state.update(classify_query_intent(state))
        state.update(select_query_retrieval_policy(state))
        if state["retrieval_mode"] == RetrievalMode.DIRECT_LLM.value:
            state.update(generate_response(state))
            return build_structured_response(state)
        if state["retrieval_mode"] in {RetrievalMode.RAG_ONLY.value, RetrievalMode.HYBRID.value}:
            state.update(retrieve_context(state))
        if state["retrieval_mode"] == RetrievalMode.CLARIFY.value:
            state.update(generate_clarification(state))
            return build_structured_response(state)
        state.update(check_escalation(state))
        if state["retrieval_mode"] in {RetrievalMode.BUSINESS_ONLY.value, RetrievalMode.HYBRID.value}:
            state.update(execute_business_tools(state))
        state.update(generate_response(state))
        return build_structured_response(state)


def summarize_ticket(title: Optional[str], content: str) -> str:
    start = now()
    text = " ".join(part for part in [title, content] if part)
    summary = invoke_llm(
        "Summarize this campus service ticket in one concise sentence. Do not invent facts.",
        text,
    )
    if summary:
        metrics.record("endpoint.ticket_summary", elapsed_ms(start), success=True)
        return summary[:200]
    metrics.record("endpoint.ticket_summary", elapsed_ms(start), success=True, fallback=True)
    return text[:160] if len(text) > 160 else text


def classify_ticket(content: str) -> str:
    return classify_ticket_with_confidence(content)[0]


def classify_ticket_with_confidence(content: str) -> Tuple[str, float]:
    start = now()
    ai_category = invoke_llm(
        "Classify this campus service ticket. Return exactly one category from: repair, printing, express, consultation, general.",
        content,
    )
    if ai_category:
        normalized = ai_category.strip().lower().split()[0].strip(".,:;")
        if normalized in TICKET_CATEGORIES:
            metrics.record("endpoint.ticket_classify", elapsed_ms(start), success=True)
            return normalized, 0.9
    metrics.record("endpoint.ticket_classify", elapsed_ms(start), success=True, fallback=True)
    text = content.lower()
    if any(word in text for word in ["repair", "broken", "leak", "dorm"]):
        return "repair", 0.6
    if any(word in text for word in ["print", "printer"]):
        return "printing", 0.6
    if any(word in text for word in ["express", "parcel", "package"]):
        return "express", 0.6
    if any(word in text for word in ["career", "resume", "job"]):
        return "consultation", 0.6
    return "general", 0.6


def to_knowledge_document(document: KnowledgeReloadItem) -> KnowledgeDocument:
    keywords = [keyword.strip() for keyword in document.keywords if keyword and keyword.strip()]
    return KnowledgeDocument(
        id=document.id,
        title=document.title.strip(),
        content=document.content.strip(),
        keywords=keywords,
        category=(document.category or "general").strip(),
        source=(document.source or "ai_knowledge").strip(),
    )


def knowledge_hits_to_sources(hits: List[KnowledgeHit]) -> List[KnowledgeSource]:
    grouped: Dict[Tuple[object, object, object], Dict[str, object]] = {}
    for position, hit in enumerate(hits):
        metadata = hit.metadata or {}
        knowledge_id = metadata.get("knowledgeId")
        key = (
            knowledge_id if knowledge_id is not None else ("legacy", position),
            metadata.get("knowledgeVersion"),
            metadata.get("indexVersion"),
        )
        if key not in grouped:
            grouped[key] = {
                "metadata": metadata,
                "snippets": [],
                "chunkIndexes": set(),
                "retrievers": [],
                "fusionScore": None,
                "score": None,
                "retrieverScores": {},
                "normalizedRetrieverScores": {},
            }
        group = grouped[key]
        snippets = group["snippets"]
        if isinstance(snippets, list) and hit.content and hit.content not in snippets:
            snippets.append(hit.content)
        chunk_indexes = group["chunkIndexes"]
        raw_indexes = metadata.get("chunkIndexes")
        if not isinstance(raw_indexes, list):
            raw_indexes = [metadata.get("chunkIndex")]
        if isinstance(chunk_indexes, set):
            chunk_indexes.update(index for index in raw_indexes if isinstance(index, int))
        retrievers = group["retrievers"]
        if isinstance(retrievers, list):
            for retriever in hit.retrievers or (hit.retriever,):
                if retriever not in retrievers:
                    retrievers.append(retriever)
        if hit.fusion_score is not None:
            current_fusion = group.get("fusionScore")
            group["fusionScore"] = max(float(current_fusion or hit.fusion_score), hit.fusion_score)
        compatible_score = hit.normalized_score if hit.normalized_score is not None else hit.score
        if compatible_score is not None:
            current_score = group.get("score")
            group["score"] = max(float(current_score or compatible_score), compatible_score)
        for field_name, values in (
            ("retrieverScores", hit.retriever_scores),
            ("normalizedRetrieverScores", hit.normalized_retriever_scores),
        ):
            target = group[field_name]
            if isinstance(target, dict):
                for retriever, score in values.items():
                    target[retriever] = max(float(target.get(retriever, score)), score)

    sources = []
    for group in grouped.values():
        metadata = group["metadata"] if isinstance(group["metadata"], dict) else {}
        snippets = group["snippets"] if isinstance(group["snippets"], list) else []
        # sources 按知识去重，但片段只来自本次实际命中的受控上下文。
        snippet = "\n".join(str(item) for item in snippets)[:240]
        retrievers = group["retrievers"] if isinstance(group["retrievers"], list) else []
        sources.append(KnowledgeSource(
            type="knowledge",
            knowledgeId=metadata.get("knowledgeId"),
            title=metadata.get("title"),
            category=metadata.get("category"),
            snippet=snippet,
            score=group.get("score"),
            source=metadata.get("source") or (retrievers[0] if retrievers else None),
            knowledgeVersion=metadata.get("knowledgeVersion"),
            indexVersion=metadata.get("indexVersion"),
            chunkIndexes=sorted(group["chunkIndexes"]) if isinstance(group["chunkIndexes"], set) else [],
            retrievers=retrievers,
            fusionScore=group.get("fusionScore"),
            retrieverScores=group["retrieverScores"] if isinstance(group["retrieverScores"], dict) else {},
            normalizedRetrieverScores=(
                group["normalizedRetrieverScores"]
                if isinstance(group["normalizedRetrieverScores"], dict)
                else {}
            ),
        ))
    return sources


def filter_knowledge_hits(hits: List[KnowledgeHit], intent: Optional[str] = None) -> List[KnowledgeHit]:
    return filter_usable_hits(hits, INTENT_TOPIC_KEYWORDS.get(str(intent or ""), ()))


def matches_intent_topic(intent: Optional[str], text: str) -> bool:
    keywords = INTENT_TOPIC_KEYWORDS.get(str(intent or ""), ())
    normalized = (text or "").lower()
    return bool(keywords) and any(keyword in normalized for keyword in keywords)


def service_point_search_text(item: Dict[str, object]) -> str:
    return " ".join(str(item.get(field) or "") for field in (
        "name",
        "categoryName",
        "description",
        "area",
    ))


def service_point_matches_intent(intent: str, item: Dict[str, object]) -> bool:
    category = str(item.get("categoryName") or "").strip()
    if category:
        return matches_intent_topic(intent, category)
    return matches_intent_topic(intent, service_point_search_text(item))


def build_structured_response(state: CampusSupportState) -> CampusAssistantResponse:
    retrieval_mode = str(state.get("retrieval_mode") or "")
    business_sources = business_tool_sources(state.get("business_tool_results", []))
    knowledge_sources = [
        source
        for source in state.get("knowledge_sources", [])
        if source_type(source) == "knowledge"
    ]
    recommended_sources = (
        recommended_point_sources(state.get("recommended_service_points", []))
        if should_use_recommended_point_sources(state)
        else []
    )
    if retrieval_mode == RetrievalMode.BUSINESS_ONLY.value:
        sources = business_sources or recommended_sources
    elif retrieval_mode == RetrievalMode.DIRECT_LLM.value:
        sources = []
    elif retrieval_mode == RetrievalMode.RAG_ONLY.value:
        sources = knowledge_sources
    elif retrieval_mode == RetrievalMode.HYBRID.value:
        sources = knowledge_sources + (business_sources or recommended_sources)
    elif retrieval_mode == RetrievalMode.CLARIFY.value:
        sources = []
    else:
        sources = business_sources + knowledge_sources
        if not sources:
            sources = recommended_sources
    business_cards = (
        business_tool_cards(state.get("business_tool_results", []))
        if retrieval_mode not in {
            RetrievalMode.DIRECT_LLM.value,
            RetrievalMode.RAG_ONLY.value,
            RetrievalMode.CLARIFY.value,
        }
        else []
    )
    fallback_reason = resolve_fallback_reason(state, sources)
    sensitive_fields_denied = fallback_reason == "PERMISSION_DENIED"
    if sensitive_fields_denied:
        sources = []
        business_cards = []
    confidence = (
        float(state.get("intent_confidence") or 0.0)
        if state.get("intent_decision")
        else resolve_confidence(state, sources, fallback_reason)
    )
    suppress_business_fields = retrieval_mode in {
        RetrievalMode.DIRECT_LLM.value,
        RetrievalMode.RAG_ONLY.value,
        RetrievalMode.CLARIFY.value,
    } or sensitive_fields_denied
    action_drafts = [] if suppress_business_fields else build_action_drafts(state)
    recommended_service_points = [] if suppress_business_fields else state.get("recommended_service_points", [])
    contract = failure_contract(fallback_reason)
    return CampusAssistantResponse(
        answer=state["response"],
        intent=state["intent"],
        traceId=state.get("trace_id"),
        orchestrator=str(state.get("orchestrator") or "legacy"),
        confidence=confidence,
        needCreateTicket=False if sensitive_fields_denied else bool(state.get("escalate") or state["intent"] == "repair"),
        recommendedServicePoints=recommended_service_points,
        sources=sources,
        businessCards=business_cards,
        actionDrafts=action_drafts,
        langGraphNodes=sanitize_lang_graph_nodes(state.get("lang_graph_nodes", [])),
        executionRecords=sanitize_execution_records(state.get("execution_records", [])),
        fallbackRecords=sanitize_fallback_records(state.get("fallback_records", [])),
        fallbackReason=fallback_reason,
        serviceStage="agent",
        errorStage=contract.stage if contract else None,
        errorCode=fallback_reason,
        retriable=contract.retriable if contract else None,
        fallbackMessage=contract.fallback_message if contract else None,
        plannerMode=str(state.get("planner_mode") or state.get("generation_record", {}).get("plannerMode") or "rule"),
        retrievalMode=retrieval_mode or None,
        intentSource=str(state.get("intent_source") or "") or None,
        routingReason=str(state.get("routing_reason") or "") or None,
        lowConfidence=bool(state.get("low_confidence")),
        memoryDiagnostics=build_memory_diagnostics(state),
    )


def build_memory_diagnostics(state: CampusSupportState) -> Optional[CampusMemoryDiagnostics]:
    """诊断只暴露计数和稳定枚举，禁止携带摘要正文或候选业务 ID。"""
    memory = state.get("memory_context")
    if not isinstance(memory, dict) or memory.get("mode") != "v2":
        return None
    entities = memory.get("entities") if isinstance(memory.get("entities"), dict) else {}
    entity_types = []
    for key, entity_type in [
        ("tickets", "ticket"),
        ("appointments", "appointment"),
        ("servicePoints", "service_point"),
    ]:
        if isinstance(entities.get(key), list) and entities[key]:
            entity_types.append(entity_type)
    recent_turns = memory.get("recentTurns")
    return CampusMemoryDiagnostics(
        mode="v2",
        schemaVersion="2",
        recentTurnCount=len(recent_turns) if isinstance(recent_turns, list) else 0,
        summaryVersion=int(memory.get("summaryVersion") or 0),
        entityTypes=entity_types,
        resolutionSource=state.get("memory_resolution_source"),
        degraded=False,
        degradedReason=None,
    )


def sanitize_lang_graph_nodes(records: object) -> List[Dict[str, Any]]:
    sanitized: List[Dict[str, Any]] = []
    if not isinstance(records, list):
        return sanitized
    for index, record in enumerate(records):
        if not isinstance(record, dict):
            continue
        sanitized.append({
            "order": safe_int(record.get("order"), index + 1),
            "nodeName": string_or_none(record.get("nodeName") or record.get("node_name") or record.get("name")),
            "status": string_or_none(record.get("status")),
            "latencyMs": safe_float(first_non_empty(record.get("latencyMs"), record.get("latency_ms"))),
            "fallbackReason": string_or_none(record.get("fallbackReason") or record.get("fallback_reason")),
            "errorType": string_or_none(record.get("errorType") or record.get("error_type")),
            "errorCode": string_or_none(record.get("errorCode") or record.get("error_code")),
            "toolName": string_or_none(record.get("toolName") or record.get("tool_name")),
            "toolProtocol": string_or_none(record.get("toolProtocol") or record.get("tool_protocol")),
            "plannerMode": string_or_none(record.get("plannerMode") or record.get("planner_mode")),
            "modelName": string_or_none(record.get("modelName") or record.get("model_name")),
            "toolCallId": string_or_none(record.get("toolCallId") or record.get("tool_call_id")),
            "finishReason": string_or_none(record.get("finishReason") or record.get("finish_reason")),
            "schemaValidation": string_or_none(record.get("schemaValidation") or record.get("schema_validation")),
        })
    return sanitized


def sanitize_execution_records(records: object) -> List[Dict[str, Any]]:
    sanitized: List[Dict[str, Any]] = []
    if not isinstance(records, list):
        return sanitized
    for record in records:
        if not isinstance(record, dict):
            continue
        sanitized.append({
            "toolName": string_or_none(record.get("toolName") or record.get("tool_name")),
            "toolProtocol": string_or_none(record.get("toolProtocol") or record.get("tool_protocol") or "http_internal"),
            "success": bool(record.get("success")),
            "count": safe_int(record.get("count"), 0),
            "latencyMs": safe_float(first_non_empty(record.get("latencyMs"), record.get("latency_ms"))),
            "errorType": string_or_none(record.get("errorType") or record.get("error_type")),
            "toolCallId": string_or_none(record.get("toolCallId") or record.get("tool_call_id")),
            "schemaValidation": string_or_none(record.get("schemaValidation") or record.get("schema_validation")),
        })
    return sanitized


def sanitize_fallback_records(records: object) -> List[Dict[str, Any]]:
    sanitized: List[Dict[str, Any]] = []
    if not isinstance(records, list):
        return sanitized
    for record in records:
        if not isinstance(record, dict):
            continue
        sanitized.append({
            "reason": string_or_none(record.get("reason")),
            "stage": string_or_none(record.get("stage")),
            "detail": sanitize_fallback_detail(record.get("detail")),
        })
    return sanitized


def sanitize_fallback_detail(detail: object) -> Dict[str, Any]:
    if not isinstance(detail, dict):
        return {}
    allowed: Dict[str, Any] = {}
    for key in [
        "toolName",
        "toolProtocol",
        "errorType",
        "message",
        "generationMode",
        "component",
        "intentSource",
        "routerMode",
    ]:
        value = detail.get(key)
        if value is not None:
            allowed[key] = value
    errors = detail.get("errors")
    if isinstance(errors, list):
        allowed["errors"] = [
            {
                "nodeName": error.get("nodeName"),
                "errorType": error.get("errorType"),
            }
            for error in errors
            if isinstance(error, dict)
        ]
    return allowed


def string_or_none(value: object) -> Optional[str]:
    if value is None:
        return None
    text = str(value)
    return text if text else None


def safe_int(value: object, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def safe_float(value: object) -> Optional[float]:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def first_non_empty(*values: object) -> object:
    for value in values:
        if value is not None and value != "":
            return value
    return None


def build_action_drafts(state: CampusSupportState) -> List[Dict[str, object]]:
    drafts = []
    create_ticket = build_create_ticket_draft(state)
    if create_ticket:
        drafts.append(create_ticket)
    appointment_query = build_appointment_query_draft(state)
    if appointment_query:
        drafts.append(appointment_query)
    reply_ticket = build_reply_ticket_draft(state)
    if reply_ticket:
        drafts.append(reply_ticket)
    return drafts


def build_create_ticket_draft(state: CampusSupportState) -> Optional[Dict[str, object]]:
    if not (state.get("intent") == "repair" or state.get("escalate")):
        return None
    point = first_recommended_point(state)
    question = clean_single_line(state.get("user_input", ""))
    title = summarize_ticket_title(question)
    payload = {
        "title": title,
        "content": question,
        "attachmentHint": "\u5982\u6709\u73b0\u573a\u7167\u7247\u6216\u622a\u56fe\uff0c\u8bf7\u5728\u73b0\u6709\u5de5\u5355\u8868\u5355\u4e2d\u4e0a\u4f20\u9644\u4ef6\u3002",
    }
    if point:
        payload.update({
            "servicePointId": point.id,
            "servicePointName": point.name,
            "categoryId": getattr(point, "categoryId", None),
        })
    return {
        "type": "create_ticket_draft",
        "title": title,
        "summary": "\u5df2\u6574\u7406\u4e00\u4efd\u5de5\u5355\u8349\u7a3f\uff0c\u9700\u8981\u4f60\u5728\u5de5\u5355\u8868\u5355\u4e2d\u786e\u8ba4\u540e\u518d\u63d0\u4ea4\u3002",
        "payload": payload,
    }


def build_appointment_query_draft(state: CampusSupportState) -> Optional[Dict[str, object]]:
    if not should_build_appointment_query_draft(state):
        return None
    point = first_recommended_point(state)
    payload = {
        "dateRange": "\u8bf7\u5728\u9884\u7ea6\u529e\u7406\u9875\u9009\u62e9\u53ef\u7528\u65e5\u671f",
        "slotFilter": clean_single_line(state.get("user_input", "")),
    }
    if point:
        payload.update({
            "servicePointId": point.id,
            "servicePointName": point.name,
        })
    return {
        "type": "appointment_query_draft",
        "title": "\u9884\u7ea6\u67e5\u8be2\u8349\u7a3f",
        "summary": "\u53ef\u524d\u5f80\u9884\u7ea6\u529e\u7406\u9875\u67e5\u770b\u53ef\u7528\u65f6\u6bb5\uff0c\u6700\u7ec8\u9884\u7ea6\u9700\u8981\u4f60\u624b\u52a8\u786e\u8ba4\u3002",
        "payload": payload,
    }


def build_reply_ticket_draft(state: CampusSupportState) -> Optional[Dict[str, object]]:
    ticket = first_ticket_requiring_reply(state)
    if not ticket:
        return None
    question = clean_single_line(state.get("user_input", ""))
    content = question if reply_text_like(question) else "\u6211\u8865\u5145\u8bf4\u660e\uff1a" + question
    return {
        "type": "reply_ticket_draft",
        "title": "\u5de5\u5355\u56de\u590d\u8349\u7a3f",
        "summary": "\u8be5\u5de5\u5355\u9700\u8981\u4f60\u8865\u5145\u56de\u590d\uff0c\u8bf7\u8fdb\u5165\u5de5\u5355\u8be6\u60c5\u786e\u8ba4\u540e\u63d0\u4ea4\u3002",
        "payload": {
            "ticketId": ticket.id,
            "replyContent": content,
        },
    }


def should_build_appointment_query_draft(state: CampusSupportState) -> bool:
    text = state.get("user_input", "")
    lower_text = text.lower()
    if state.get("intent") == "service_point_slots":
        return True
    if not any(word in lower_text for word in ["appointment", "reservation", "\u9884\u7ea6"]):
        return False
    return any(word in lower_text for word in ["book", "available", "slot", "\u529e\u7406", "\u600e\u4e48", "\u5982\u4f55", "\u60f3", "\u53ef\u4ee5", "\u65f6\u6bb5", "\u4f59\u91cf", "\u67e5\u8be2", "\u8349\u7a3f", "\u6574\u7406"])


def first_recommended_point(state: CampusSupportState) -> Optional[CampusServicePoint]:
    points = state.get("recommended_service_points") or []
    return points[0] if points else None


def first_ticket_requiring_reply(state: CampusSupportState) -> Optional[CampusTicket]:
    if state.get("intent") != "ticket_status":
        return None
    text = state.get("user_input", "")
    # 是否需要补充材料属于实时事实，只能使用本轮 Java 工具结果，不能读取 Memory 旧快照。
    live_tickets = live_ticket_results(state.get("business_tool_results", []))
    if not reply_text_like(text) and not any(ticket.studentReplyRequired == 1 for ticket in live_tickets):
        return None
    for ticket in live_tickets:
        if ticket.studentReplyRequired == 1:
            return ticket
    return None


def live_ticket_results(results: object) -> List[CampusTicket]:
    tickets: List[CampusTicket] = []
    if not isinstance(results, list):
        return tickets
    for result in results:
        if (
            not isinstance(result, dict)
            or not result.get("success")
            or result.get("toolName") not in {"query_ticket_detail", "query_my_tickets"}
        ):
            continue
        data = result.get("data")
        items = data if isinstance(data, list) else [data] if isinstance(data, dict) else []
        for item in items:
            if not isinstance(item, dict):
                continue
            try:
                tickets.append(CampusTicket(**item))
            except Exception:
                continue
    return tickets


def reply_text_like(text: str) -> bool:
    lower_text = (text or "").lower()
    return any(word in lower_text for word in ["reply", "supplement", "\u56de\u590d", "\u8865\u5145", "\u8bf4\u660e"])


def clean_single_line(text: str) -> str:
    return re.sub(r"\s+", " ", text or "").strip()


def summarize_ticket_title(text: str) -> str:
    cleaned = clean_single_line(text).strip("\u3002\uff0c,.!? \t")
    if not cleaned:
        return "\u6821\u56ed\u670d\u52a1\u5de5\u5355"
    if len(cleaned) <= 24:
        return cleaned
    return cleaned[:24] + "..."


def business_tool_sources(results: List[Dict[str, object]]) -> List[KnowledgeSource]:
    result = first_successful_tool_result(results)
    if not result:
        return []
    tool_name = result.get("toolName")
    data = result.get("data")
    if tool_name == "query_inbox_summary" and isinstance(data, dict):
        items = ensure_list(data.get("latest"))
    else:
        items = data if isinstance(data, list) else [data] if isinstance(data, dict) else []
    sources = []
    for item in items[:5]:
        if not isinstance(item, dict):
            continue
        sources.append(source_from_tool_item(str(tool_name), item))
    return sources


def source_from_tool_item(tool_name: str, item: Dict[str, object]) -> KnowledgeSource:
    if tool_name in {"query_service_points", "query_service_point_slots"}:
        return KnowledgeSource(
            type="service_point",
            id=item.get("servicePointId") or item.get("id"),
            name=item.get("servicePointName") or item.get("name") or item.get("title"),
            title=item.get("title") or item.get("name"),
            address=item.get("servicePointAddress") or item.get("address"),
            openHours=item.get("openHours"),
            snippet=item.get("description"),
            startTime=item.get("startTime"),
            endTime=item.get("endTime"),
        )
    if tool_name in {"query_my_tickets", "query_ticket_detail"}:
        return KnowledgeSource(
            type="ticket",
            id=item.get("id"),
            title=item.get("title"),
            statusText=display_ticket_status(item.get("statusText"), item.get("status")),
            createTime=item.get("createTime"),
            snippet=item.get("content") or item.get("title"),
        )
    if tool_name in {"query_my_appointments", "query_appointment_detail"}:
        return KnowledgeSource(
            type="appointment",
            id=item.get("id"),
            title=item.get("servicePointName") or item.get("slotTitle"),
            slotTitle=item.get("slotTitle"),
            statusText=display_appointment_status(item.get("statusText"), item.get("status")),
            startTime=item.get("startTime"),
            endTime=item.get("endTime"),
            snippet=item.get("remark"),
        )
    if tool_name == "query_inbox_summary":
        return KnowledgeSource(
            type="inbox",
            id=item.get("messageId"),
            title=item.get("title"),
            readStatus=item.get("readStatus"),
            createTime=item.get("createTime"),
            snippet=item.get("summary") or item.get("content"),
        )
    if tool_name == "query_admin_operation_logs":
        return KnowledgeSource(type="admin_log", id=item.get("id"), module=item.get("module"), operation=item.get("operation"), createTime=item.get("createTime"))
    if tool_name == "query_admin_appointment_failure_logs":
        return KnowledgeSource(type="admin_log", id=item.get("id"), title=item.get("failureType"), createTime=item.get("createTime"), snippet=item.get("reason"))
    if tool_name == "query_service_categories":
        return KnowledgeSource(type="service_category", id=item.get("id"), title=item.get("name"), statusText=str(item.get("status")))
    if tool_name == "query_station_comments":
        return KnowledgeSource(type="service_point", id=item.get("stationId"), title="station comment", createTime=item.get("createTime"), snippet=item.get("content"))
    return KnowledgeSource(type="business", id=item.get("id"), title=item.get("title") or item.get("name"))


def recommended_point_sources(points: List[CampusServicePoint]) -> List[KnowledgeSource]:
    sources = []
    for point in points[:3]:
        sources.append(KnowledgeSource(
            type="service_point",
            id=point.id,
            name=point.name,
            title=point.name,
            address=point.address,
            openHours=point.openHours,
            snippet=point.description,
        ))
    return sources


def source_type(source: object) -> Optional[str]:
    if isinstance(source, dict):
        value = source.get("type")
    else:
        value = getattr(source, "type", None)
    return str(value) if value is not None else None


def should_use_recommended_point_sources(state: CampusSupportState) -> bool:
    return state.get("intent") in {"repair", "printing", "express", "consultation", "service_point_comment_ranking"}


def business_tool_cards(results: List[Dict[str, object]]) -> List[Dict[str, object]]:
    result = first_successful_tool_result(results)
    if not result:
        return []
    tool_name = str(result.get("toolName"))
    data = result.get("data")
    items = data if isinstance(data, list) else [data] if isinstance(data, dict) else []
    cards = []
    for item in items[:5]:
        if isinstance(item, dict):
            card = dict(item)
            card_type = card_type_for_tool(tool_name)
            card["type"] = card_type
            if card_type == "appointment":
                card["statusText"] = display_appointment_status(card.get("statusText"), card.get("status"))
            elif card_type == "ticket":
                card["statusText"] = display_ticket_status(card.get("statusText"), card.get("status"))
            cards.append(card)
    return cards


def card_type_for_tool(tool_name: str) -> str:
    if tool_name in {"query_admin_operation_logs", "query_admin_appointment_failure_logs"}:
        return "admin_log"
    if "ticket" in tool_name:
        return "ticket"
    if "appointment" in tool_name:
        return "appointment"
    if tool_name in {"query_service_points", "query_service_point_slots", "query_station_comments"}:
        return "service_point"
    if tool_name == "query_inbox_summary":
        return "inbox"
    return "business"


def resolve_fallback_reason(state: CampusSupportState, sources: List[KnowledgeSource]) -> Optional[str]:
    if state.get("retrieval_mode") == RetrievalMode.CLARIFY.value:
        classifier_failure = str(state.get("classifier_fallback_reason") or "")
        return classifier_failure if classifier_failure and classifier_failure != "LOW_CONFIDENCE" else None
    if state.get("permission_denied"):
        return "PERMISSION_DENIED"
    generation_record = state.get("generation_record")
    if isinstance(generation_record, dict) and generation_record.get("fallbackReason"):
        return str(generation_record.get("fallbackReason"))
    if state.get("retrieval_mode") == RetrievalMode.DIRECT_LLM.value:
        return None
    results = state.get("business_tool_results", [])
    if results and not first_successful_tool_result(results):
        return tool_failure_fallback_reason(results[0])
    if results:
        return None
    if not state.get("knowledge_initialized", True):
        return "KNOWLEDGE_NOT_SYNCED"
    if not sources:
        return "NO_SOURCE"
    return None


def resolve_confidence(state: CampusSupportState, sources: List[KnowledgeSource], fallback_reason: Optional[str]) -> float:
    if fallback_reason == "PERMISSION_DENIED":
        return 0.7
    if fallback_reason:
        return 0.3
    if any(source.type != "knowledge" for source in sources):
        return 0.86
    if sources:
        scores = [source.score for source in sources if source.score is not None]
        if not scores:
            return 0.72
        best = max(scores)
        if best <= 1:
            return max(0.55, min(0.92, float(best)))
        return 0.72
    if state.get("recommended_service_points"):
        return 0.65
    return 0.4
