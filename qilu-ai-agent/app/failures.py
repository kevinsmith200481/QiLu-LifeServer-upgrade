from __future__ import annotations

from dataclasses import dataclass
from typing import Dict


@dataclass(frozen=True)
class FailureContract:
    stage: str
    retriable: bool
    fallback_message: str


# This table mirrors qilu-ai-api AiFailureCode and is intentionally explicit:
# Agent responses must not invent stages or retry semantics at runtime.
FAILURES: Dict[str, FailureContract] = {
    "MODEL_TIMEOUT": FailureContract("model", True, "模型响应超时，已切换到规则回答。"),
    "MODEL_UNAVAILABLE": FailureContract("model", True, "模型暂不可用，已切换到规则回答。"),
    "TOOL_TIMEOUT": FailureContract("tool", True, "业务数据查询超时，请稍后重试。"),
    "TOOL_UNAVAILABLE": FailureContract("tool", True, "业务数据暂时无法读取，请稍后重试。"),
    "PERMISSION_DENIED": FailureContract("permission", False, "当前账号无权查看该数据。"),
    "KNOWLEDGE_NOT_SYNCED": FailureContract("knowledge", True, "知识库尚未同步，请稍后重试。"),
    "NO_SOURCE": FailureContract("generation", False, "暂未找到可靠来源，无法给出确定答案。"),
}


def failure_contract(code: str | None) -> FailureContract | None:
    return FAILURES.get(str(code or "").strip().upper())
