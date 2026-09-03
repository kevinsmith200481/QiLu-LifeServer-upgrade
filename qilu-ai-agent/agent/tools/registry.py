from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple


def _object_schema(
    properties: Optional[Dict[str, Dict[str, object]]] = None,
    required: Optional[List[str]] = None,
) -> Dict[str, object]:
    """Build the closed JSON Schema exposed to the model.

    Identity and tracing fields are intentionally absent. They are injected by
    the trusted request state immediately before the internal HTTP call.
    """
    return {
        "type": "object",
        "properties": properties or {},
        "required": required or [],
        "additionalProperties": False,
    }


LIMIT_SCHEMA = {"type": "integer", "minimum": 1, "maximum": 20}
POSITIVE_ID_SCHEMA = {"type": "integer", "minimum": 1}


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    intents: Tuple[str, ...]
    description: str
    argument_schema: Dict[str, object]
    role_scope: Tuple[str, ...]
    source_type: str
    result_card_type: str
    timeout_seconds: float

    def supports_role(self, role: Optional[str]) -> bool:
        return normalize_role(role) in self.role_scope

    def openai_schema(self) -> Dict[str, object]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.argument_schema,
            },
        }

    def public_metadata(self) -> Dict[str, object]:
        """Return registry metadata without trusted request-context fields."""
        return {
            "name": self.name,
            "description": self.description,
            "parameters": self.argument_schema,
            "roleScope": list(self.role_scope),
            "sourceType": self.source_type,
            "cardType": self.result_card_type,
            "timeout": self.timeout_seconds,
        }


ALL_ROLES = ("student", "manager", "admin")


TOOL_DEFINITIONS: List[ToolDefinition] = [
    ToolDefinition("query_service_categories", ("service_categories",), "Query enabled campus service categories.", _object_schema(), ALL_ROLES, "service_category", "business", 3.0),
    ToolDefinition("query_service_points", ("repair", "printing", "express", "consultation", "service_point_search", "service_point_comment_ranking"), "Query campus service points, including comment counts.", _object_schema({"id": POSITIVE_ID_SCHEMA, "limit": LIMIT_SCHEMA}), ALL_ROLES, "service_point", "service_point", 3.0),
    ToolDefinition("query_service_point_slots", ("service_point_slots",), "Query appointment slots for service points.", _object_schema({"servicePointId": POSITIVE_ID_SCHEMA, "limit": LIMIT_SCHEMA}), ALL_ROLES, "service_point", "service_point", 3.0),
    ToolDefinition("query_my_tickets", ("ticket_status",), "Query the current user's recent tickets.", _object_schema({"limit": LIMIT_SCHEMA}), ALL_ROLES, "ticket", "ticket", 3.0),
    ToolDefinition("query_ticket_detail", ("ticket_status",), "Query one ticket detail by id.", _object_schema({"ticketId": POSITIVE_ID_SCHEMA}, ["ticketId"]), ALL_ROLES, "ticket", "ticket", 3.0),
    ToolDefinition("query_my_appointments", ("appointment_status",), "Query the current user's recent appointments.", _object_schema({"limit": LIMIT_SCHEMA}), ALL_ROLES, "appointment", "appointment", 3.0),
    ToolDefinition("query_appointment_detail", ("appointment_status",), "Query one appointment detail by id.", _object_schema({"appointmentId": POSITIVE_ID_SCHEMA}, ["appointmentId"]), ALL_ROLES, "appointment", "appointment", 3.0),
    ToolDefinition("query_inbox_summary", ("inbox_summary",), "Query the current user's inbox summary.", _object_schema({"limit": LIMIT_SCHEMA}), ALL_ROLES, "inbox", "inbox", 3.0),
    ToolDefinition("query_station_comments", ("station_comments",), "Query public comments for a service point.", _object_schema({"stationId": POSITIVE_ID_SCHEMA, "limit": LIMIT_SCHEMA}, ["stationId"]), ALL_ROLES, "service_point", "service_point", 3.0),
    ToolDefinition("query_admin_operation_logs", ("admin_operation_logs",), "Query admin operation logs.", _object_schema({"limit": LIMIT_SCHEMA}), ("admin",), "admin_log", "admin_log", 3.0),
    ToolDefinition("query_admin_appointment_failure_logs", ("admin_appointment_failure_logs",), "Query admin appointment failure logs.", _object_schema({"limit": LIMIT_SCHEMA}), ("admin",), "admin_log", "admin_log", 3.0),
]


def normalize_role(role: Optional[str]) -> str:
    normalized = str(role or "student").strip().lower()
    return normalized if normalized in ALL_ROLES else "student"


def list_tools(role: Optional[str] = None) -> List[ToolDefinition]:
    if role is None:
        return list(TOOL_DEFINITIONS)
    return [tool for tool in TOOL_DEFINITIONS if tool.supports_role(role)]


def tool_schemas_for_role(role: Optional[str]) -> List[Dict[str, object]]:
    return [tool.openai_schema() for tool in list_tools(role)]


