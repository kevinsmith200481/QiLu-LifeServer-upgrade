from __future__ import annotations

from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


class CampusServicePoint(BaseModel):
    id: Optional[int] = None
    name: Optional[str] = None
    categoryName: Optional[str] = None
    area: Optional[str] = None
    address: Optional[str] = None
    openHours: Optional[str] = None
    phone: Optional[str] = None
    description: Optional[str] = None
    commentCount: Optional[int] = None


class CampusTicket(BaseModel):
    id: Optional[int] = None
    title: Optional[str] = None
    content: Optional[str] = None
    status: Optional[int] = None
    statusText: Optional[str] = None
    priority: Optional[int] = None
    studentReplyRequired: Optional[int] = None
    studentReplyTime: Optional[str] = None
    attachmentName: Optional[str] = None
    attachmentUrl: Optional[str] = None


class CampusAppointment(BaseModel):
    id: Optional[int] = None
    servicePointId: Optional[int] = None
    servicePointName: Optional[str] = None
    servicePointAddress: Optional[str] = None
    slotTitle: Optional[str] = None
    slotDescription: Optional[str] = None
    startTime: Optional[str] = None
    endTime: Optional[str] = None
    status: Optional[int] = None
    statusText: Optional[str] = None
    remark: Optional[str] = None
    createTime: Optional[str] = None
    cancelTime: Optional[str] = None
    finishTime: Optional[str] = None


class CampusMemoryTurn(BaseModel):
    """主服务确认的完整轮次；禁止夹带任意 metadata 字段。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    turnId: Optional[str] = Field(default=None, max_length=64)
    question: str = Field(default="", max_length=4000)
    answer: str = Field(default="", max_length=4000)
    intent: Optional[str] = Field(default=None, max_length=128)


class CampusMemoryEntity(BaseModel):
    """只接受正整数 ID 和可信消息位置，不接受业务正文或状态。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    id: int = Field(gt=0)
    lastSeenTurnId: Optional[str] = Field(default=None, max_length=64)
    lastSeenMessageId: int = Field(ge=0)


class CampusMemoryActionDraft(BaseModel):
    """草稿记忆只保留目标引用，不携带可执行写入参数。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    type: Literal["reply_ticket_draft", "appointment_query_draft", "create_ticket_draft"]
    targetType: Literal["ticket", "appointment", "service_point"]
    targetId: int = Field(gt=0)


class CampusMemoryEntities(BaseModel):
    """固定实体集合，每种类型最多保留最近三个候选。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    tickets: List[CampusMemoryEntity] = Field(default_factory=list, max_length=3)
    appointments: List[CampusMemoryEntity] = Field(default_factory=list, max_length=3)
    servicePoints: List[CampusMemoryEntity] = Field(default_factory=list, max_length=3)
    pendingActionDraft: Optional[CampusMemoryActionDraft] = None


class CampusMemory(BaseModel):
    """Java/Python 共识的 Memory v2 封闭契约。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    mode: Literal["legacy", "shadow", "v2"]
    schemaVersion: Literal["2"]
    conversationId: str = Field(min_length=1, max_length=64)
    recentTurns: List[CampusMemoryTurn] = Field(default_factory=list, max_length=100)
    rollingSummary: str = Field(default="", max_length=1000)
    entities: CampusMemoryEntities = Field(default_factory=CampusMemoryEntities)
    lastProcessedMessageId: int = Field(default=0, ge=0)
    summaryVersion: int = Field(default=0, ge=0)
    truncated: bool = False
    estimatedTokens: int = Field(default=0, ge=0, le=100000)


class CampusMemoryDiagnostics(BaseModel):
    """诊断字段只保留计数和枚举，不允许正文或业务 ID。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    mode: Literal["legacy", "shadow", "v2"]
    schemaVersion: Literal["2"]
    recentTurnCount: int = Field(default=0, ge=0, le=100)
    summaryVersion: int = Field(default=0, ge=0)
    entityTypes: List[Literal["ticket", "appointment", "service_point"]] = Field(
        default_factory=list,
        max_length=3,
    )
    resolutionSource: Optional[str] = Field(default=None, max_length=64)
    degraded: bool = False
    degradedReason: Optional[str] = Field(default=None, max_length=64)


