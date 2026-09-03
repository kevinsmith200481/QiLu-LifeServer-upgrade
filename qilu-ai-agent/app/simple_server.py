from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Dict, Tuple

from agent.campus_support_agent import CampusSupportAgent, classify_ticket_with_confidence, runtime_status, summarize_ticket
from agent.checkpoint_runtime import CheckpointConflictError, checkpoint_status, get_checkpoint_runtime
from agent.memory_summarizer import summarize_memory
from app.metrics import metrics
from app.schemas import (
    CampusAssistantRequest,
    CampusMemorySummaryRequest,
    KnowledgeReloadRequest,
    TicketTextRequest,
)


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8001

default_kb_dir = Path(__file__).resolve().parents[1] / "knowledge"
agent = CampusSupportAgent(kb_dir=os.getenv("CAMPUS_KB_DIR", str(default_kb_dir)))


def model_to_dict(model: Any) -> Dict[str, Any]:
    if hasattr(model, "model_dump"):
        return model.model_dump()
    return model.dict()


class AgentRequestHandler(BaseHTTPRequestHandler):
    server_version = "QiluCampusAiAgent/0.1"

    def do_GET(self) -> None:
        if self.path == "/health":
            body = {"status": "ok", "service": "qilu-ai-agent"}
            body.update(runtime_status())
            body.update(agent.retriever_status())
            body.update(checkpoint_status())
            self.write_json(200, body)
            return
        if self.path == "/metrics":
            body = runtime_status()
            body.update(agent.retriever_status())
            body.update(metrics.snapshot())
            self.write_json(200, body)
            return
        if self.path == "/agent/knowledge/status":
            self.write_json(200, agent.retriever_status())
            return
        self.write_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        routes: Dict[str, Callable[[Dict[str, Any]], Tuple[int, Dict[str, Any]]]] = {
            "/agent/chat": self.handle_chat,
            "/agent/ticket/summary": self.handle_ticket_summary,
            "/agent/ticket/classify": self.handle_ticket_classify,
            "/agent/knowledge/reload": self.handle_reload_knowledge,
            "/internal/checkpoints/delete": self.handle_delete_checkpoint,
            "/internal/memory/summarize": self.handle_memory_summary,
        }
        handler = routes.get(self.path)
        if handler is None:
            self.write_json(404, {"error": "not found"})
            return
        try:
            status, body = handler(self.read_json_body())
            self.write_json(status, body)
        except CheckpointConflictError as exc:
            self.write_json(409, {"errorCode": exc.error_code, "message": str(exc)})
        except Exception as exc:
            self.write_json(400, {"error": str(exc)})

    def handle_chat(self, body: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
        request = CampusAssistantRequest(**body)
        return 200, model_to_dict(agent.chat(request))

    def handle_ticket_summary(self, body: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
        request = TicketTextRequest(**body)
        return 200, {"summary": summarize_ticket(request.title, request.content)}

    def handle_ticket_classify(self, body: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
        request = TicketTextRequest(**body)
        category, confidence = classify_ticket_with_confidence(request.content)
        return 200, {"category": category, "confidence": confidence}

    def handle_reload_knowledge(self, body: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
        request = KnowledgeReloadRequest(**body)
        return 200, agent.reload_knowledge_contract(
            request.documents,
            request.knowledgeVersion,
        )

    def handle_delete_checkpoint(self, body: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
        runtime = get_checkpoint_runtime()
        if not runtime.verify_internal_token(self.headers.get("X-AI-Internal-Token")):
            return 401, {"errorCode": "INVALID_INTERNAL_TOKEN"}
        user_id = int(body["userId"])
        conversation_id = body.get("conversationId")
        if conversation_id:
            runtime.delete_thread(user_id, str(conversation_id))
            return 200, {"success": True, "deletedThreads": 1}
        return 200, {"success": True, "deletedThreads": runtime.delete_user(user_id)}

    def handle_memory_summary(self, body: Dict[str, Any]) -> Tuple[int, Dict[str, Any]]:
        runtime = get_checkpoint_runtime()
        if not runtime.verify_internal_token(self.headers.get("X-AI-Internal-Token")):
            return 401, {"errorCode": "INVALID_INTERNAL_TOKEN"}
        request = CampusMemorySummaryRequest(**body)
        return 200, model_to_dict(summarize_memory(request))

    def read_json_body(self) -> Dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8")
        return json.loads(raw) if raw else {}

    def write_json(self, status: int, body: Dict[str, Any]) -> None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format: str, *args: Any) -> None:
        print("%s - %s" % (self.address_string(), format % args))


def main() -> None:
    host = os.getenv("AI_AGENT_HOST", DEFAULT_HOST)
    port = int(os.getenv("AI_AGENT_PORT", str(DEFAULT_PORT)))
    server = ThreadingHTTPServer((host, port), AgentRequestHandler)
    print(f"Qilu Campus AI Agent listening on http://{host}:{port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
