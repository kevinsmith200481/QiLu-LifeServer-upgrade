from __future__ import annotations

import json
import logging
import os
import re
import time
from dataclasses import dataclass
from enum import Enum
from functools import lru_cache
from typing import Any, Callable, Dict, List, Optional, Tuple

from pydantic import BaseModel, ConfigDict, Field, StrictInt, ValidationError, field_validator, model_validator

from app.acceptance_faults import force_model_timeout, force_model_unavailable

try:
    from langchain_core.messages import HumanMessage, SystemMessage
    from langchain_openai import ChatOpenAI
except ImportError:  # Keeps keyword fallback usable in a lightweight runtime.
    HumanMessage = None
    SystemMessage = None
    ChatOpenAI = None

try:
    from openai import OpenAI
except ImportError:
    OpenAI = None


logger = logging.getLogger(__name__)

ROUTER_MODES = {"keyword", "semantic_shadow", "semantic"}
CONFIDENCE_THRESHOLD = 0.75


class IntentName(str, Enum):
    APPOINTMENT_STATUS = "appointment_status"
    TICKET_STATUS = "ticket_status"
    INBOX_SUMMARY = "inbox_summary"
    SERVICE_POINT_SLOTS = "service_point_slots"
    STATION_COMMENTS = "station_comments"
    SERVICE_POINT_COMMENT_RANKING = "service_point_comment_ranking"
    ADMIN_OPERATION_LOGS = "admin_operation_logs"
    ADMIN_APPOINTMENT_FAILURE_LOGS = "admin_appointment_failure_logs"
    APPOINTMENT_POLICY = "appointment_policy"
    TICKET_POLICY = "ticket_policy"
    CAMPUS_POLICY = "campus_policy"
    CASUAL_CHAT = "casual_chat"
    GENERAL = "general"
    REPAIR = "repair"
    PRINTING = "printing"
    EXPRESS = "express"
    CONSULTATION = "consultation"
    SERVICE_CATEGORIES = "service_categories"
    SERVICE_POINT_SEARCH = "service_point_search"
    AMBIGUOUS = "ambiguous"


class IntentScope(str, Enum):
    CONVERSATIONAL = "conversational"
    PUBLIC_KNOWLEDGE = "public_knowledge"
    USER_PRIVATE = "user_private"
    ADMIN_PRIVATE = "admin_private"
    MIXED = "mixed"
    AMBIGUOUS = "ambiguous"


class IntentSource(str, Enum):
    MEMORY = "memory"
    SEMANTIC_MODEL = "semantic_model"
    RULE_FALLBACK = "rule_fallback"
    CLARIFICATION = "clarification"


class RetrievalMode(str, Enum):
    DIRECT_LLM = "DIRECT_LLM"
    BUSINESS_ONLY = "BUSINESS_ONLY"
    RAG_ONLY = "RAG_ONLY"
    HYBRID = "HYBRID"
    CLARIFY = "CLARIFY"


INTENT_SCOPES: Dict[IntentName, IntentScope] = {
    IntentName.APPOINTMENT_STATUS: IntentScope.USER_PRIVATE,
    IntentName.TICKET_STATUS: IntentScope.USER_PRIVATE,
    IntentName.INBOX_SUMMARY: IntentScope.USER_PRIVATE,
    IntentName.SERVICE_POINT_SLOTS: IntentScope.USER_PRIVATE,
    IntentName.STATION_COMMENTS: IntentScope.USER_PRIVATE,
    IntentName.SERVICE_POINT_COMMENT_RANKING: IntentScope.USER_PRIVATE,
    IntentName.ADMIN_OPERATION_LOGS: IntentScope.ADMIN_PRIVATE,
    IntentName.ADMIN_APPOINTMENT_FAILURE_LOGS: IntentScope.ADMIN_PRIVATE,
    IntentName.APPOINTMENT_POLICY: IntentScope.PUBLIC_KNOWLEDGE,
    IntentName.TICKET_POLICY: IntentScope.PUBLIC_KNOWLEDGE,
    IntentName.CAMPUS_POLICY: IntentScope.PUBLIC_KNOWLEDGE,
    IntentName.CASUAL_CHAT: IntentScope.CONVERSATIONAL,
    IntentName.GENERAL: IntentScope.PUBLIC_KNOWLEDGE,
    IntentName.REPAIR: IntentScope.MIXED,
    IntentName.PRINTING: IntentScope.MIXED,
    IntentName.EXPRESS: IntentScope.MIXED,
    IntentName.CONSULTATION: IntentScope.MIXED,
    IntentName.SERVICE_CATEGORIES: IntentScope.MIXED,
    IntentName.SERVICE_POINT_SEARCH: IntentScope.MIXED,
    IntentName.AMBIGUOUS: IntentScope.AMBIGUOUS,
}

INTENT_DESCRIPTIONS: Dict[str, str] = {
    "appointment_status": "用户自己的预约记录、预约详情或当前状态",
    "ticket_status": "用户自己的工单记录、工单详情或处理进度",
    "inbox_summary": "用户自己的未读数、最近通知或站内信摘要",
    "service_point_slots": "服务点的实时预约时段、名额或容量",
    "station_comments": "指定服务点（已给出 ID 或沿用上下文）的真实留言或评论明细",
    "service_point_comment_ranking": "跨服务点统计哪些网点有留言、留言数量或留言排行",
    "admin_operation_logs": "管理端操作日志",
    "admin_appointment_failure_logs": "管理端预约失败记录",
    "appointment_policy": "预约办理、取消规则或所需材料",
    "ticket_policy": "工单办理规则、受理流程或补充材料说明",
    "campus_policy": "校园卡、开放规则和一般校园制度",
    "casual_chat": "问候、感谢、能力询问、轻量闲聊或创意请求，不需要校园业务事实、正式规则或实时数据",
    "general": "不查询个人记录的其他公开校园服务办理方法、使用说明和常见问题",
    "repair": "维修知识以及真实维修服务点",
    "printing": "打印知识以及真实打印服务点",
    "express": "快递知识以及真实快递服务点",
    "consultation": "咨询知识以及真实咨询服务点",
    "service_categories": "服务分类说明以及当前可用分类",
    "service_point_search": "未落入维修、打印、快递或就业咨询主题的其他服务说明以及真实服务点列表",
    "ambiguous": "信息不足以区分两个不同业务意图且必须由用户澄清",
}

