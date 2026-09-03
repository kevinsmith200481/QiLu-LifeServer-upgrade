from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Dict, List, Optional


@dataclass
class McpToolDescription:
    name: str
    description: str
    inputSchema: Dict[str, object]
    roleScope: List[str]
    sourceType: str
    cardType: str
    timeoutMs: int = 3000

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


@dataclass
class McpToolCallResult:
    toolName: str
    success: bool
    data: Any
    message: Optional[str]
    count: int
    traceId: Optional[str] = None
    latencyMs: float = 0.0
    errorType: Optional[str] = None
    toolProtocol: str = "mcp_adapter"

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)
