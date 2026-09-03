from __future__ import annotations

import os
from typing import Dict, List

from agent.mcp.schemas import McpToolDescription
from agent.tools.registry import get_tool, list_tools


MCP_DISCOVERABLE_TOOLS = {
    "query_service_points",
    "query_service_point_slots",
    "query_my_tickets",
    "query_ticket_detail",
    "query_my_appointments",
    "query_appointment_detail",
    "query_inbox_summary",
}


def list_mcp_tools() -> List[Dict[str, object]]:
    descriptions = []
    for tool in list_tools():
        if tool.name not in MCP_DISCOVERABLE_TOOLS:
            continue
        descriptions.append(McpToolDescription(
            name=tool.name,
            description=tool.description,
            inputSchema=to_mcp_input_schema(tool.argument_schema),
            roleScope=list(tool.role_scope),
            sourceType=tool.source_type,
            cardType=tool.result_card_type,
            timeoutMs=tool_timeout_ms(tool.timeout_seconds),
        ).to_dict())
    return descriptions


def mcp_tool_description(name: str) -> Dict[str, object]:
    tool = get_tool(name)
    if not tool or name not in MCP_DISCOVERABLE_TOOLS:
        raise ValueError("tool is not discoverable by mcp adapter")
    return McpToolDescription(
        name=tool.name,
        description=tool.description,
        inputSchema=to_mcp_input_schema(tool.argument_schema),
        roleScope=list(tool.role_scope),
        sourceType=tool.source_type,
        cardType=tool.result_card_type,
        timeoutMs=tool_timeout_ms(tool.timeout_seconds),
    ).to_dict()


def to_mcp_input_schema(argument_schema: Dict[str, object]) -> Dict[str, object]:
    # Stage 6 registry schemas are already closed JSON Schema documents. Keep
    # the adapter lossless instead of applying the legacy string-type converter.
    if argument_schema.get("type") == "object" and isinstance(argument_schema.get("properties"), dict):
        return {
            "type": "object",
            "properties": dict(argument_schema.get("properties") or {}),
            "required": list(argument_schema.get("required") or []),
            "additionalProperties": False,
        }
    properties: Dict[str, object] = {}
    required = []
    for name, spec in (argument_schema or {}).items():
        type_name = str(spec)
        optional = type_name.endswith("?")
        if optional:
            type_name = type_name[:-1]
        properties[name] = mcp_type_schema(type_name)
        if not optional:
            required.append(name)
    schema: Dict[str, object] = {
        "type": "object",
        "properties": properties,
        "additionalProperties": False,
    }
    if required:
        schema["required"] = required
    return schema


def mcp_type_schema(type_name: str) -> Dict[str, object]:
    if type_name in {"int", "integer"}:
        return {"type": "integer"}
    if type_name in {"float", "number"}:
        return {"type": "number"}
    if type_name in {"bool", "boolean"}:
        return {"type": "boolean"}
    if type_name in {"dict", "object"}:
        return {"type": "object"}
    if type_name in {"list", "array"}:
        return {"type": "array"}
    return {"type": "string"}


def tool_timeout_ms(default_seconds: float = 3.0) -> int:
    try:
        return int(float(os.getenv("AI_TOOL_TIMEOUT_SECONDS", str(default_seconds))) * 1000)
    except ValueError:
        return 3000
