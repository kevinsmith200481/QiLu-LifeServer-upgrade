from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Dict, List, Optional, Tuple


MAX_ENTITY_CANDIDATES = 3
ENTITY_SPECS: Tuple[Tuple[str, str, str], ...] = (
    ("tickets", "ticket", "lastTicket"),
    ("appointments", "appointment", "lastAppointment"),
    ("servicePoints", "service_point", "lastServicePoint"),
)


@dataclass
class ConversationTurn:
    turnId: Optional[str] = None
    question: str = ""
    answer: str = ""
    intent: Optional[str] = None
    sources: List[Dict[str, object]] = field(default_factory=list)
    businessCards: List[Dict[str, object]] = field(default_factory=list)
    actionDrafts: List[Dict[str, object]] = field(default_factory=list)


@dataclass
class BusinessContextSnapshot:
    lastTicket: Optional[Dict[str, object]] = None
    lastAppointment: Optional[Dict[str, object]] = None
    lastServicePoint: Optional[Dict[str, object]] = None
    lastActionDraft: Optional[Dict[str, object]] = None


@dataclass
class MemorySummary:
    conversationId: Optional[str]
    recentTurns: List[ConversationTurn]
    compressedSummary: str
    businessContext: BusinessContextSnapshot

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


def build_request_memory_context(request: object) -> Dict[str, object]:
    """让 legacy 与 LangGraph 从同一个入口选择 Memory，避免两条链路行为漂移。"""
    return build_memory_context(
        getattr(request, "conversationId", None),
        getattr(request, "history", None),
        getattr(request, "lastBusinessContext", None),
        memory=getattr(request, "memory", None),
    )


def build_memory_context(
    conversation_id: Optional[str],
    history: Optional[List[Dict[str, object]]],
    last_business_context: Optional[Dict[str, object]],
    max_turns: int = 6,
    memory: Optional[object] = None,
) -> Dict[str, object]:
    """构造统一 Memory 视图，并让 shadow 保持 legacy 决策同时携带只读 v2 对照。"""
    structured = _object_to_dict(memory)
    if _is_matching_v2_memory(structured, conversation_id):
        return _build_v2_context(structured)
    legacy = _build_legacy_context(
        conversation_id,
        history,
        last_business_context,
        max_turns,
    )
    if _is_matching_shadow_memory(structured, conversation_id):
        shadow_payload = dict(structured)
        shadow_payload["mode"] = "v2"
        # Shadow 的对照上下文只在本次 Agent 内存中使用；实际路由继续读取 legacy 字段。
        legacy["mode"] = "shadow"
        legacy["effectiveMode"] = "legacy"
        legacy["shadowV2"] = _build_v2_context(shadow_payload)
        legacy["estimatedTokens"] = _estimate_context_tokens(legacy)
    return legacy


def _build_v2_context(memory: Dict[str, object]) -> Dict[str, object]:
    turns = [
        _turn(item)
        for item in _list_of_dicts(memory.get("recentTurns"))
    ]
    # Java 已按完整 turn 和 Token 预算裁剪；Python 再次排除异常半轮，不能自行拼接正文。
    complete_turns = [turn for turn in turns if _is_complete_turn(turn)]
    entities = _controlled_entities(memory.get("entities"))
    snapshot = _snapshot_from_entities(entities)
    rolling_summary = str(memory.get("rollingSummary") or "")
    return {
        "mode": "v2",
        "schemaVersion": "2",
        "conversationId": str(memory.get("conversationId")),
        "recentTurns": [asdict(turn) for turn in complete_turns],
        "rollingSummary": rolling_summary,
        "compressedSummary": rolling_summary,
        "entities": entities,
        "businessContext": asdict(snapshot),
        "lastProcessedMessageId": _non_negative_int(memory.get("lastProcessedMessageId")),
        "summaryVersion": _non_negative_int(memory.get("summaryVersion")),
        "truncated": bool(memory.get("truncated")),
        "estimatedTokens": _non_negative_int(memory.get("estimatedTokens")),
    }