INTENT_RETRIEVAL_MODES: Dict[IntentName, RetrievalMode] = {
    IntentName.APPOINTMENT_STATUS: RetrievalMode.BUSINESS_ONLY,
    IntentName.TICKET_STATUS: RetrievalMode.BUSINESS_ONLY,
    IntentName.INBOX_SUMMARY: RetrievalMode.BUSINESS_ONLY,
    IntentName.SERVICE_POINT_SLOTS: RetrievalMode.BUSINESS_ONLY,
    IntentName.STATION_COMMENTS: RetrievalMode.BUSINESS_ONLY,
    IntentName.SERVICE_POINT_COMMENT_RANKING: RetrievalMode.BUSINESS_ONLY,
    IntentName.ADMIN_OPERATION_LOGS: RetrievalMode.BUSINESS_ONLY,
    IntentName.ADMIN_APPOINTMENT_FAILURE_LOGS: RetrievalMode.BUSINESS_ONLY,
    IntentName.APPOINTMENT_POLICY: RetrievalMode.RAG_ONLY,
    IntentName.TICKET_POLICY: RetrievalMode.RAG_ONLY,
    IntentName.CAMPUS_POLICY: RetrievalMode.RAG_ONLY,
    IntentName.CASUAL_CHAT: RetrievalMode.DIRECT_LLM,
    IntentName.GENERAL: RetrievalMode.RAG_ONLY,
    IntentName.REPAIR: RetrievalMode.HYBRID,
    IntentName.PRINTING: RetrievalMode.HYBRID,
    IntentName.EXPRESS: RetrievalMode.HYBRID,
    IntentName.CONSULTATION: RetrievalMode.HYBRID,
    IntentName.SERVICE_CATEGORIES: RetrievalMode.HYBRID,
    IntentName.SERVICE_POINT_SEARCH: RetrievalMode.HYBRID,
    IntentName.AMBIGUOUS: RetrievalMode.CLARIFY,
}

if set(INTENT_RETRIEVAL_MODES) != set(IntentName):
    raise RuntimeError("every intent must have one explicit retrieval mode")


class IntentEntities(BaseModel):
    model_config = ConfigDict(extra="forbid")

    appointmentId: Optional[StrictInt] = Field(gt=0)
    ticketId: Optional[StrictInt] = Field(gt=0)
    servicePointId: Optional[StrictInt] = Field(gt=0)


class IntentDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")

    intent: IntentName
    confidence: float = Field(ge=0.0, le=1.0, strict=True)
    scope: IntentScope
    entities: IntentEntities
    candidateIntents: List[IntentName] = Field(min_length=1, max_length=3)
    intentSource: IntentSource

    @field_validator("candidateIntents")
    @classmethod
    def candidates_must_be_unique(cls, value: List[IntentName]) -> List[IntentName]:
        if len(set(value)) != len(value):
            raise ValueError("candidateIntents must be unique")
        return value

    @model_validator(mode="after")
    def scope_must_match_intent(self) -> "IntentDecision":
        if self.scope != INTENT_SCOPES[self.intent]:
            raise ValueError("scope does not match intent")
        return self


@dataclass(frozen=True)
class IntentClassification:
    decision: IntentDecision
    mode: str
    model_decision: Optional[IntentDecision] = None
    shadow_decision: Optional[IntentDecision] = None
    fallback_reason: Optional[str] = None
    low_confidence: bool = False
    shadow_different: bool = False
    memory_resolution_source: Optional[str] = None

    def to_dict(self) -> Dict[str, object]:
        return {
            "decision": self.decision.model_dump(mode="json"),
            "mode": self.mode,
            "modelDecision": self.model_decision.model_dump(mode="json") if self.model_decision else None,
            "shadowDecision": self.shadow_decision.model_dump(mode="json") if self.shadow_decision else None,
            "fallbackReason": self.fallback_reason,
            "lowConfidence": self.low_confidence,
            "shadowDifferent": self.shadow_different,
            "memoryResolutionSource": self.memory_resolution_source,
        }

    @classmethod
    def from_dict(cls, value: Dict[str, object]) -> "IntentClassification":
        decision = IntentDecision.model_validate(value.get("decision"))
        model_value = value.get("modelDecision")
        shadow_value = value.get("shadowDecision")
        return cls(
            decision=decision,
            mode=str(value.get("mode") or "keyword"),
            model_decision=IntentDecision.model_validate(model_value) if isinstance(model_value, dict) else None,
            shadow_decision=IntentDecision.model_validate(shadow_value) if isinstance(shadow_value, dict) else None,
            fallback_reason=str(value.get("fallbackReason")) if value.get("fallbackReason") else None,
            low_confidence=bool(value.get("lowConfidence")),
            shadow_different=bool(value.get("shadowDifferent")),
            memory_resolution_source=(
                str(value.get("memoryResolutionSource"))
                if value.get("memoryResolutionSource")
                else None
            ),
        )


@dataclass(frozen=True)
class MemoryResolution:
    decision: IntentDecision
    source: str


@dataclass(frozen=True)
class RetrievalPolicy:
    retrieval_mode: RetrievalMode
    effective_intent: IntentName
    routing_reason: str
    low_confidence: bool = False

    def to_dict(self) -> Dict[str, object]:
        return {
            "retrievalMode": self.retrieval_mode.value,
            "effectiveIntent": self.effective_intent.value,
            "routingReason": self.routing_reason,
            "lowConfidence": self.low_confidence,
        }


