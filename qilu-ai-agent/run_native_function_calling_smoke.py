from __future__ import annotations

import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List

from dotenv import load_dotenv

load_dotenv()

from agent.native_function_calling import start_native_plan  # noqa: E402
from agent.tools.registry import plan_tool_calls  # noqa: E402


SMOKE_CASES = [
    ("ticket", "查询我的最近工单", "ticket_status", "query_my_tickets"),
    ("appointment", "查询我的最近预约", "appointment_status", "query_my_appointments"),
    ("service_point", "查询校园维修服务点", "repair", "query_service_points"),
    ("inbox", "查询我的未读通知摘要", "inbox_summary", "query_inbox_summary"),
    ("no_tool", "你好，请只回复一句简短问候。", "general", None),
]


def run_case(name: str, question: str, intent: str, expected_tool: str | None) -> Dict[str, object]:
    state = {
        "role": "student",
        "intent": intent,
        "user_input": question,
        "memory_context": {},
        "trace_id": "phase6-smoke-" + name,
    }
    rule_calls = plan_tool_calls(intent, question, role="student")
    result = start_native_plan(state, rule_calls)
    observed_tools = [str(call.get("toolName")) for call in result.calls]
    if expected_tool is None:
        passed = result.planner_mode == "rule_fallback" and result.fallback_reason == "EMPTY_TOOL_CALLS" and not observed_tools
    else:
        passed = result.planner_mode == "native" and expected_tool in observed_tools
    return {
        "name": name,
        "expectedTool": expected_tool,
        "observedTools": observed_tools,
        "plannerMode": result.planner_mode,
        "modelName": result.model_name,
        "finishReason": result.finish_reason,
        "schemaValidation": result.schema_validation,
        "fallbackReason": result.fallback_reason,
        "passed": passed,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run five real-model native Function Calling smoke cases.")
    parser.add_argument("--output", help="Optional sanitized JSON output path.")
    args = parser.parse_args()
    if not os.getenv("OPENAI_API_KEY"):
        raise SystemExit("OPENAI_API_KEY is required for the real-model smoke test")
    old_mode = os.environ.get("AI_PLANNER_MODE")
    os.environ["AI_PLANNER_MODE"] = "native"
    try:
        results: List[Dict[str, object]] = [run_case(*case) for case in SMOKE_CASES]
    finally:
        if old_mode is None:
            os.environ.pop("AI_PLANNER_MODE", None)
        else:
            os.environ["AI_PLANNER_MODE"] = old_mode
    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "caseCount": len(results),
        "passedCount": sum(1 for result in results if result["passed"]),
        "questionHashes": [hashlib.sha256(case[1].encode("utf-8")).hexdigest() for case in SMOKE_CASES],
        "results": results,
    }
    payload = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(payload + "\n", encoding="utf-8")
    print(payload)
    return 0 if report["passedCount"] == report["caseCount"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
