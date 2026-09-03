from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any, Dict, List

from agent.campus_support_agent import (
    build_structured_response,
    detect_intent,
    filter_knowledge_hits,
    knowledge_hits_to_sources,
    memory_appointment_list,
    memory_service_point_list,
    memory_ticket_list,
)
from agent.memory import build_memory_context
from app.schemas import CampusAppointment, CampusServicePoint, CampusTicket, KnowledgeSource
from rag.retriever import KnowledgeHit, normalize_hits


def base_state(case: Dict[str, Any]) -> Dict[str, Any]:
    memory_context = build_memory_context(case.get("conversationId"), case.get("history", []), case.get("lastBusinessContext"))
    return {
        "messages": [],
        "user_input": case.get("question", ""),
        "retrieved_context": "",
        "knowledge_sources": [KnowledgeSource(**item) for item in case.get("knowledgeSources", [])],
        "response": "evaluation answer",
        "intent": case.get("intent", "general"),
        "escalate": False,
        "knowledge_initialized": case.get("knowledgeInitialized", True),
        "service_points": [
            CampusServicePoint(**item) for item in case.get("servicePoints", [{
                "id": 1,
                "name": "宿舍维修中心",
                "categoryName": "维修服务",
                "address": "学生公寓一楼",
                "openHours": "08:00-18:00",
                "description": "宿舍水电与设备维修",
            }])
        ],
        "tickets": [CampusTicket(**item) for item in case.get("tickets", [])],
        "appointments": [CampusAppointment(**item) for item in case.get("appointments", [])],
        "recommended_service_points": [],
        "user_id": 2006,
        "role": "student",
        "trace_id": "eval-" + case.get("name", "case"),
        "business_tool_results": [],
        "memory_context": memory_context,
        "agent_plan": {},
        "execution_records": [],
        "generation_record": {},
        "fallback_records": [],
    }


def evaluate_case(case: Dict[str, Any]) -> Dict[str, Any]:
    state = base_state(case)
    case_type = case.get("type")
    if case_type == "intent":
        state.update(detect_intent(state))
    elif case_type == "tool":
        state["business_tool_results"] = [{
            "toolName": case["toolName"],
            "success": True,
            "data": case["toolData"],
            "message": None,
            "count": len(case["toolData"]) if isinstance(case["toolData"], list) else 1,
        }]
    elif case_type == "permission":
        state["business_tool_results"] = [{
            "toolName": case.get("toolName", "query_ticket_detail"),
            "success": False,
            "data": None,
            "message": case.get("message"),
            "count": 0,
        }]
    elif case_type == "memory":
        memory_context = state.get("memory_context", {})
        state["tickets"] = state.get("tickets") or memory_ticket_list(memory_context)
        state["appointments"] = state.get("appointments") or memory_appointment_list(memory_context)
        memory_points = memory_service_point_list(memory_context)
        if memory_points:
            state["service_points"] = memory_points
        state.update(detect_intent(state))
    elif case_type == "rag":
        hits = [
            KnowledgeHit(
                content=str(item.get("content") or ""),
                metadata=item.get("metadata") if isinstance(item.get("metadata"), dict) else {},
                score=float(item["score"]) if item.get("score") is not None else None,
                retriever=str(item.get("retriever") or "keyword"),
            )
            for item in case.get("ragHits", [])
            if isinstance(item, dict)
        ]
        thresholds = case.get("ragThresholds", {})
        old_thresholds = {key: os.environ.get(key) for key in thresholds}
        try:
            for key, value in thresholds.items():
                os.environ[key] = str(value)
            state["knowledge_sources"] = knowledge_hits_to_sources(filter_knowledge_hits(normalize_hits(hits)))
        finally:
            for key, value in old_thresholds.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

    live_tool_data = case.get("liveToolData")
    if isinstance(live_tool_data, (dict, list)):
        # 回复草稿必须由本轮 Java 工具确认实时状态；评测 fixture 显式注入工具结果，
        # 不能把历史 Memory 中的 studentReplyRequired 当作仍然有效的业务事实。
        state["business_tool_results"] = [{
            "toolName": str(case.get("liveToolName") or "query_ticket_detail"),
            "success": True,
            "data": live_tool_data,
            "message": None,
            "count": len(live_tool_data) if isinstance(live_tool_data, list) else 1,
        }]

    response = build_structured_response(state)
    payload = response.model_dump() if hasattr(response, "model_dump") else response.dict()
    failures = compare(case.get("expect", {}), payload)
    return {
        "name": case.get("name"),
        "type": case_type,
        "group": case.get("group", case_type),
        "passed": not failures,
        "failures": failures,
        "permission": case_type == "permission",
    }