def _build_legacy_context(
    conversation_id: Optional[str],
    history: Optional[List[Dict[str, object]]],
    last_business_context: Optional[Dict[str, object]],
    max_turns: int,
) -> Dict[str, object]:
    turns = [_turn(item) for item in (history or []) if isinstance(item, dict)]
    complete_turns = [turn for turn in turns if _is_complete_turn(turn)]
    recent_turns = complete_turns[-max(1, max_turns):]
    entities = _legacy_entities(complete_turns)
    _merge_compatible_client_context(entities, conversation_id, last_business_context or {})
    snapshot = _snapshot_from_entities(entities)
    summary = _compress(complete_turns[:-max(1, max_turns)]) if len(complete_turns) > max_turns else ""
    result = MemorySummary(conversation_id, recent_turns, summary, snapshot).to_dict()
    result.update({
        "mode": "legacy",
        "entities": entities,
        "rollingSummary": summary,
        "schemaVersion": None,
        "lastProcessedMessageId": 0,
        "summaryVersion": 0,
        "truncated": len(complete_turns) > max_turns,
        "estimatedTokens": 0,
    })
    return result


def _is_matching_v2_memory(memory: Dict[str, object], conversation_id: Optional[str]) -> bool:
    if memory.get("mode") != "v2" or memory.get("schemaVersion") != "2":
        return False
    memory_conversation = str(memory.get("conversationId") or "")
    if not memory_conversation:
        return False
    return conversation_id is None or str(conversation_id) == memory_conversation


def _is_matching_shadow_memory(memory: Dict[str, object], conversation_id: Optional[str]) -> bool:
    if memory.get("mode") != "shadow" or memory.get("schemaVersion") != "2":
        return False
    memory_conversation = str(memory.get("conversationId") or "")
    if not memory_conversation:
        return False
    return conversation_id is None or str(conversation_id) == memory_conversation


