from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple


MAIN_SPAN = "qilu.ai.campus_chat"
RPC_CLIENT_SPAN = "rpc.client AiCampusAssistantService.chat"
RPC_SERVER_SPAN = "rpc.server AiCampusAssistantService.chat"
PROVIDER_SPAN = "qilu.ai.provider.chat"
AGENT_SPAN = "qilu.ai.agent.chat"
BASE_LAYERS = ["main", "rpc_client", "rpc_server", "provider", "agent"]


def request_json(
    method: str,
    url: str,
    timeout: float,
    token: Optional[str] = None,
    payload: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["authorization"] = token
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read().decode("utf-8")
    return json.loads(body) if body else {}


def assert_true(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def unwrap_result(body: Dict[str, Any], name: str) -> Any:
    assert_true(body.get("success") is True, "%s returned failure: %s" % (name, body.get("errorMsg")))
    return body.get("data")


def assert_structured_response(response: Dict[str, Any]) -> None:
    assert_true(isinstance(response.get("answer"), str), "response.answer is missing")
    assert_true(isinstance(response.get("intent"), str), "response.intent is missing")
    assert_true("confidence" in response, "response.confidence is missing")
    for field in ("sources", "businessCards", "actionDrafts", "executionRecords"):
        assert_true(isinstance(response.get(field), list), "response.%s is not a list" % field)


def chat(
    base_url: str,
    token: str,
    question: str,
    timeout: float,
    session_id: Optional[int] = None,
) -> Tuple[Dict[str, Any], str, int]:
    payload: Dict[str, Any] = {"question": question}
    if session_id is not None:
        payload["sessionId"] = session_id
    body = request_json("POST", base_url + "/ai/campus/chat", timeout, token=token, payload=payload)
    data = unwrap_result(body, "ai chat")
    assert_true(isinstance(data, dict), "ai chat data is not an object")
    response = data.get("response")
    assert_true(isinstance(response, dict), "ai chat response is not an object")
    assert_structured_response(response)
    trace_id = data.get("traceId")
    assert_true(
        isinstance(trace_id, str) and re.fullmatch(r"[0-9a-f]{32}", trace_id) is not None,
        "chat traceId is not a W3C trace id",
    )
    assert_true(response.get("traceId") == trace_id, "response traceId does not match wrapper traceId")
    returned_session = data.get("sessionId")
    assert_true(isinstance(returned_session, int), "chat sessionId is missing")
    return response, trace_id, returned_session


def item_types(response: Dict[str, Any], field: str) -> List[str]:
    return [str(item.get("type")) for item in response.get(field, []) if isinstance(item, dict)]


def execution_record(response: Dict[str, Any], tool_name: str) -> Optional[Dict[str, Any]]:
    for record in response.get("executionRecords", []):
        if isinstance(record, dict) and record.get("toolName") == tool_name:
            return record
    return None


def ticket_total(base_url: str, token: str, timeout: float) -> Optional[int]:
    body = request_json("GET", base_url + "/ticket/mine?current=1", timeout, token=token)
    assert_true(body.get("success") is True, "ticket mine returned failure")
    if isinstance(body.get("total"), int):
        return body["total"]
    data = body.get("data")
    if isinstance(data, dict) and isinstance(data.get("total"), int):
        return data["total"]
    if isinstance(data, list):
        return len(data)
    return None


def observed_layers(span_names: List[str]) -> List[str]:
    layers: List[str] = []
    mappings = [
        ("main", lambda name: name == MAIN_SPAN),
        ("rpc_client", lambda name: name == RPC_CLIENT_SPAN),
        ("rpc_server", lambda name: name == RPC_SERVER_SPAN),
        ("provider", lambda name: name == PROVIDER_SPAN),
        ("agent", lambda name: name == AGENT_SPAN),
        ("tool", lambda name: name.startswith("qilu.ai.agent.tool.") or name.startswith("qilu.ai.tool.")),
    ]
    for layer, predicate in mappings:
        if any(predicate(name) for name in span_names):
            layers.append(layer)
    return layers


def wait_for_trace(
    collector_url: str,
    trace_id: str,
    expected_layers: List[str],
    timeout: float,
) -> Tuple[List[str], List[str]]:
    deadline = time.monotonic() + timeout
    latest_spans: List[str] = []
    while time.monotonic() < deadline:
        try:
            body = request_json("GET", collector_url + "/api/traces/" + trace_id, min(5.0, timeout))
            traces = body.get("data") if isinstance(body, dict) else None
            if isinstance(traces, list) and traces:
                latest_spans = sorted(
                    {
                        str(span.get("operationName"))
                        for span in traces[0].get("spans", [])
                        if isinstance(span, dict) and span.get("operationName")
                    }
                )
                layers = observed_layers(latest_spans)
                if all(layer in layers for layer in expected_layers):
                    return layers, latest_spans
        except (OSError, ValueError, urllib.error.URLError):
            # OTLP export is asynchronous; retry only inside the bounded trace budget.
            pass
        time.sleep(0.25)
    return observed_layers(latest_spans), latest_spans


def result_payload(
    name: str,
    category: str,
    expected_layers: List[str],
    observed: List[str],
    trace_id: Optional[str],
    response: Optional[Dict[str, Any]],
    latency_ms: int,
    passed: bool,
    failure_detail: Optional[str],
    session_id: Optional[int] = None,
    observed_spans: Optional[List[str]] = None,
) -> Dict[str, Any]:
    response = response or {}
    payload: Dict[str, Any] = {
        "name": name,
        "category": category,
        "entryPoint": "/ai/campus/chat",
        "expectedLayers": expected_layers,
        "observedLayers": observed,
        "traceId": trace_id,
        "plannerMode": response.get("plannerMode"),
        "fallbackReason": response.get("fallbackReason"),
        "errorCode": response.get("errorCode"),
        "latencyMs": latency_ms,
        "passed": passed,
        "failureDetail": failure_detail,
    }
    if session_id is not None:
        payload["sessionId"] = session_id
    if observed_spans is not None:
        payload["observedSpans"] = observed_spans
    records = [item for item in response.get("executionRecords", []) if isinstance(item, dict)]
    payload["observedTools"] = sorted({str(item.get("toolName")) for item in records if item.get("toolName")})
    payload["toolCallIdPresent"] = any(bool(item.get("toolCallId")) for item in records)
    payload["schemaValidations"] = sorted(
        {str(item.get("schemaValidation")) for item in records if item.get("schemaValidation")}
    )
    checkpoint = response.get("checkpoint")
    if isinstance(checkpoint, dict):
        payload["checkpoint"] = {
            "enabled": checkpoint.get("enabled"),
            "recovered": checkpoint.get("recovered"),
            "resumedFromInterrupt": checkpoint.get("resumedFromInterrupt"),
            "schemaVersion": checkpoint.get("schemaVersion"),
        }
    return payload


class PhaseEightRunner:

    def __init__(self, args: argparse.Namespace):
        self.base_url = args.base_url.rstrip("/")
        self.collector_url = args.collector_url.rstrip("/")
        self.student_token = args.student_token
        self.admin_token = args.admin_token
        self.timeout = args.timeout
        self.trace_timeout = args.trace_timeout
        self.sync_call_count = 0

    def invoke_case(
        self,
        name: str,
        token: str,
        question: str,
        assertion: Callable[[Dict[str, Any], int], None],
        tool_expected: bool = True,
        session_id: Optional[int] = None,
        category: str = "business",
    ) -> Dict[str, Any]:
        started = time.monotonic()
        response: Optional[Dict[str, Any]] = None
        trace_id: Optional[str] = None
        returned_session: Optional[int] = None
        expected = BASE_LAYERS + (["tool"] if tool_expected else [])
        try:
            response, trace_id, returned_session = chat(
                self.base_url, token, question, self.timeout, session_id=session_id
            )
            latency_ms = int((time.monotonic() - started) * 1000)
            assertion(response, returned_session)
            layers, spans = wait_for_trace(
                self.collector_url, trace_id, expected, self.trace_timeout
            )
            missing = [layer for layer in expected if layer not in layers]
            assert_true(not missing, "trace missing layers: %s" % ",".join(missing))
            return result_payload(
                name, category, expected, layers, trace_id, response, latency_ms,
                True, None, returned_session, spans,
            )
        except Exception as exc:
            latency_ms = int((time.monotonic() - started) * 1000)
            return result_payload(
                name, category, expected, [], trace_id, response, latency_ms,
                False, str(exc), returned_session,
            )

    def sync_knowledge(self) -> None:
        self.sync_call_count += 1
        body = request_json(
            "PUT",
            self.base_url + "/admin/ai-knowledge/sync-agent",
            self.timeout,
            token=self.admin_token,
        )
        unwrap_result(body, "knowledge sync")

    def business_cases(self) -> List[Dict[str, Any]]:
        self.sync_knowledge()
        cases: List[Dict[str, Any]] = []
        cases.append(self.invoke_case(
            "student_ticket_query", self.student_token, "Show my recent campus service tickets",
            lambda response, _: self.assert_source_and_card(response, "ticket", "ticket_status"),
        ))
        cases.append(self.invoke_case(
            "student_unauthorized_ticket_denied", self.student_token, "Show ticket 990002 detail",
            self.assert_permission_denied,
        ))

        before = ticket_total(self.base_url, self.student_token, self.timeout)
        draft = self.invoke_case(
            "action_draft_does_not_write", self.student_token, "My dorm room has a water leak",
            self.assert_ticket_draft, tool_expected=True,
        )
        after = ticket_total(self.base_url, self.student_token, self.timeout)
        if before is not None and after is not None and before != after:
            draft["passed"] = False
            draft["failureDetail"] = "action draft changed ticket total"
        cases.append(draft)

        cases.append(self.invoke_case(
            "admin_operation_log_query", self.admin_token, "Show recent admin operation logs",
            lambda response, _: self.assert_source_and_card(response, "admin_log", "admin_operation_logs"),
        ))
        cases.append(self.invoke_case(
            "appointment_detail_query", self.student_token, "Show appointment 990001 detail",
            lambda response, _: self.assert_tool(response, "query_appointment_detail"),
        ))
        cases.append(self.invoke_case(
            "inbox_summary_query", self.student_token, "Show my unread inbox notification summary",
            lambda response, _: self.assert_source_and_card(response, "inbox", "inbox_summary"),
        ))
        cases.append(self.invoke_case(
            "service_point_slots_query", self.student_token,
            "Show available appointment slots at service point 990001",
            lambda response, _: self.assert_tool(response, "query_service_point_slots"),
        ))
        cases.append(self.invoke_case(
            "knowledge_sync_rag_query", self.student_token,
            "阶段八校园手册的答案颜色是什么？",
            self.assert_rag, tool_expected=False,
        ))
        return cases

    def native_case(self) -> List[Dict[str, Any]]:
        return [self.invoke_case(
            "native_function_calling_tool_selection", self.student_token,
            "Show my recent campus appointments",
            self.assert_native_function_call,
        )]

    def legacy_cases(self) -> List[Dict[str, Any]]:
        self.sync_knowledge()
        return [
            self.invoke_case(
                "legacy_student_ticket_query", self.student_token, "Show my recent campus service tickets",
                lambda response, _: self.assert_source_and_card(response, "ticket", "ticket_status"),
                category="legacy_compatibility",
            ),
            self.invoke_case(
                "legacy_student_unauthorized_ticket_denied", self.student_token, "Show ticket 990002 detail",
                self.assert_permission_denied, category="legacy_compatibility",
            ),
            self.invoke_case(
                "legacy_knowledge_sync_rag_query", self.student_token,
                "阶段八校园手册的答案颜色是什么？",
                self.assert_rag, tool_expected=False, category="legacy_compatibility",
            ),
        ]

    def checkpoint_seed(self) -> List[Dict[str, Any]]:
        return [self.invoke_case(
            "checkpoint_restart_seed", self.student_token, "Show appointment 990001 detail",
            lambda response, _: self.assert_tool(response, "query_appointment_detail"),
            category="restart_seed",
        )]

    def provider_restart_case(self) -> List[Dict[str, Any]]:
        return [self.invoke_case(
            "provider_restart_student_ticket_query", self.student_token,
            "Show my recent campus service tickets",
            lambda response, _: self.assert_source_and_card(response, "ticket", "ticket_status"),
            category="provider_restart",
        )]

    def agent_restart_rag_case(self) -> List[Dict[str, Any]]:
        return [self.invoke_case(
            "agent_restart_rag_query", self.student_token,
            "阶段八校园手册的答案颜色是什么？",
            self.assert_rag, tool_expected=False, category="agent_restart",
        )]

    def knowledge_resync_case(self) -> List[Dict[str, Any]]:
        self.sync_knowledge()
        return [self.invoke_case(
            "knowledge_resync_v2_query", self.student_token,
            "阶段八校园手册的答案颜色是什么？",
            self.assert_rag_v2, tool_expected=False, category="knowledge_resync",
        )]

    def knowledge_empty_sync_case(self) -> List[Dict[str, Any]]:
        self.sync_knowledge()
        return [self.invoke_case(
            "knowledge_empty_sync_query", self.student_token,
            "阶段八校园手册的答案颜色是什么？",
            self.assert_knowledge_not_synced, tool_expected=False, category="knowledge_empty_sync",
        )]

    def agent_restart_native_case(self) -> List[Dict[str, Any]]:
        return [self.invoke_case(
            "agent_restart_native_function_calling", self.student_token,
            "Show my recent campus appointments",
            self.assert_native_function_call, category="agent_restart",
        )]

    def checkpoint_recovery(self, session_id: int) -> List[Dict[str, Any]]:
        return [self.invoke_case(
            "checkpointer_restart_recovery", self.student_token, "Continue with that appointment",
            self.assert_checkpoint_recovered, session_id=session_id, category="business",
        )]

    @staticmethod
    def assert_source_and_card(response: Dict[str, Any], expected_type: str, intent: str) -> None:
        assert_true(response.get("intent") == intent, "unexpected intent")
        assert_true(expected_type in item_types(response, "sources"), "expected source is missing")
        assert_true(expected_type in item_types(response, "businessCards"), "expected business card is missing")

    @staticmethod
    def assert_permission_denied(response: Dict[str, Any], _: int) -> None:
        assert_true(response.get("fallbackReason") == "PERMISSION_DENIED", "permission was not denied")
        assert_true(not response.get("sources"), "permission response exposed sources")
        assert_true(not response.get("businessCards"), "permission response exposed business cards")

    @staticmethod
    def assert_ticket_draft(response: Dict[str, Any], _: int) -> None:
        assert_true("create_ticket_draft" in item_types(response, "actionDrafts"), "ticket draft is missing")

    @staticmethod
    def assert_tool(response: Dict[str, Any], tool_name: str) -> None:
        assert_true(execution_record(response, tool_name) is not None, "tool %s was not executed" % tool_name)

    @staticmethod
    def assert_rag(response: Dict[str, Any], _: int) -> None:
        assert_true("knowledge" in item_types(response, "sources"), "RAG response has no knowledge source")
        assert_true(response.get("fallbackReason") is None, "RAG response used fallback")
        assert_true("靛蓝" in str(response.get("answer")), "RAG answer did not use the synchronized fixture")

    @staticmethod
    def assert_rag_v2(response: Dict[str, Any], _: int) -> None:
        assert_true("knowledge" in item_types(response, "sources"), "V2 RAG response has no knowledge source")
        assert_true(response.get("fallbackReason") is None, "V2 RAG response used fallback")
        answer = str(response.get("answer"))
        assert_true("COBALT_V2" in answer, "V2 RAG answer did not use the updated fixture")
        assert_true("靛蓝" not in answer, "V2 RAG answer still contains the V1 fixture")

    @staticmethod
    def assert_knowledge_not_synced(response: Dict[str, Any], _: int) -> None:
        assert_true(response.get("fallbackReason") == "KNOWLEDGE_NOT_SYNCED", "empty sync did not clear knowledge")
        assert_true(not response.get("sources"), "empty sync response exposed old knowledge sources")
        answer = str(response.get("answer"))
        assert_true("靛蓝" not in answer and "COBALT_V2" not in answer, "empty sync returned an old answer")

    @staticmethod
    def assert_native_function_call(response: Dict[str, Any], _: int) -> None:
        assert_true(response.get("plannerMode") == "native", "planner mode is not native")
        record = execution_record(response, "query_my_appointments")
        assert_true(record is not None, "native appointment tool was not executed")
        assert_true(bool(record.get("toolCallId")), "native tool_call id is missing")
        assert_true(record.get("schemaValidation") == "passed", "native tool schema validation failed")

    @staticmethod
    def assert_checkpoint_recovered(response: Dict[str, Any], _: int) -> None:
        checkpoint = response.get("checkpoint")
        assert_true(isinstance(checkpoint, dict), "checkpoint metadata is missing")
        assert_true(checkpoint.get("recovered") is True, "checkpoint was not recovered after restart")
        assert_true(execution_record(response, "query_appointment_detail") is not None,
                    "recovered appointment context was not used")


def write_json(path: str, payload: Dict[str, Any]) -> None:
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run self-contained phase 8 main-to-RPC-to-Agent E2E checks.")
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--collector-url", default="http://127.0.0.1:16687")
    parser.add_argument("--student-token", default="stage8-student")
    parser.add_argument("--admin-token", default="stage8-admin")
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--trace-timeout", type=float, default=20.0)
    parser.add_argument(
        "--suite",
        choices=(
            "business", "legacy", "checkpoint-seed", "checkpoint-recovery",
            "native", "provider-restart", "agent-restart-rag", "agent-restart-native",
            "knowledge-resync", "knowledge-empty-sync",
        ),
        default="business",
    )
    parser.add_argument("--session-id", type=int)
    parser.add_argument("--output")
    args = parser.parse_args()
    runner = PhaseEightRunner(args)
    if args.suite == "business":
        results = runner.business_cases()
    elif args.suite == "legacy":
        results = runner.legacy_cases()
    elif args.suite == "checkpoint-seed":
        results = runner.checkpoint_seed()
    elif args.suite == "checkpoint-recovery":
        assert_true(args.session_id is not None, "--session-id is required for checkpoint recovery")
        results = runner.checkpoint_recovery(args.session_id)
    elif args.suite == "provider-restart":
        results = runner.provider_restart_case()
    elif args.suite == "native":
        results = runner.native_case()
    elif args.suite == "agent-restart-rag":
        results = runner.agent_restart_rag_case()
    elif args.suite == "knowledge-resync":
        results = runner.knowledge_resync_case()
    elif args.suite == "knowledge-empty-sync":
        results = runner.knowledge_empty_sync_case()
    else:
        results = runner.agent_restart_native_case()
    passed = sum(1 for result in results if result["passed"])
    payload = {
        "schemaVersion": 1,
        "suite": args.suite,
        "caseCount": len(results),
        "passedCount": passed,
        "passed": passed == len(results),
        "syncCallCount": runner.sync_call_count,
        "results": results,
    }
    if args.output:
        write_json(args.output, payload)
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if payload["passed"] else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, urllib.error.URLError, ValueError) as exc:
        print(json.dumps({"passed": False, "failureDetail": str(exc)}, ensure_ascii=False, indent=2))
        raise SystemExit(1)