class IntentRouterError(ValueError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


SemanticInvoker = Callable[[Dict[str, object]], object]


def router_mode(configured: Optional[str] = None) -> str:
    value = (configured if configured is not None else os.getenv("AI_INTENT_ROUTER_MODE", "keyword")).strip().lower()
    return value if value in ROUTER_MODES else "keyword"


def intent_model_name() -> str:
    return os.getenv("AI_INTENT_MODEL") or os.getenv("AI_NATIVE_MODEL") or os.getenv("AI_MODEL", "gpt-4o-mini")


def intent_output_mode() -> str:
    value = os.getenv("AI_INTENT_OUTPUT_MODE", "json_schema").strip().lower()
    return "json_object" if value == "json_object" else "json_schema"


def intent_timeout_seconds() -> float:
    try:
        configured = float(os.getenv("AI_INTENT_TIMEOUT_SECONDS", "15"))
    except ValueError:
        return 15.0
    return configured if 0.0 < configured <= 15.0 else 15.0


def intent_max_retries() -> int:
    try:
        configured = int(os.getenv("AI_INTENT_MAX_RETRIES", "2"))
    except ValueError:
        return 2
    return max(0, min(configured, 2))


def intent_reasoning_effort() -> Optional[str]:
    value = os.getenv("AI_INTENT_REASONING_EFFORT", "").strip().lower()
    return value if value in {"none", "minimal", "low"} else None


def classify_intent(
    question: str,
    memory_summary: Optional[Dict[str, object]] = None,
    role: Optional[str] = None,
    scene: Optional[str] = None,
    mode: Optional[str] = None,
    semantic_invoker: Optional[SemanticInvoker] = None,
) -> IntentClassification:
    selected_mode = router_mode(mode)
    memory = memory_summary if isinstance(memory_summary, dict) else {}
    memory_resolution = resolve_memory_reference_result(question, memory)
    if memory_resolution is not None:
        result = IntentClassification(
            decision=memory_resolution.decision,
            mode=selected_mode,
            memory_resolution_source=memory_resolution.source,
        )
        _log_result(result, question, role, scene)
        return result

    keyword_decision = (
        legacy_keyword_decision(question)
        if selected_mode in {"keyword", "semantic_shadow"}
        else None
    )
    if selected_mode == "keyword":
        assert keyword_decision is not None
        result = IntentClassification(decision=keyword_decision, mode=selected_mode)
        _log_result(result, question, role, scene)
        return result

    payload = build_model_payload(question, memory, role, scene)
    model_decision: Optional[IntentDecision] = None
    fallback_reason: Optional[str] = None
    low_confidence = False
    try:
        raw_decision = (semantic_invoker or invoke_semantic_model)(payload)
        model_decision = parse_semantic_decision(raw_decision)
        semantic_decision = model_decision
        if model_decision.confidence < CONFIDENCE_THRESHOLD:
            low_confidence = True
            fallback_reason = "LOW_CONFIDENCE"
            semantic_decision = low_confidence_semantic_decision(question, memory, model_decision)
    except Exception as exc:
        fallback_reason = error_code(exc)
        semantic_decision = model_failure_decision()
        logger.warning(
            "intent semantic fallback errorCode=%s errorType=%s questionLength=%s",
            fallback_reason,
            type(exc).__name__,
            len(question or ""),
        )

    if selected_mode == "semantic_shadow":
        assert keyword_decision is not None
        different = _decision_signature(keyword_decision) != _decision_signature(semantic_decision)
        result = IntentClassification(
            decision=keyword_decision,
            mode=selected_mode,
            model_decision=model_decision,
            shadow_decision=semantic_decision,
            fallback_reason=fallback_reason,
            low_confidence=low_confidence,
            shadow_different=different,
        )
    else:
        result = IntentClassification(
            decision=semantic_decision,
            mode=selected_mode,
            model_decision=model_decision,
            fallback_reason=fallback_reason,
            low_confidence=low_confidence,
        )
    _log_result(result, question, role, scene)
    return result


def select_retrieval_policy(classification: IntentClassification) -> RetrievalPolicy:
    decision = classification.decision
    if decision.intent != IntentName.AMBIGUOUS:
        return RetrievalPolicy(
            retrieval_mode=INTENT_RETRIEVAL_MODES[decision.intent],
            effective_intent=decision.intent,
            routing_reason="intent_policy:%s" % decision.intentSource.value,
            low_confidence=classification.low_confidence,
        )

    model_decision = classification.model_decision
    if classification.low_confidence and model_decision is not None:
        candidate_modes = {
            INTENT_RETRIEVAL_MODES[candidate]
            for candidate in model_decision.candidateIntents
        }
        if len(candidate_modes) == 1:
            mode = next(iter(candidate_modes))
            effective_intent = model_decision.intent
            if INTENT_RETRIEVAL_MODES[effective_intent] != mode:
                effective_intent = model_decision.candidateIntents[0]
            return RetrievalPolicy(
                retrieval_mode=mode,
                effective_intent=effective_intent,
                routing_reason="low_confidence_candidates_same_mode",
                low_confidence=True,
            )
        return RetrievalPolicy(
            retrieval_mode=RetrievalMode.CLARIFY,
            effective_intent=IntentName.AMBIGUOUS,
            routing_reason="low_confidence_candidate_mode_conflict",
            low_confidence=True,
        )

    return RetrievalPolicy(
        retrieval_mode=RetrievalMode.CLARIFY,
        effective_intent=IntentName.AMBIGUOUS,
        routing_reason="ambiguous_intent",
        low_confidence=classification.low_confidence,
    )


def build_model_payload(
    question: str,
    memory_summary: Dict[str, object],
    role: Optional[str],
    scene: Optional[str],
) -> Dict[str, object]:
    return {
        "question": question,
        "memorySummary": sanitized_memory(memory_summary),
        "role": _trusted_role(role),
        "scene": str(scene or "campus_assistant")[:64],
        "supportedIntents": INTENT_DESCRIPTIONS,
        "supportedIntentScopes": {
            intent.value: INTENT_SCOPES[intent].value
            for intent in IntentName
        },
    }


def invoke_semantic_model(payload: Dict[str, object]) -> object:
    if force_model_timeout():
        raise TimeoutError("ACCEPTANCE_MODEL_TIMEOUT")
    if force_model_unavailable():
        raise ConnectionError("ACCEPTANCE_MODEL_UNAVAILABLE")
    return invoke_semantic_model_with_budget(payload, intent_timeout_seconds())


def invoke_semantic_model_with_budget(payload: Dict[str, object], timeout_seconds: float) -> object:
    attempt_timeout = max(0.1, float(timeout_seconds))
    max_attempts = intent_max_retries() + 1
    for attempt in range(1, max_attempts + 1):
        started_at = time.monotonic()
        try:
            return invoke_semantic_model_once(payload, attempt_timeout)
        except Exception as exc:
            if attempt >= max_attempts or not retryable_intent_model_error(exc):
                raise
            logger.warning(
                "intent model retry completedAttempt=%s nextAttempt=%s maxAttempts=%s "
                "attemptTimeoutSeconds=%.3f elapsedMs=%.2f errorType=%s",
                attempt,
                attempt + 1,
                max_attempts,
                attempt_timeout,
                (time.monotonic() - started_at) * 1000.0,
                type(exc).__name__,
            )
    raise TimeoutError("INTENT_CLASSIFICATION_ATTEMPTS_EXHAUSTED")


def invoke_semantic_model_once(payload: Dict[str, object], timeout_seconds: float) -> object:
    if intent_output_mode() == "json_object":
        return invoke_json_object_model(payload, timeout_seconds)
    if ChatOpenAI is None or HumanMessage is None or SystemMessage is None:
        raise IntentRouterError("MODEL_STRUCTURED_OUTPUT_UNSUPPORTED")
    if not os.getenv("OPENAI_API_KEY"):
        raise IntentRouterError("MODEL_UNAVAILABLE")
    structured_model = _structured_intent_model(
        intent_model_name(),
        os.getenv("OPENAI_BASE_URL") or os.getenv("OPENAI_API_BASE") or "",
        max(0.1, float(timeout_seconds)),
        intent_reasoning_effort(),
    )
    return structured_model.invoke(
        [
            SystemMessage(content=_intent_system_prompt()),
            HumanMessage(content=json.dumps(payload, ensure_ascii=False, separators=(",", ":"))),
        ]
    )


def invoke_json_object_model(payload: Dict[str, object], timeout_seconds: float) -> object:
    if OpenAI is None:
        raise IntentRouterError("MODEL_STRUCTURED_OUTPUT_UNSUPPORTED")
    if not os.getenv("OPENAI_API_KEY"):
        raise IntentRouterError("MODEL_UNAVAILABLE")
    client = _json_intent_client(
        os.getenv("OPENAI_BASE_URL") or os.getenv("OPENAI_API_BASE") or "",
        max(0.1, float(timeout_seconds)),
    )
    response = client.chat.completions.create(
        model=intent_model_name(),
        temperature=0.0,
        response_format={"type": "json_object"},
        messages=[
            {"role": "system", "content": _json_object_system_prompt()},
            {
                "role": "user",
                "content": "json\n" + json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
            },
        ],
    )
    if not response.choices:
        raise IntentRouterError("MODEL_RESPONSE_INVALID")
    content = response.choices[0].message.content
    if not isinstance(content, str) or not content.strip():
        raise IntentRouterError("MODEL_RESPONSE_INVALID")
    try:
        return json.loads(content)
    except json.JSONDecodeError as exc:
        raise IntentRouterError("MODEL_RESPONSE_INVALID") from exc


@lru_cache(maxsize=8)
def _json_intent_client(base_url: str, timeout_seconds: float):
    return OpenAI(
        api_key=os.getenv("OPENAI_API_KEY"),
        base_url=base_url or None,
        timeout=timeout_seconds,
        max_retries=0,
    )


@lru_cache(maxsize=8)
def _structured_intent_model(
    model_name: str,
    base_url: str,
    timeout_seconds: float,
    reasoning_effort: Optional[str],
):
    model_args = dict(
        model=model_name,
        temperature=0.0,
        base_url=base_url or None,
        timeout=timeout_seconds,
        max_retries=0,
    )
    if reasoning_effort:
        model_args["reasoning_effort"] = reasoning_effort
    model = ChatOpenAI(**model_args)
    return model.with_structured_output(IntentDecision, method="json_schema", strict=True)


def retryable_intent_model_error(exc: Exception) -> bool:
    if isinstance(exc, (TimeoutError, ConnectionError)):
        return True
    if isinstance(exc, IntentRouterError):
        return False
    error_type = type(exc).__name__.lower()
    return any(token in error_type for token in (
        "timeout",
        "connection",
        "ratelimit",
        "internalserver",
        "serviceunavailable",
    ))


def warm_semantic_model_client() -> None:
    if router_mode() not in {"semantic", "semantic_shadow"}:
        return
    if not os.getenv("OPENAI_API_KEY"):
        return
    if intent_output_mode() == "json_object":
        if OpenAI is not None:
            _json_intent_client(
                os.getenv("OPENAI_BASE_URL") or os.getenv("OPENAI_API_BASE") or "",
                intent_timeout_seconds(),
            )
        return
    if ChatOpenAI is None:
        return
    _structured_intent_model(
        intent_model_name(),
        os.getenv("OPENAI_BASE_URL") or os.getenv("OPENAI_API_BASE") or "",
        intent_timeout_seconds(),
        intent_reasoning_effort(),
    )


def _intent_system_prompt() -> str:
    return (
        "你是校园服务语义意图分类器，只输出给定 JSON Schema。"
        "区分用户自己的记录或当前状态，与办理规则、材料和操作方法。"
        "优先使用脱敏 Memory 中已确认的业务实体完成指代消解。"
        "scope 必须使用输入中 supportedIntentScopes 对应 intent 的固定值。"
        "repair、printing、express、consultation 是完整的单一意图，各自同时覆盖同主题的办理知识与真实服务地点；"
        "主题明确时，即使问题同时询问规则、材料和去哪里办理，也必须选择对应主题意图，不得使用 ambiguous 或 service_point_search。"
        "询问指定服务点的留言明细使用 station_comments；询问哪些服务点有留言、留言数量或跨服务点排行使用 service_point_comment_ranking。"
        "问候、感谢、能力询问、轻量闲聊或创意请求使用 casual_chat；casual_chat 不得包含需要校园知识、正式规则、办理流程、个人记录、服务点或实时数据的问题。"
        "询问公开办理方法、使用说明或常见问题时，不得仅因没有个人业务实体而使用 ambiguous；没有更具体主题时使用 general。"
        "询问平台内部实现、工程报告、系统提示、token 或索引设计时使用 general 进入受控知识检索，不得使用 casual_chat。"
        "跨不同数据源且信息不足时使用 ambiguous，并列出最多三个候选意图。"
        "不能仅因一个受支持意图自身需要多类数据源而使用 ambiguous。"
        "不得推断权限，不得输出检索模式、工具名或答案。"
        "intentSource 必须是 semantic_model。"
    )


def _json_object_system_prompt() -> str:
    return (
        _intent_system_prompt()
        + ' 仅返回一个 json 对象，结构必须严格为：'
        + '{"intent":"受支持意图","confidence":0.0,"scope":"固定scope",'
        + '"entities":{"appointmentId":null,"ticketId":null,"servicePointId":null},'
        + '"candidateIntents":["受支持意图"],"intentSource":"semantic_model"}。'
        + "没有实体 ID 时三个 entities 字段也必须存在并使用 null；不得增加其他字段。"
    )


def parse_semantic_decision(raw_decision: object) -> IntentDecision:
    if isinstance(raw_decision, IntentDecision):
        decision = raw_decision
    elif isinstance(raw_decision, dict):
        try:
            decision = IntentDecision.model_validate(raw_decision)
        except ValidationError as exc:
            if _contains_unknown_intent(raw_decision):
                raise IntentRouterError("UNKNOWN_INTENT") from exc
            raise IntentRouterError("SCHEMA_INVALID") from exc
    else:
        raise IntentRouterError("MODEL_RESPONSE_INVALID")
    if decision.intentSource != IntentSource.SEMANTIC_MODEL:
        raise IntentRouterError("SCHEMA_INVALID")
    return decision


def low_confidence_semantic_decision(
    question: str,
    memory_summary: Dict[str, object],
    model_decision: IntentDecision,
) -> IntentDecision:
    memory_decision = resolve_memory_reference(question, memory_summary, allow_generic_followup=True)
    if memory_decision is not None:
        return memory_decision
    candidates = list(model_decision.candidateIntents)[:3] or [model_decision.intent]
    candidate_modes = {INTENT_RETRIEVAL_MODES[candidate] for candidate in candidates}
    if len(candidate_modes) == 1:
        return model_decision
    return _decision(
        IntentName.AMBIGUOUS,
        IntentSource.CLARIFICATION,
        confidence=model_decision.confidence,
        candidates=candidates,
    )


def model_failure_decision() -> IntentDecision:
    return _decision(
        IntentName.AMBIGUOUS,
        IntentSource.CLARIFICATION,
        confidence=0.0,
        candidates=[IntentName.GENERAL],
    )


def resolve_memory_reference(
    question: str,
    memory_summary: Dict[str, object],
    allow_generic_followup: bool = False,
) -> Optional[IntentDecision]:
    resolution = resolve_memory_reference_result(
        question,
        memory_summary,
        allow_generic_followup=allow_generic_followup,
    )
    return resolution.decision if resolution is not None else None


def resolve_memory_reference_result(
    question: str,
    memory_summary: Dict[str, object],
    allow_generic_followup: bool = False,
) -> Optional[MemoryResolution]:
    """按显式 ID、唯一候选、最近轮次和多候选澄清的固定顺序解析指代。"""
    text = (question or "").lower()
    candidates = {
        "ticket": _memory_candidates(memory_summary, "tickets", "lastTicket", "ticketId"),
        "appointment": _memory_candidates(
            memory_summary,
            "appointments",
            "lastAppointment",
            "appointmentId",
        ),
        "service_point": _memory_candidates(
            memory_summary,
            "servicePoints",
            "lastServicePoint",
            "servicePointId",
        ),
    }
    explicit_id = _first_positive_id(question)
    explicit_kind = _explicit_entity_kind(text)
    if explicit_id is not None and explicit_kind is not None:
        return MemoryResolution(
            _decision_for_entity(explicit_kind, explicit_id, IntentSource.RULE_FALLBACK, question),
            "explicit_id",
        )
    if explicit_id is not None:
        matching_kinds = [
            kind
            for kind, items in candidates.items()
            if any(item["id"] == explicit_id for item in items)
        ]
        if len(matching_kinds) == 1 and _has_generic_reference(text):
            return MemoryResolution(
                _decision_for_entity(matching_kinds[0], explicit_id, IntentSource.MEMORY, question),
                "explicit_id",
            )

    referenced_kinds = [kind for kind in candidates if _references_entity_kind(text, kind)]
    if len(referenced_kinds) == 1:
        kind = referenced_kinds[0]
        items = candidates[kind]
        if len(items) == 1:
            return MemoryResolution(
                _decision_for_entity(kind, items[0]["id"], IntentSource.MEMORY, question),
                "memory_unique_entity",
            )
        if len(items) > 1:
            recent = (
                _recent_turn_candidate(memory_summary, [kind], candidates)
                if _requests_recent_candidate(text) and not _forces_multiple_choice(text)
                else None
            )
            if recent is not None:
                return MemoryResolution(
                    _decision_for_entity(recent[0], recent[1], IntentSource.MEMORY, question),
                    "memory_recent_turn",
                )
            return MemoryResolution(_ambiguous_entities([kind]), "memory_multiple_candidates")
        return None

    if not _has_generic_reference(text):
        return None
    available = [
        (kind, item["id"])
        for kind, items in candidates.items()
        for item in items
    ]
    if len(available) == 1:
        return MemoryResolution(
            _decision_for_entity(available[0][0], available[0][1], IntentSource.MEMORY, question),
            "memory_unique_entity",
        )
    if len(available) > 1:
        populated_kinds = [kind for kind, items in candidates.items() if items]
        recent = (
            _recent_turn_candidate(memory_summary, populated_kinds, candidates)
            if _requests_recent_candidate(text) and not _forces_multiple_choice(text)
            else None
        )
        if recent is not None:
            return MemoryResolution(
                _decision_for_entity(recent[0], recent[1], IntentSource.MEMORY, question),
                "memory_recent_turn",
            )
        return MemoryResolution(
            _ambiguous_entities(populated_kinds),
            "memory_multiple_candidates",
        )
    return None


def rule_fallback_decision(question: str, memory_summary: Optional[Dict[str, object]] = None) -> IntentDecision:
    memory = memory_summary if isinstance(memory_summary, dict) else {}
    memory_decision = resolve_memory_reference(question, memory, allow_generic_followup=True)
    if memory_decision is not None:
        return memory_decision
    text = (question or "").lower()

    if _contains_any(text, ["operation log", "admin log", "操作日志", "后台日志"]):
        return _decision(IntentName.ADMIN_OPERATION_LOGS, IntentSource.RULE_FALLBACK)
    if _contains_any(text, ["failure log", "appointment failure", "预约失败", "失败原因"]):
        return _decision(IntentName.ADMIN_APPOINTMENT_FAILURE_LOGS, IntentSource.RULE_FALLBACK)
    if _contains_any(text, ["inbox", "notification", "unread", "收件箱", "通知", "未读"]):
        return _decision(IntentName.INBOX_SUMMARY, IntentSource.RULE_FALLBACK)

    if _contains_any(text, ["appointment", "reservation", "预约"]):
        if _contains_any(text, ["需要什么材料", "准备什么材料", "取消规则", "怎么取消", "如何取消", "如何预约", "怎么预约", "办理规则", "预约流程"]):
            return _decision(IntentName.APPOINTMENT_POLICY, IntentSource.RULE_FALLBACK)
        if _contains_any(text, ["我的预约", "预约记录", "预约状态", "预约详情", "预约进度", "号预约", "预约单"]):
            return _decision_from_question(IntentName.APPOINTMENT_STATUS, question, IntentSource.RULE_FALLBACK)
        return _ambiguous(IntentName.APPOINTMENT_STATUS, IntentName.APPOINTMENT_POLICY)

    if _contains_any(text, ["ticket", "工单"]):
        if _contains_any(text, ["受理多久", "多久受理", "补什么材料", "补充材料", "办理规则", "工单流程", "处理流程"]):
            return _decision(IntentName.TICKET_POLICY, IntentSource.RULE_FALLBACK)
        if _contains_any(text, ["我的工单", "工单状态", "工单详情", "工单进度", "号工单"]):
            return _decision_from_question(IntentName.TICKET_STATUS, question, IntentSource.RULE_FALLBACK)
        return _ambiguous(IntentName.TICKET_STATUS, IntentName.TICKET_POLICY)

    if _contains_any(text, ["slot", "quota", "capacity", "时段", "余量", "名额", "容量"]):
        return _decision_from_question(IntentName.SERVICE_POINT_SLOTS, question, IntentSource.RULE_FALLBACK)
    if _is_comment_aggregate_query(question):
        return _decision(IntentName.SERVICE_POINT_COMMENT_RANKING, IntentSource.RULE_FALLBACK)
    if _contains_any(text, ["comment", "留言", "评论"]):
        return _decision_from_question(IntentName.STATION_COMMENTS, question, IntentSource.RULE_FALLBACK)
    if _contains_any(text, ["repair", "fix", "broken", "leak", "维修", "报修", "漏水", "故障"]):
        return _decision(IntentName.REPAIR, IntentSource.RULE_FALLBACK)
    if _contains_any(text, ["print", "printer", "copy", "打印", "复印", "打印机"]):
        return _decision(IntentName.PRINTING, IntentSource.RULE_FALLBACK)
    if _contains_any(text, ["express", "parcel", "package", "快递", "包裹", "取件"]):
        return _decision(IntentName.EXPRESS, IntentSource.RULE_FALLBACK)
    if _contains_any(text, ["career", "resume", "job", "interview", "就业", "简历", "面试", "咨询"]):
        return _decision(IntentName.CONSULTATION, IntentSource.RULE_FALLBACK)
    if _contains_any(text, ["category", "categories", "服务分类", "服务类别"]):
        return _decision(IntentName.SERVICE_CATEGORIES, IntentSource.RULE_FALLBACK)
    if _contains_any(text, ["service point", "服务点", "哪里办理", "去哪办理"]):
        return _decision(IntentName.SERVICE_POINT_SEARCH, IntentSource.RULE_FALLBACK)
    if _contains_any(text, ["校园卡", "开放规则", "校园制度"]):
        return _decision(IntentName.CAMPUS_POLICY, IntentSource.RULE_FALLBACK)
    if text.strip() in {"你好", "您好", "hello", "hi"}:
        return _decision(IntentName.GENERAL, IntentSource.RULE_FALLBACK)
    return _ambiguous(IntentName.AMBIGUOUS)


def legacy_keyword_decision(question: str) -> IntentDecision:
    text = (question or "").lower()
    if _is_comment_aggregate_query(question):
        return _decision(IntentName.SERVICE_POINT_COMMENT_RANKING, IntentSource.RULE_FALLBACK)
    ordered_rules: List[Tuple[IntentName, List[str]]] = [
        (IntentName.ADMIN_OPERATION_LOGS, ["operation log", "admin log", "操作日志"]),
        (IntentName.ADMIN_APPOINTMENT_FAILURE_LOGS, ["failure log", "appointment failure", "预约失败", "失败原因"]),
        (IntentName.INBOX_SUMMARY, ["inbox", "notification", "unread", "收件箱", "通知", "未读"]),
        (IntentName.SERVICE_POINT_SLOTS, ["slot", "quota", "capacity", "时段", "余量", "容量"]),
        (IntentName.SERVICE_CATEGORIES, ["category", "categories", "分类", "类别"]),
        (IntentName.STATION_COMMENTS, ["comment", "留言", "评论"]),
        (IntentName.REPAIR, ["repair", "fix", "broken", "leak", "dorm", "complaint", "维修", "报修", "宿舍", "漏水", "故障", "投诉"]),
        (IntentName.PRINTING, ["print", "printer", "copy", "打印", "复印", "打印机", "读卡"]),
        (IntentName.EXPRESS, ["express", "parcel", "package", "快递", "包裹", "取件"]),
        (IntentName.CONSULTATION, ["career", "resume", "job", "interview", "就业", "简历", "面试", "咨询"]),
        (IntentName.APPOINTMENT_STATUS, ["appointment", "reservation", "预约", "预约单"]),
        (IntentName.TICKET_STATUS, ["ticket", "status", "progress", "工单", "进度", "状态"]),
    ]
    for intent, keywords in ordered_rules:
        if _contains_any(text, keywords):
            return _decision_from_question(intent, question, IntentSource.RULE_FALLBACK)
    return _decision(IntentName.GENERAL, IntentSource.RULE_FALLBACK)


def sanitized_memory(memory_summary: Dict[str, object]) -> Dict[str, object]:
    context = memory_summary.get("businessContext", {})
    context = context if isinstance(context, dict) else {}
    recent_turns = memory_summary.get("recentTurns", [])
    recent_intents: List[str] = []
    if isinstance(recent_turns, list):
        supported = set(INTENT_DESCRIPTIONS)
        for turn in recent_turns[-3:]:
            if isinstance(turn, dict) and str(turn.get("intent") or "") in supported:
                recent_intents.append(str(turn["intent"]))
    return {
        "recentIntents": recent_intents,
        "entities": {
            "ticketId": _snapshot_id(context.get("lastTicket"), "ticketId"),
            "appointmentId": _snapshot_id(context.get("lastAppointment"), "appointmentId"),
            "servicePointId": _snapshot_id(context.get("lastServicePoint"), "servicePointId"),
        },
    }


def error_code(exc: Exception) -> str:
    if isinstance(exc, IntentRouterError):
        return exc.code
    if isinstance(exc, TimeoutError) or "timeout" in type(exc).__name__.lower():
        return "MODEL_TIMEOUT"
    if isinstance(exc, (json.JSONDecodeError, TypeError)):
        return "MODEL_RESPONSE_INVALID"
    if isinstance(exc, ValidationError):
        locations = {str(item) for error in exc.errors() for item in error.get("loc", ())}
        return "UNKNOWN_INTENT" if "intent" in locations or "candidateIntents" in locations else "SCHEMA_INVALID"
    message = str(exc).upper()
    if "STRUCTURED" in message or "JSON_SCHEMA" in message:
        return "MODEL_STRUCTURED_OUTPUT_UNSUPPORTED"
    if "TIMEOUT" in message:
        return "MODEL_TIMEOUT"
    return "MODEL_UNAVAILABLE"


def _decision(
    intent: IntentName,
    source: IntentSource,
    confidence: float = 1.0,
    candidates: Optional[List[IntentName]] = None,
    appointment_id: Optional[int] = None,
    ticket_id: Optional[int] = None,
    service_point_id: Optional[int] = None,
) -> IntentDecision:
    return IntentDecision(
        intent=intent,
        confidence=float(confidence),
        scope=INTENT_SCOPES[intent],
        entities=IntentEntities(
            appointmentId=appointment_id,
            ticketId=ticket_id,
            servicePointId=service_point_id,
        ),
        candidateIntents=candidates or [intent],
        intentSource=source,
    )


def _decision_from_question(intent: IntentName, question: str, source: IntentSource) -> IntentDecision:
    entity_id = _first_positive_id(question)
    if intent == IntentName.APPOINTMENT_STATUS:
        return _decision(intent, source, appointment_id=entity_id)
    if intent == IntentName.TICKET_STATUS:
        return _decision(intent, source, ticket_id=entity_id)
    if intent in {IntentName.SERVICE_POINT_SLOTS, IntentName.STATION_COMMENTS}:
        return _decision(intent, source, service_point_id=entity_id)
    return _decision(intent, source)


def _ambiguous(*candidates: IntentName) -> IntentDecision:
    normalized = list(candidates)[:3] or [IntentName.AMBIGUOUS]
    return _decision(
        IntentName.AMBIGUOUS,
        IntentSource.CLARIFICATION,
        confidence=0.0,
        candidates=normalized,
    )


def _decision_for_entity(
    kind: str,
    entity_id: int,
    source: IntentSource,
    question: str = "",
) -> IntentDecision:
    if kind == "ticket":
        return _decision(IntentName.TICKET_STATUS, source, ticket_id=entity_id)
    if kind == "appointment":
        return _decision(IntentName.APPOINTMENT_STATUS, source, appointment_id=entity_id)
    if _asks_service_point_information(question):
        return _decision(IntentName.SERVICE_POINT_SEARCH, source, service_point_id=entity_id)
    return _decision(IntentName.SERVICE_POINT_SLOTS, source, service_point_id=entity_id)


def _asks_service_point_information(question: str) -> bool:
    text = (question or "").lower()
    return _contains_any(text, [
        "open", "close", "opening hours", "address", "where is",
        "开门", "关门", "营业时间", "几点", "地址", "在哪里", "在哪儿",
    ])


def _ambiguous_entities(kinds: List[str]) -> IntentDecision:
    intent_by_kind = {
        "ticket": IntentName.TICKET_STATUS,
        "appointment": IntentName.APPOINTMENT_STATUS,
        "service_point": IntentName.SERVICE_POINT_SLOTS,
    }
    candidates = []
    for kind in kinds:
        intent = intent_by_kind.get(kind)
        if intent is not None and intent not in candidates:
            candidates.append(intent)
    return _ambiguous(*(candidates or [IntentName.AMBIGUOUS]))


def _memory_candidates(
    memory: Dict[str, object],
    plural_key: str,
    snapshot_key: str,
    explicit_key: str,
) -> List[Dict[str, object]]:
    entities = memory.get("entities")
    raw_candidates = entities.get(plural_key) if isinstance(entities, dict) else None
    candidates: List[Dict[str, object]] = []
    seen = set()
    if isinstance(raw_candidates, list):
        for item in raw_candidates:
            if not isinstance(item, dict):
                continue
            item_id = _snapshot_id(item, explicit_key)
            if item_id is None or item_id in seen:
                continue
            seen.add(item_id)
            candidates.append({
                "id": item_id,
                "lastSeenTurnId": str(item.get("lastSeenTurnId")) if item.get("lastSeenTurnId") else None,
                "lastSeenMessageId": item.get("lastSeenMessageId"),
            })
    if candidates:
        return candidates
    context = memory.get("businessContext")
    snapshot = context.get(snapshot_key) if isinstance(context, dict) else None
    snapshot_id = _snapshot_id(snapshot, explicit_key)
    return [{"id": snapshot_id, "lastSeenTurnId": None, "lastSeenMessageId": 0}] if snapshot_id else []


def _explicit_entity_kind(text: str) -> Optional[str]:
    matches = [
        kind
        for kind, terms in {
            "ticket": ["ticket", "工单"],
            "appointment": ["appointment", "reservation", "预约"],
            "service_point": ["service point", "station", "服务点", "网点"],
        }.items()
        if _contains_any(text, terms)
    ]
    return matches[0] if len(matches) == 1 else None


def _references_entity_kind(text: str, kind: str) -> bool:
    specific = {
        "ticket": ["that ticket", "last ticket", "刚才那个工单", "那个工单", "这个工单", "工单呢"],
        "appointment": ["that appointment", "last appointment", "那个预约", "这个预约", "预约呢"],
        "service_point": ["that service point", "last service point", "那个服务点", "这个服务点", "那里还有名额", "那个地方"],
    }
    if _contains_any(text, specific[kind]):
        return True
    return _explicit_entity_kind(text) == kind and _has_generic_reference(text)


def _has_generic_reference(text: str) -> bool:
    return _contains_any(text, [
        "that",
        "this",
        "one of",
        "last",
        "previous",
        "这个",
        "那个",
        "其中",
        "它",
        "刚才",
        "上一个",
        "前一个",
        "第一个",
        "最早提到",
        "后来",
        "进度",
        "怎么样",
        "呢",
    ])


def _forces_multiple_choice(text: str) -> bool:
    return _contains_any(text, ["one of", "either one", "其中一个", "随便一个", "任意一个"])


def _requests_recent_candidate(text: str) -> bool:
    return _contains_any(text, ["last", "previous", "刚才", "上一个", "前一个", "最近那个"])


def _recent_turn_candidate(
    memory: Dict[str, object],
    kinds: List[str],
    candidates: Dict[str, List[Dict[str, object]]],
) -> Optional[Tuple[str, int]]:
    recent_turns = memory.get("recentTurns")
    if not isinstance(recent_turns, list):
        return None
    kind_by_intent = {
        "ticket_status": "ticket",
        "appointment_status": "appointment",
        "service_point_slots": "service_point",
        "station_comments": "service_point",
    }
    for turn in reversed(recent_turns):
        if not isinstance(turn, dict):
            continue
        kind = kind_by_intent.get(str(turn.get("intent") or ""))
        if kind not in kinds or not turn.get("turnId"):
            continue
        matches = [
            item
            for item in candidates.get(kind, [])
            if item.get("lastSeenTurnId") == str(turn.get("turnId"))
        ]
        if len(matches) == 1:
            return kind, int(matches[0]["id"])
        if matches:
            return None
    return None


def _snapshot_id(value: object, explicit_key: str) -> Optional[int]:
    if not isinstance(value, dict):
        return None
    item_id = value.get(explicit_key) or value.get("id") or value.get("servicePointId")
    if isinstance(item_id, bool):
        return None
    if isinstance(item_id, int) and item_id > 0:
        return item_id
    if isinstance(item_id, str) and item_id.isdigit() and int(item_id) > 0:
        return int(item_id)
    return None


def _first_positive_id(text: str) -> Optional[int]:
    match = re.search(r"\d+", text or "")
    if not match:
        return None
    value = int(match.group(0))
    return value if value > 0 else None


def _contains_any(text: str, keywords: List[str]) -> bool:
    return any(keyword in text for keyword in keywords)


def _is_comment_aggregate_query(question: str) -> bool:
    text = (question or "").lower()
    if _first_positive_id(question) is not None:
        return False
    if not _contains_any(text, ["comment", "留言", "评论"]):
        return False
    aggregate_terms = [
        "most comments",
        "comment count",
        "comment ranking",
        "which station",
        "which service point",
        "哪个网点",
        "哪些网点",
        "哪个服务点",
        "哪些服务点",
        "留言最多",
        "评论最多",
        "留言数",
        "评论数",
        "留言数量",
        "评论数量",
        "留言排行",
        "评论排行",
        "留言排名",
        "评论排名",
    ]
    has_service_point_scope = _contains_any(text, ["station", "service point", "网点", "服务点"])
    asks_if_any = _contains_any(text, ["has comments", "have comments", "有留言", "有评论"])
    return _contains_any(text, aggregate_terms) or (has_service_point_scope and asks_if_any)


def _contains_unknown_intent(raw_decision: Dict[str, object]) -> bool:
    allowed = set(INTENT_DESCRIPTIONS)
    intent = raw_decision.get("intent")
    if not isinstance(intent, str) or intent not in allowed:
        return True
    candidates = raw_decision.get("candidateIntents")
    return isinstance(candidates, list) and any(not isinstance(item, str) or item not in allowed for item in candidates)


def _trusted_role(role: Optional[str]) -> str:
    normalized = str(role or "student").strip().lower()
    return normalized if normalized in {"student", "manager", "admin"} else "student"


def _decision_signature(decision: IntentDecision) -> Tuple[str, str]:
    return decision.intent.value, decision.scope.value


def confidence_bucket(confidence: float) -> str:
    if confidence >= 0.9:
        return "high"
    if confidence >= CONFIDENCE_THRESHOLD:
        return "accepted"
    return "low"


def _log_result(
    result: IntentClassification,
    question: str,
    role: Optional[str],
    scene: Optional[str],
) -> None:
    logger.info(
        "intent router completed mode=%s intent=%s source=%s confidenceBucket=%s lowConfidence=%s "
        "fallbackReason=%s shadowDifferent=%s questionLength=%s role=%s sceneLength=%s",
        result.mode,
        result.decision.intent.value,
        result.decision.intentSource.value,
        confidence_bucket(result.decision.confidence),
        result.low_confidence,
        result.fallback_reason,
        result.shadow_different,
        len(question or ""),
        _trusted_role(role),
        len(str(scene or "campus_assistant")),
    )