def _estimate_context_tokens(memory: Dict[str, object]) -> int:
    """使用与 Java 相同量级的字符估算，仅记录无正文的 shadow 预算差异。"""
    characters = len(str(memory.get("rollingSummary") or ""))
    turns = memory.get("recentTurns")
    if isinstance(turns, list):
        for turn in turns:
            if not isinstance(turn, dict):
                continue
            characters += len(str(turn.get("question") or ""))
            characters += len(str(turn.get("answer") or ""))
    return max(1, (characters + 3) // 4)


def _turn(item: Dict[str, object]) -> ConversationTurn:
    return ConversationTurn(
        turnId=str(item.get("turnId")) if item.get("turnId") else None,
        question=str(item.get("question") or item.get("user") or ""),
        answer=str(item.get("answer") or item.get("assistant") or ""),
        intent=str(item.get("intent")) if item.get("intent") else None,
        sources=_list_of_dicts(item.get("sources")),
        businessCards=_list_of_dicts(item.get("businessCards")),
        actionDrafts=_list_of_dicts(item.get("actionDrafts")),
    )


def _is_complete_turn(turn: ConversationTurn) -> bool:
    return bool(turn.question.strip() and turn.answer.strip())


def _legacy_entities(turns: List[ConversationTurn]) -> Dict[str, object]:
    entities = _empty_entities()
    for position, turn in enumerate(turns, start=1):
        for item in turn.sources + turn.businessCards:
            entity_type = str(item.get("type") or "")
            item_id = _positive_int(item.get("id"))
            if item_id is not None:
                _merge_entity(entities, entity_type, item_id, turn.turnId, position)
        for draft in turn.actionDrafts:
            controlled = _controlled_action_draft(draft)
            if controlled is not None:
                entities["pendingActionDraft"] = controlled
    return entities


def _controlled_entities(value: object) -> Dict[str, object]:
    source = value if isinstance(value, dict) else {}
    entities = _empty_entities()
    for plural, entity_type, _ in ENTITY_SPECS:
        for item in _list_of_dicts(source.get(plural)):
            item_id = _positive_int(item.get("id"))
            message_id = _non_negative_int(item.get("lastSeenMessageId"))
            if item_id is not None:
                _merge_entity(
                    entities,
                    entity_type,
                    item_id,
                    str(item.get("lastSeenTurnId")) if item.get("lastSeenTurnId") else None,
                    message_id,
                )
    draft = _controlled_action_draft(source.get("pendingActionDraft"))
    if draft is not None:
        entities["pendingActionDraft"] = draft
    return entities


def _merge_entity(
    entities: Dict[str, object],
    entity_type: str,
    item_id: int,
    turn_id: Optional[str],
    message_id: int,
) -> None:
    plural = _plural_for_type(entity_type)
    if plural is None:
        return
    candidates = entities.get(plural)
    if not isinstance(candidates, list):
        return
    candidate = {
        "id": item_id,
        "lastSeenTurnId": turn_id,
        "lastSeenMessageId": max(0, message_id),
    }
    candidates[:] = [item for item in candidates if item.get("id") != item_id]
    candidates.append(candidate)
    # 并发完成顺序可能与业务消息顺序不同，候选必须按可信消息位置确定新旧。
    candidates.sort(
        key=lambda item: (
            _non_negative_int(item.get("lastSeenMessageId")),
            _non_negative_int(item.get("id")),
        ),
        reverse=True,
    )
    del candidates[MAX_ENTITY_CANDIDATES:]


def _merge_compatible_client_context(
    entities: Dict[str, object],
    conversation_id: Optional[str],
    context: Dict[str, object],
) -> None:
    # 旧字段只保留兼容所需的类型和 ID；显式跨会话标记或正文、状态、附件一律不进入 Memory。
    for plural, entity_type, snapshot_key in ENTITY_SPECS:
        item = context.get(snapshot_key)
        if not isinstance(item, dict):
            continue
        source_conversation = item.get("sourceConversationId")
        if source_conversation is not None and str(source_conversation) != str(conversation_id):
            continue
        item_id = _positive_int(
            item.get("id")
            or item.get("ticketId")
            or item.get("appointmentId")
            or item.get("servicePointId")
        )
        candidates = entities.get(plural)
        if item_id is not None and isinstance(candidates, list) and not candidates:
            _merge_entity(entities, entity_type, item_id, None, 0)


def _snapshot_from_entities(entities: Dict[str, object]) -> BusinessContextSnapshot:
    snapshot = BusinessContextSnapshot()
    for plural, entity_type, snapshot_key in ENTITY_SPECS:
        candidates = entities.get(plural)
        # 多候选时故意不生成 lastXxx，防止旧 Planner 静默取第一个实体。
        value = None
        if isinstance(candidates, list) and len(candidates) == 1:
            value = {"type": entity_type, "id": candidates[0]["id"]}
        setattr(snapshot, snapshot_key, value)
    draft = entities.get("pendingActionDraft")
    snapshot.lastActionDraft = dict(draft) if isinstance(draft, dict) else None
    return snapshot


def _controlled_action_draft(value: object) -> Optional[Dict[str, object]]:
    if not isinstance(value, dict):
        return None
    draft_type = str(value.get("type") or "")
    target_type = str(value.get("targetType") or "")
    target_id = _positive_int(value.get("targetId"))
    payload = value.get("payload") if isinstance(value.get("payload"), dict) else {}
    if not target_type:
        if draft_type == "reply_ticket_draft":
            target_type = "ticket"
            target_id = target_id or _positive_int(payload.get("ticketId"))
        elif draft_type == "appointment_query_draft":
            target_type = "service_point"
            target_id = target_id or _positive_int(payload.get("servicePointId"))
    if draft_type not in {"reply_ticket_draft", "appointment_query_draft", "create_ticket_draft"}:
        return None
    if target_type not in {"ticket", "appointment", "service_point"} or target_id is None:
        return None
    return {"type": draft_type, "targetType": target_type, "targetId": target_id}


def _compress(turns: List[ConversationTurn]) -> str:
    pairs = []
    for turn in turns[-4:]:
        if turn.question or turn.intent:
            pairs.append("%s:%s" % (turn.intent or "general", turn.question[:40]))
    return " | ".join(pairs)


def _empty_entities() -> Dict[str, object]:
    return {
        "tickets": [],
        "appointments": [],
        "servicePoints": [],
        "pendingActionDraft": None,
    }


def _plural_for_type(entity_type: str) -> Optional[str]:
    normalized = {
        "ticket": "tickets",
        "appointment": "appointments",
        "service_point": "servicePoints",
    }
    return normalized.get(entity_type)


def _list_of_dicts(value: object) -> List[Dict[str, object]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def _object_to_dict(value: object) -> Dict[str, object]:
    if isinstance(value, dict):
        return value
    model_dump = getattr(value, "model_dump", None)
    if callable(model_dump):
        dumped = model_dump(mode="json")
        return dumped if isinstance(dumped, dict) else {}
    legacy_dump = getattr(value, "dict", None)
    if callable(legacy_dump):
        dumped = legacy_dump()
        return dumped if isinstance(dumped, dict) else {}
    return {}


def _positive_int(value: object) -> Optional[int]:
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value > 0:
        return value
    if isinstance(value, str) and value.isdigit() and int(value) > 0:
        return int(value)
    return None


def _non_negative_int(value: object) -> int:
    if isinstance(value, bool):
        return 0
    if isinstance(value, int) and value >= 0:
        return value
    return 0
