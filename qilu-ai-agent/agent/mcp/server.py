from __future__ import annotations

from typing import Callable, Dict, List, Optional

from agent.mcp.adapter import list_mcp_tools
from agent.mcp.client import call_tool as call_mcp_tool


def list_tools() -> List[Dict[str, object]]:
    return list_mcp_tools()


def call_tool(
    state: Dict[str, object],
    tool_name: str,
    arguments: Dict[str, object],
    call_business_tool_func: Optional[Callable[[Dict[str, object], str, Dict[str, object]], Dict[str, object]]] = None,
) -> Dict[str, object]:
    if call_business_tool_func is None:
        from agent.campus_support_agent import call_business_tool

        call_business_tool_func = call_business_tool
    return call_mcp_tool(call_business_tool_func, state, tool_name, arguments)
