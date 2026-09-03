from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional


@dataclass
class PlanStep:
    name: str
    status: str = "planned"
    detail: Dict[str, object] = field(default_factory=dict)


@dataclass
class AgentPlan:
    intent: str
    required_context: List[str]
    planned_tools: List[str]
    answer_strategy: str
    plannerMode: str = "rule"
    modelName: Optional[str] = None
    finishReason: Optional[str] = None
    schemaValidation: str = "not_applicable"
    risk_flags: List[str] = field(default_factory=list)
    steps: List[PlanStep] = field(default_factory=list)

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


@dataclass
class ToolExecutionRecord:
    toolName: str
    arguments: Dict[str, object]
    success: bool
    count: int
    latencyMs: float
    errorType: Optional[str] = None
    errorCode: Optional[str] = None
    toolProtocol: str = "http_internal"
    toolCallId: Optional[str] = None
    schemaValidation: str = "not_applicable"

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


@dataclass
class FallbackRecord:
    reason: str
    stage: str
    detail: Dict[str, object] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


def build_agent_plan(
    state: Dict[str, object],
    planned_calls: List[Dict[str, object]],
    planner_mode: str = "rule",
    model_name: Optional[str] = None,
    finish_reason: Optional[str] = None,
    schema_validation: str = "not_applicable",
) -> AgentPlan:
    intent = str(state.get("intent") or "general")
    tools = [str(call.get("toolName")) for call in planned_calls if call.get("toolName")]
    retrieval_mode = str(state.get("retrieval_mode") or "")
    required_context: List[str] = []
    if tools:
        required_context.append("business_tool")
    if retrieval_mode in {"RAG_ONLY", "HYBRID"} or (not retrieval_mode and intent == "general"):
        required_context.append("knowledge")
    if retrieval_mode == "HYBRID":
        strategy = "rag_then_business_tool"
    elif retrieval_mode == "DIRECT_LLM":
        strategy = "direct_llm"
    elif retrieval_mode == "BUSINESS_ONLY":
        strategy = "business_tool_only"
    elif retrieval_mode == "RAG_ONLY":
        strategy = "rag_only"
    else:
        strategy = "business_tool_first" if tools else "rag_or_rule"
    risk_flags: List[str] = []
    if intent.startswith("admin_"):
        risk_flags.append("admin_scope")
    if not state.get("knowledge_initialized", True):
        risk_flags.append("knowledge_not_synced")
    return AgentPlan(
        intent=intent,
        required_context=required_context,
        planned_tools=tools,
        answer_strategy=strategy,
        plannerMode=planner_mode,
        modelName=model_name,
        finishReason=finish_reason,
        schemaValidation=schema_validation,
        risk_flags=risk_flags,
        steps=[
            PlanStep("classify_intent", "done", {"intent": intent}),
            PlanStep("select_retrieval_policy", "done", {"retrievalMode": retrieval_mode}),
            PlanStep("plan_tools", "done", {"toolCount": len(tools)}),
        ],
    )