def compare(expect: Dict[str, Any], payload: Dict[str, Any]) -> List[str]:
    failures: List[str] = []
    if "intent" in expect and payload.get("intent") != expect["intent"]:
        failures.append("intent expected %s got %s" % (expect["intent"], payload.get("intent")))
    if "fallbackReason" in expect and payload.get("fallbackReason") != expect["fallbackReason"]:
        failures.append("fallbackReason expected %s got %s" % (expect["fallbackReason"], payload.get("fallbackReason")))
    if "sourceType" in expect and expect["sourceType"] not in [item.get("type") for item in payload.get("sources", [])]:
        failures.append("missing sourceType %s" % expect["sourceType"])
    if expect.get("sourcesEmpty") is True and payload.get("sources"):
        failures.append("sources expected empty")
    if "firstSourceType" in expect:
        sources = payload.get("sources", [])
        actual = sources[0].get("type") if sources else None
        if actual != expect["firstSourceType"]:
            failures.append("firstSourceType expected %s got %s" % (expect["firstSourceType"], actual))
    if "sourceRetriever" in expect and expect["sourceRetriever"] not in [item.get("source") for item in payload.get("sources", [])]:
        failures.append("missing sourceRetriever %s" % expect["sourceRetriever"])
    if "cardType" in expect and expect["cardType"] not in [item.get("type") for item in payload.get("businessCards", [])]:
        failures.append("missing cardType %s" % expect["cardType"])
    if expect.get("businessCardsEmpty") is True and payload.get("businessCards"):
        failures.append("businessCards expected empty")
    if "draftType" in expect and expect["draftType"] not in [item.get("type") for item in payload.get("actionDrafts", [])]:
        failures.append("missing draftType %s" % expect["draftType"])
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description="Run fixed AI assistant evaluation cases.")
    parser.add_argument("--cases", default="ai_assistant_eval_cases.json")
    parser.add_argument("--min-pass-rate", type=float, default=0.90)
    args = parser.parse_args()

    case_path = Path(args.cases)
    cases = json.loads(case_path.read_text(encoding="utf-8"))
    results = [evaluate_case(case) for case in cases]
    passed = sum(1 for result in results if result["passed"])
    permission_results = [result for result in results if result["permission"]]
    permission_passed = sum(1 for result in permission_results if result["passed"])
    pass_rate = passed / len(results) if results else 0.0
    permission_rate = permission_passed / len(permission_results) if permission_results else 1.0
    group_stats = build_group_stats(results)

    print(json.dumps({
        "caseCount": len(results),
        "passed": passed,
        "passRate": round(pass_rate, 4),
        "permissionCaseCount": len(permission_results),
        "permissionPassed": permission_passed,
        "permissionPassRate": round(permission_rate, 4),
        "intentPassRate": group_stats["intent"]["passRate"],
        "toolPassRate": group_stats["tool"]["passRate"],
        "permissionPassRateByGroup": group_stats["permission"]["passRate"],
        "ragPassRate": group_stats["rag"]["passRate"],
        "memoryPassRate": group_stats["memory"]["passRate"],
        "groupStats": group_stats,
        "results": results,
    }, ensure_ascii=False, indent=2))

    return 0 if pass_rate >= args.min_pass_rate and permission_rate == 1.0 else 1


def build_group_stats(results: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    groups = ["intent", "tool", "permission", "rag", "memory"]
    stats: Dict[str, Dict[str, Any]] = {}
    for group in groups:
        group_results = [result for result in results if result.get("group") == group]
        group_passed = sum(1 for result in group_results if result["passed"])
        stats[group] = {
            "caseCount": len(group_results),
            "passed": group_passed,
            "passRate": round(group_passed / len(group_results), 4) if group_results else 1.0,
        }
    return stats


if __name__ == "__main__":
    raise SystemExit(main())