def get_tool(name: str) -> Optional[ToolDefinition]:
    for tool in TOOL_DEFINITIONS:
        if tool.name == name:
            return tool
    return None


def tools_for_intent(intent: str, role: Optional[str] = None) -> List[ToolDefinition]:
    tools = list_tools(role) if role is not None else TOOL_DEFINITIONS
    return [tool for tool in tools if intent in tool.intents]


def plan_tool_calls(
    intent: Optional[str],
    text: str,
    memory_context: Optional[Dict[str, object]] = None,
    role: Optional[str] = None,
    resolved_entities: Optional[Dict[str, object]] = None,
) -> List[Dict[str, object]]:
    intent = intent or "general"
    # 主链路传入 Intent Router 已确认的实体后，Planner 不再独立猜测另一个 ID。
    use_resolved_entities = isinstance(resolved_entities, dict)
    entity_id = None if use_resolved_entities else extract_first_number(text)
    memory_context = memory_context or {}
    if intent == "service_categories":
        return _calls_for_intent(intent, [("query_service_categories", {})], role)
    if intent in {"repair", "printing", "express", "consultation", "service_point_search"}:
        arguments = {"limit": 10}
        service_point_id = _resolved_id(resolved_entities, "servicePointId") if use_resolved_entities else None
        if intent == "service_point_search" and service_point_id:
            # 指代已确认时按 ID 查询真实服务点，营业时间等可变事实不能取 Memory 旧卡片。
            arguments["id"] = service_point_id
        return _calls_for_intent(intent, [("query_service_points", arguments)], role)
    if intent == "service_point_comment_ranking":
        return _calls_for_intent(intent, [("query_service_points", {"limit": 20})], role)
    if intent == "service_point_slots":
        arguments = {"limit": 10}
        service_point_id = (
            _resolved_id(resolved_entities, "servicePointId")
            if use_resolved_entities
            else entity_id or _snapshot_id(memory_context.get("lastServicePoint"))
        )
        if service_point_id:
            arguments["servicePointId"] = service_point_id
        return _calls_for_intent(intent, [("query_service_point_slots", arguments)], role)
    if intent == "ticket_status":
        ticket_id = (
            _resolved_id(resolved_entities, "ticketId")
            if use_resolved_entities
            else entity_id or _snapshot_id(memory_context.get("lastTicket"))
        )
        if ticket_id:
            return _calls_for_intent(intent, [("query_ticket_detail", {"ticketId": ticket_id})], role)
        return _calls_for_intent(intent, [("query_my_tickets", {"limit": 10})], role)
    if intent == "appointment_status":
        appointment_id = (
            _resolved_id(resolved_entities, "appointmentId")
            if use_resolved_entities
            else entity_id or _snapshot_id(memory_context.get("lastAppointment"))
        )
        if appointment_id:
            return _calls_for_intent(intent, [("query_appointment_detail", {"appointmentId": appointment_id})], role)
        return _calls_for_intent(intent, [("query_my_appointments", {"limit": 10})], role)
    if intent == "inbox_summary":
        return _calls_for_intent(intent, [("query_inbox_summary", {"limit": 5})], role)
    if intent == "station_comments":
        station_id = (
            _resolved_id(resolved_entities, "servicePointId")
            if use_resolved_entities
            else entity_id
        )
        if station_id:
            return _calls_for_intent(intent, [("query_station_comments", {"stationId": station_id, "limit": 10})], role)
        return []
    if intent == "admin_operation_logs":
        return _calls_for_intent(intent, [("query_admin_operation_logs", {"limit": 10})], role)
    if intent == "admin_appointment_failure_logs":
        return _calls_for_intent(intent, [("query_admin_appointment_failure_logs", {"limit": 10})], role)
    return []


def _calls_for_intent(intent: str, planned: List[tuple], role: Optional[str]) -> List[Dict[str, object]]:
    calls = []
    available = {tool.name: tool for tool in tools_for_intent(intent, role)}
    for tool_name, arguments in planned:
        tool = available.get(tool_name)
        if tool:
            calls.append(_call(tool, arguments))
    return calls


def _call(tool: ToolDefinition, arguments: Optional[Dict[str, object]] = None) -> Dict[str, object]:
    return {"toolName": tool.name, "arguments": arguments or {}}


def _snapshot_id(value: object) -> Optional[int]:
    if isinstance(value, dict):
        item_id = value.get("id") or value.get("ticketId") or value.get("appointmentId") or value.get("servicePointId")
        return int(item_id) if isinstance(item_id, int) or (isinstance(item_id, str) and item_id.isdigit()) else None
    return None


def _resolved_id(value: object, key: str) -> Optional[int]:
    if not isinstance(value, dict):
        return None
    item_id = value.get(key)
    if isinstance(item_id, bool):
        return None
    return item_id if isinstance(item_id, int) and item_id > 0 else None


def extract_first_number(text: str) -> Optional[int]:
    match = re.search(r"\d+", text or "")
    return int(match.group(0)) if match else None
