from __future__ import annotations

import unittest

from agent.campus_support_agent import build_structured_response, detect_intent, memory_ticket_list
from agent.memory import build_memory_context
from agent.tools.registry import plan_tool_calls


class MemoryContextTest(unittest.TestCase):
    def test_extracts_recent_business_context(self) -> None:
        memory = build_memory_context(
            "conv-1",
            [
                {
                    "question": "ticket status",
                    "answer": "ticket answer",
                    "intent": "ticket_status",
                    "sources": [{"type": "ticket", "id": 12, "title": "Dorm leak"}],
                    "businessCards": [{"type": "appointment", "id": 9, "servicePointName": "Print desk"}],
                    "actionDrafts": [{"type": "reply_ticket_draft", "payload": {"ticketId": 12}}],
                },
                {
                    "question": "print slots",
                    "answer": "slot answer",
                    "intent": "service_point_slots",
                    "businessCards": [{"type": "service_point", "id": 3, "name": "Print desk"}],
                },
            ],
            None,
        )

        snapshot = memory["businessContext"]
        self.assertEqual(12, snapshot["lastTicket"]["id"])
        self.assertEqual(9, snapshot["lastAppointment"]["id"])
        self.assertEqual(3, snapshot["lastServicePoint"]["id"])
        self.assertEqual("reply_ticket_draft", snapshot["lastActionDraft"]["type"])

    def test_long_history_is_truncated_and_compressed(self) -> None:
        history = [
            {
                "question": "question-%s" % index,
                "answer": "answer-%s" % index,
                "intent": "general",
            }
            for index in range(8)
        ]

        memory = build_memory_context("conv-2", history, None, max_turns=3)

        self.assertEqual(3, len(memory["recentTurns"]))
        self.assertEqual("question-5", memory["recentTurns"][0]["question"])
        self.assertIn("question-4", memory["compressedSummary"])

    def test_tool_planning_uses_memory_ids(self) -> None:
        ticket_calls = plan_tool_calls("ticket_status", "that ticket", {"lastTicket": {"id": 12}})
        slot_calls = plan_tool_calls("service_point_slots", "available slots", {"lastServicePoint": {"id": 3}})

        self.assertEqual("query_ticket_detail", ticket_calls[0]["toolName"])
        self.assertEqual(12, ticket_calls[0]["arguments"]["ticketId"])
        self.assertEqual("query_service_point_slots", slot_calls[0]["toolName"])
        self.assertEqual(3, slot_calls[0]["arguments"]["servicePointId"])

    def test_ticket_followup_uses_live_tool_fact_for_reply_draft(self) -> None:
        memory = build_memory_context(
            "conv-3",
            [
                {
                    "question": "my ticket status",
                    "answer": "the ticket needs a reply",
                    "intent": "ticket_status",
                    "businessCards": [{"type": "ticket", "id": 12, "title": "Dorm leak", "status": 2, "studentReplyRequired": 1}],
                }
            ],
            None,
        )
        state = {
            "messages": [],
            "user_input": "that ticket needs supplement",
            "retrieved_context": "",
            "knowledge_sources": [],
            "response": "evaluation answer",
            "intent": "general",
            "escalate": False,
            "knowledge_initialized": True,
            "service_points": [],
            "tickets": memory_ticket_list(memory),
            "appointments": [],
            "recommended_service_points": [],
            "user_id": 2006,
            "role": "student",
            "trace_id": "test-memory",
            "business_tool_results": [],
            "memory_context": memory,
            "agent_plan": {},
            "execution_records": [],
            "generation_record": {},
            "fallback_records": [],
        }

        state.update(detect_intent(state))
        stale_payload = build_structured_response(state).model_dump()
        self.assertNotIn(
            "reply_ticket_draft",
            [item.get("type") for item in stale_payload["actionDrafts"]],
        )

        state["business_tool_results"] = [{
            "toolName": "query_ticket_detail",
            "success": True,
            "data": {"id": 12, "studentReplyRequired": 1, "status": 2},
            "count": 1,
        }]
        payload = build_structured_response(state).model_dump()

        self.assertEqual("ticket_status", payload["intent"])
        self.assertIn("reply_ticket_draft", [item.get("type") for item in payload["actionDrafts"]])


if __name__ == "__main__":
    unittest.main()