class CampusMemorySummaryRequest(BaseModel):
    """内部模型摘要请求；禁止身份、实体、工具和任意扩展字段。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    schemaVersion: Literal["2"]
    conversationId: str = Field(min_length=1, max_length=64)
    baseVersion: int = Field(ge=0)
    lastProcessedMessageId: int = Field(ge=0)
    previousSummary: str = Field(default="", max_length=1000)
    turns: List[CampusMemoryTurn] = Field(default_factory=list, max_length=20)
    maxSummaryChars: int = Field(ge=64, le=1000)
    timeoutSeconds: int = Field(ge=1, le=60)
    maxRetries: int = Field(ge=0, le=3)


class CampusMemorySummaryResponse(BaseModel):
    """失败只返回稳定错误码；成功只返回脱敏滚动摘要。"""

    model_config = ConfigDict(extra="forbid", strict=True)

    success: bool
    rollingSummary: Optional[str] = Field(default=None, max_length=1000)
    errorCode: Optional[str] = Field(default=None, max_length=64)


class CampusAssistantRequest(BaseModel):
    userId: Optional[int] = None
    role: Optional[str] = None
    traceId: Optional[str] = None
    traceParent: Optional[str] = None
    conversationId: Optional[str] = None
    turnId: Optional[str] = Field(default=None, max_length=64)
    scene: Optional[str] = None
    question: str = Field(min_length=1)
    servicePoints: List[CampusServicePoint] = Field(default_factory=list)
    tickets: List[CampusTicket] = Field(default_factory=list)
    appointments: List[CampusAppointment] = Field(default_factory=list)
    history: List[Dict[str, Any]] = Field(default_factory=list)
    lastBusinessContext: Optional[Dict[str, Any]] = None
    memory: Optional[CampusMemory] = None


class KnowledgeSource(BaseModel):
    type: str = "knowledge"
    id: Optional[int] = None
    knowledgeId: Optional[int] = None
    title: Optional[str] = None
    name: Optional[str] = None
    category: Optional[str] = None
    snippet: Optional[str] = None
    score: Optional[float] = None
    source: Optional[str] = None
    knowledgeVersion: Optional[str] = None
    indexVersion: Optional[str] = None
    chunkIndexes: List[int] = Field(default_factory=list)
    retrievers: List[str] = Field(default_factory=list)
    fusionScore: Optional[float] = None
    retrieverScores: Dict[str, float] = Field(default_factory=dict)
    normalizedRetrieverScores: Dict[str, float] = Field(default_factory=dict)
    address: Optional[str] = None
    openHours: Optional[str] = None
    statusText: Optional[str] = None
    readStatus: Optional[int] = None
    createTime: Optional[str] = None
    slotTitle: Optional[str] = None
    startTime: Optional[str] = None
    endTime: Optional[str] = None
    module: Optional[str] = None
    operation: Optional[str] = None


class CampusAssistantResponse(BaseModel):
    answer: str
    intent: str
    traceId: Optional[str] = None
    orchestrator: Optional[str] = None
    confidence: float = 0.0
    needCreateTicket: bool = False
    recommendedServicePoints: List[CampusServicePoint] = Field(default_factory=list)
    sources: List[KnowledgeSource] = Field(default_factory=list)
    businessCards: List[Dict[str, Any]] = Field(default_factory=list)
    actionDrafts: List[Dict[str, Any]] = Field(default_factory=list)
    langGraphNodes: List[Dict[str, Any]] = Field(default_factory=list)
    executionRecords: List[Dict[str, Any]] = Field(default_factory=list)
    fallbackRecords: List[Dict[str, Any]] = Field(default_factory=list)
    fallbackReason: Optional[str] = None
    serviceStage: Optional[str] = None
    errorStage: Optional[str] = None
    errorCode: Optional[str] = None
    retriable: Optional[bool] = None
    fallbackMessage: Optional[str] = None
    rpcAttempts: Optional[int] = None
    plannerMode: Optional[str] = None
    retrievalMode: Optional[str] = None
    intentSource: Optional[str] = None
    routingReason: Optional[str] = None
    lowConfidence: bool = False
    checkpoint: Optional[Dict[str, Any]] = None
    memoryDiagnostics: Optional[CampusMemoryDiagnostics] = None


class TicketTextRequest(BaseModel):
    title: Optional[str] = None
    content: str = Field(min_length=1)


class TicketSummaryResponse(BaseModel):
    summary: str


class TicketCategoryResponse(BaseModel):
    category: str
    confidence: float = 1.0


class KnowledgeReloadItem(BaseModel):
    id: Optional[int] = None
    title: str = Field(min_length=1)
    content: str = Field(min_length=1)
    category: Optional[str] = None
    source: Optional[str] = None
    keywords: List[str] = Field(default_factory=list)


class KnowledgeReloadRequest(BaseModel):
    knowledgeVersion: Optional[str] = None
    documents: List[KnowledgeReloadItem] = Field(default_factory=list)


class KnowledgeReloadResponse(BaseModel):
    success: bool
    activated: bool = False
    degraded: bool = False
    documentCount: int = 0
    sourceDocumentCount: int = 0
    chunkCount: int = 0
    message: str = ""
    knowledgeVersion: Optional[str] = None
    indexVersion: Optional[str] = None
    activeKnowledgeVersion: Optional[str] = None
    activeIndexVersion: Optional[str] = None
    backendStates: Dict[str, str] = Field(default_factory=dict)
    candidateCollection: Optional[str] = None
    errorCode: Optional[str] = None
    instanceId: Optional[str] = None


class CheckpointDeleteRequest(BaseModel):
    userId: int
    conversationId: Optional[str] = None
