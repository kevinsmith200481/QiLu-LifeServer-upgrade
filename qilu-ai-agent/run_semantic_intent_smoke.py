from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional

from dotenv import load_dotenv

load_dotenv()

from agent.intent_router import (  # noqa: E402
    classify_intent,
    intent_max_retries,
    intent_model_name,
    intent_timeout_seconds,
    invoke_semantic_model_with_budget,
    select_retrieval_policy,
)


SMOKE_CASES = [
    {
        "name": "casual_chat",
        "question": "你好，很高兴认识你",
        "expectedIntent": "casual_chat",
        "expectedMode": "DIRECT_LLM",
    },
    {
        "name": "appointment_status",
        "question": "我的预约记录发生了什么",
        "expectedIntent": "appointment_status",
        "expectedMode": "BUSINESS_ONLY",
    },
    {
        "name": "appointment_policy",
        "question": "预约需要准备什么材料？",
        "expectedIntent": "appointment_policy",
        "expectedMode": "RAG_ONLY",
    },
    {
        "name": "printing_hybrid",
        "question": "我需要打印、复印和装订材料，应该去哪个服务点并遵守哪些办理规则？",
        "expectedIntent": "printing",
        "expectedMode": "HYBRID",
    },
    {
        "name": "memory_reference",
        "question": "请结合之前的记录告诉我现在的办理状态",
        "memory": {
            "recentTurns": [{"intent": "appointment_status"}],
            "businessContext": {
                "lastAppointment": {"appointmentId": 920001},
            },
        },
        "expectedIntent": "appointment_status",
        "expectedMode": "BUSINESS_ONLY",
    },
    {
        "name": "clarification",
        "question": "预约怎么了？我不确定是要查我的预约状态，还是想问预约办理规则。",
        "expectedIntent": "ambiguous",
        "expectedMode": "CLARIFY",
    },
]


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def run_case(case: Dict[str, object], model_timeout_seconds: float) -> Dict[str, object]:
    question = str(case["question"])
    memory = case.get("memory")
    started = time.monotonic()
    classification = classify_intent(
        question,
        memory_summary=memory if isinstance(memory, dict) else None,
        role="student",
        scene="semantic_intent_real_model_smoke",
        mode="semantic",
        semantic_invoker=lambda payload: invoke_semantic_model_with_budget(payload, model_timeout_seconds),
    )
    latency_ms = round((time.monotonic() - started) * 1000, 2)
    policy = select_retrieval_policy(classification)
    decision = classification.decision
    model_decision = classification.model_decision
    expected_intent = str(case["expectedIntent"])
    expected_mode = str(case["expectedMode"])
    model_intent = model_decision.intent.value if model_decision else None
    model_source = model_decision.intentSource.value if model_decision else None
    technical_fallback = classification.fallback_reason not in {None, "LOW_CONFIDENCE"}
    passed = (
        decision.intent.value == expected_intent
        and policy.retrieval_mode.value == expected_mode
        and model_intent == expected_intent
        and model_source == "semantic_model"
        and not technical_fallback
    )
    return {
        "name": case["name"],
        "questionSha256": sha256_text(question),
        "memoryProvided": isinstance(memory, dict),
        "expectedIntent": expected_intent,
        "observedIntent": decision.intent.value,
        "expectedRetrievalMode": expected_mode,
        "observedRetrievalMode": policy.retrieval_mode.value,
        "intentSource": decision.intentSource.value,
        "modelIntent": model_intent,
        "modelIntentSource": model_source,
        "confidence": decision.confidence,
        "candidateIntents": [candidate.value for candidate in decision.candidateIntents],
        "fallbackReason": classification.fallback_reason,
        "lowConfidence": classification.low_confidence,
        "latencyMs": latency_ms,
        "passed": passed,
    }


def build_report(results: List[Dict[str, object]], model_timeout_seconds: float) -> Dict[str, object]:
    base_url = os.getenv("OPENAI_BASE_URL") or os.getenv("OPENAI_API_BASE") or "default"
    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "modelName": intent_model_name(),
        "endpointSha256": sha256_text(base_url),
        "routerMode": "semantic",
        "productionTimeoutSeconds": intent_timeout_seconds(),
        "smokeTimeoutSeconds": model_timeout_seconds,
        "maxRetries": intent_max_retries(),
        "reasoningEffort": "none",
        "caseCount": len(results),
        "passedCount": sum(1 for result in results if result["passed"]),
        "results": results,
    }


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Run real-model semantic intent routing smoke cases.")
    parser.add_argument("--output", help="Optional sanitized JSON output path.")
    parser.add_argument("--model-timeout-seconds", type=float, default=15.0)
    args = parser.parse_args(argv)
    if not os.getenv("OPENAI_API_KEY"):
        raise SystemExit("OPENAI_API_KEY is required for the real-model smoke test")

    old_values = {
        "AI_INTENT_ROUTER_MODE": os.environ.get("AI_INTENT_ROUTER_MODE"),
        "AI_INTENT_TIMEOUT_SECONDS": os.environ.get("AI_INTENT_TIMEOUT_SECONDS"),
        "AI_INTENT_MAX_RETRIES": os.environ.get("AI_INTENT_MAX_RETRIES"),
        "AI_INTENT_REASONING_EFFORT": os.environ.get("AI_INTENT_REASONING_EFFORT"),
    }
    os.environ["AI_INTENT_ROUTER_MODE"] = "semantic"
    os.environ["AI_INTENT_TIMEOUT_SECONDS"] = "15"
    os.environ["AI_INTENT_MAX_RETRIES"] = "2"
    os.environ["AI_INTENT_REASONING_EFFORT"] = "none"
    try:
        smoke_timeout = max(2.0, min(float(args.model_timeout_seconds), 30.0))
        results = [run_case(case, smoke_timeout) for case in SMOKE_CASES]
    finally:
        for name, value in old_values.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value

    report = build_report(results, smoke_timeout)
    payload = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(payload + "\n", encoding="utf-8")
    print(payload)
    return 0 if report["passedCount"] == report["caseCount"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
