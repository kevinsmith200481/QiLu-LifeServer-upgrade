from __future__ import annotations

import json
import os
import unittest
from pathlib import Path
from typing import Dict, List

# The stage-A baseline must remain deterministic and must not call a real model.
os.environ["AI_PLANNER_MODE"] = "rule"

import agent.campus_support_agent as support_agent
from app.schemas import KnowledgeSource


BASELINE_PATH = Path(__file__).with_name("semantic_routing_baseline_cases.json")


class CountingRetriever:
    def __init__(self) -> None:
        self.calls: List[str] = []

    def is_initialized(self) -> bool:
        return True

    def retrieve_documents(self, query: str, limit: int = 3, topic_keywords=(), trace_span=None):
        self.calls.append(query)
        return []


class BaselineObserver:
    def __init__(self) -> None:
        self.retriever = CountingRetriever()
        self.tool_calls: List[str] = []

    def fake_tool(self, state, tool_name, arguments):
        self.tool_calls.append(tool_name)
        if tool_name == "query_my_appointments":
            data = [{"id": 710001, "servicePointName": "合成预约服务点", "statusText": "已过期"}]
        elif tool_name == "query_my_tickets":
            data = [{"id": 720001, "title": "合成工单", "statusText": "处理中"}]
        else:
            data = []
        return {
            "toolName": tool_name,
            "success": True,
            "data": data,
            "count": len(data),
            "latencyMs": 0.0,
            "errorType": None,
            "errorCode": None,
            "toolProtocol": "baseline_fake",
            "metricsRecorded": True,
        }

    def observe(self, question: str) -> Dict[str, object]:
        state = {
            "user_input": question,
            "trace_id": "semantic-routing-stage-a",
            "user_id": 700001,
            "role": "student",
            "memory_context": {},
            "service_points": [],
            "fallback_records": [],
        }
        original_retriever = getattr(support_agent.retrieve_context, "retriever", None)
        had_retriever = hasattr(support_agent.retrieve_context, "retriever")
        original_tool = support_agent.call_business_tool
        support_agent.retrieve_context.retriever = self.retriever
        support_agent.call_business_tool = self.fake_tool
        try:
            state.update(support_agent.retrieve_context(state))
            state.update(support_agent.check_escalation(state))
            state.update(support_agent.detect_intent(state))
            state.update(support_agent.execute_business_tools(state))
        finally:
            support_agent.call_business_tool = original_tool
            if had_retriever:
                support_agent.retrieve_context.retriever = original_retriever
            else:
                delattr(support_agent.retrieve_context, "retriever")

        sources: List[KnowledgeSource] = list(state.get("knowledge_sources", []))
        sources.extend(support_agent.business_tool_sources(state.get("business_tool_results", [])))
        return {
            "intent": state["intent"],
            "ragCalls": len(self.retriever.calls),
            "toolCalls": len(self.tool_calls),
            "toolNames": list(self.tool_calls),
            "sourceTypes": [source.type for source in sources],
        }


class SemanticRoutingBaselineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.baseline = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))

    def test_phase_a_langgraph_primary_path_remains_recorded(self) -> None:
        self.assertEqual(
            [
                "load_memory",
                "retrieve",
                "check_escalation",
                "detect_intent",
                "plan_tools",
                "execute_tools",
                "generate",
                "finalize_response",
            ],
            self.baseline["currentPrimaryPath"],
        )

    def test_two_same_term_counterexample_groups_are_stable(self) -> None:
        grouped: Dict[str, set] = {}
        target_mismatches = []
        for case in self.baseline["cases"]:
            grouped.setdefault(case["group"], set()).add(case["variant"])
            actual = BaselineObserver().observe(case["question"])
            self.assertEqual(case["expectedCurrent"], actual, case["id"])
            target = case["expectedTarget"]
            comparable_keys = actual.keys() & target.keys()
            if any(actual[key] != target[key] for key in comparable_keys):
                target_mismatches.append(case["id"])

        self.assertEqual(
            {"appointment": {"business", "knowledge"}, "ticket": {"business", "knowledge"}},
            grouped,
        )
        self.assertEqual(
            ["A-APPOINTMENT-KNOWLEDGE", "A-TICKET-KNOWLEDGE"],
            target_mismatches,
        )

    def test_fixture_contains_only_synthetic_baseline_data(self) -> None:
        serialized = json.dumps(self.baseline, ensure_ascii=False).lower()
        self.assertNotIn("token", serialized)
        self.assertNotIn("手机号", serialized)
        self.assertNotIn("910001", serialized)
        self.assertNotIn("910002", serialized)


if __name__ == "__main__":
    unittest.main()
