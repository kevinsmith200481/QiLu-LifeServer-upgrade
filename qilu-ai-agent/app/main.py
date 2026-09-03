from __future__ import annotations

import os
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, Header, HTTPException, Response
from fastapi.responses import JSONResponse

from agent.campus_support_agent import CampusSupportAgent, classify_ticket_with_confidence, runtime_status, summarize_ticket
from agent.checkpoint_runtime import CheckpointConflictError, checkpoint_status, get_checkpoint_runtime
from agent.intent_router import warm_semantic_model_client
from agent.memory_summarizer import summarize_memory
from app.acceptance_faults import (
    delay_agent_if_configured,
    fault_status,
    force_agent_http_500,
    force_agent_invalid_json,
)
from app.metrics import metrics
from app.schemas import (
    CampusAssistantRequest,
    CampusAssistantResponse,
    CampusMemorySummaryRequest,
    CampusMemorySummaryResponse,
    CheckpointDeleteRequest,
    KnowledgeReloadRequest,
    KnowledgeReloadResponse,
    TicketCategoryResponse,
    TicketSummaryResponse,
    TicketTextRequest,
)

app = FastAPI(title="Qilu Campus AI Agent", version="0.2.0")
default_kb_dir = Path(__file__).resolve().parents[1] / "knowledge"
agent = CampusSupportAgent(kb_dir=os.getenv("CAMPUS_KB_DIR", str(default_kb_dir)))


@app.on_event("startup")
def warm_semantic_intent_router() -> None:
    warm_semantic_model_client()


@app.middleware("http")
async def acceptance_fault_middleware(request, call_next):
    if request.url.path == "/agent/chat":
        await delay_agent_if_configured()
        if force_agent_http_500():
            return JSONResponse(status_code=500, content={"errorCode": "ACCEPTANCE_AGENT_HTTP_500"})
        if force_agent_invalid_json():
            return Response(content="{acceptance-invalid-json", media_type="application/json")
    return await call_next(request)


@app.get("/health")
def health():
    status = {"status": "ok", "service": "qilu-ai-agent"}
    status.update(runtime_status())
    status.update(agent.retriever_status())
    status.update(fault_status())
    status.update(checkpoint_status())
    return status


@app.get("/metrics")
def agent_metrics():
    status = runtime_status()
    status.update(agent.retriever_status())
    status.update(metrics.snapshot())
    return status


@app.get("/metrics/prometheus")
def agent_prometheus_metrics():
    return Response(content=metrics.prometheus(), media_type="text/plain; version=0.0.4")


@app.get("/agent/knowledge/status")
def knowledge_status():
    return agent.retriever_status()


@app.post("/agent/chat", response_model=CampusAssistantResponse)
def chat(request: CampusAssistantRequest, traceparent: Optional[str] = Header(default=None)):
    if traceparent and not request.traceParent:
        request.traceParent = traceparent
    try:
        return agent.chat(request)
    except CheckpointConflictError as exc:
        raise HTTPException(
            status_code=409,
            detail={"errorCode": exc.error_code, "message": str(exc)},
        ) from exc


@app.post("/internal/checkpoints/delete")
def delete_checkpoint(
    request: CheckpointDeleteRequest,
    internal_token: Optional[str] = Header(default=None, alias="X-AI-Internal-Token"),
):
    runtime = get_checkpoint_runtime()
    if not runtime.verify_internal_token(internal_token):
        raise HTTPException(status_code=401, detail={"errorCode": "INVALID_INTERNAL_TOKEN"})
    if request.conversationId:
        runtime.delete_thread(request.userId, request.conversationId)
        return {"success": True, "deletedThreads": 1}
    return {"success": True, "deletedThreads": runtime.delete_user(request.userId)}


@app.post("/internal/memory/summarize", response_model=CampusMemorySummaryResponse)
def memory_summary(
    request: CampusMemorySummaryRequest,
    internal_token: Optional[str] = Header(default=None, alias="X-AI-Internal-Token"),
):
    if not get_checkpoint_runtime().verify_internal_token(internal_token):
        raise HTTPException(status_code=401, detail={"errorCode": "INVALID_INTERNAL_TOKEN"})
    return summarize_memory(request)


@app.post("/agent/ticket/summary", response_model=TicketSummaryResponse)
def ticket_summary(request: TicketTextRequest):
    return TicketSummaryResponse(summary=summarize_ticket(request.title, request.content))


@app.post("/agent/ticket/classify", response_model=TicketCategoryResponse)
def ticket_classify(request: TicketTextRequest):
    category, confidence = classify_ticket_with_confidence(request.content)
    return TicketCategoryResponse(category=category, confidence=confidence)


@app.post("/agent/knowledge/reload", response_model=KnowledgeReloadResponse)
def reload_knowledge(request: KnowledgeReloadRequest):
    return KnowledgeReloadResponse(**agent.reload_knowledge_contract(
        request.documents,
        request.knowledgeVersion,
    ))
