from __future__ import annotations

import unittest

from agent.memory import build_request_memory_context
from app.schemas import CampusAssistantRequest


def memory_payload(mode: str = "v2") -> dict:
    return {
        "mode": mode,
        "schemaVersion": "2",
        "conversationId": "session-v2",
        "recentTurns": [
            {
                "turnId": "turn-v2-1",
                "question": "查看 12 号工单",
                "answer": "已查询实时工单信息",
                "intent": "ticket_status",
            }
        ],
        "rollingSummary": "此前咨询过校园维修事项。",
        "entities": {
            "tickets": [
                {"id": 12, "lastSeenTurnId": "turn-v2-1", "lastSeenMessageId": 101}
            ],
            "appointments": [],
            "servicePoints": [],
            "pendingActionDraft": None,
        },
        "lastProcessedMessageId": 101,
        "summaryVersion": 3,
        "truncated": True,
        "estimatedTokens": 320,
    }


class MemoryV2Test(unittest.TestCase):
    def test_v2_uses_structured_window_summary_and_entities(self) -> None:
        request = CampusAssistantRequest(
            conversationId="session-v2",
            question="刚才那个工单怎么样？",
            history=[{
                "question": "poisoned legacy question",
                "answer": "poisoned legacy answer",
                "businessCards": [{"type": "ticket", "id": 999}],
            }],
            lastBusinessContext={"lastTicket": {"type": "ticket", "id": 998}},
            memory=memory_payload(),
        )

        context = build_request_memory_context(request)

        self.assertEqual("v2", context["mode"])
        self.assertEqual("此前咨询过校园维修事项。", context["rollingSummary"])
        self.assertEqual("turn-v2-1", context["recentTurns"][0]["turnId"])
        self.assertEqual(12, context["businessContext"]["lastTicket"]["id"])
        self.assertNotIn(999, [item["id"] for item in context["entities"]["tickets"]])

    def test_v2_sorts_candidates_and_does_not_choose_from_multiple(self) -> None:
        payload = memory_payload()
        payload["entities"]["tickets"] = [
            {"id": 12, "lastSeenTurnId": "turn-v2-1", "lastSeenMessageId": 101},
            {"id": 18, "lastSeenTurnId": "turn-v2-2", "lastSeenMessageId": 202},
        ]
        request = CampusAssistantRequest(
            conversationId="session-v2",
            question="其中一个怎么样？",
            memory=payload,
        )

        context = build_request_memory_context(request)

        self.assertEqual([18, 12], [item["id"] for item in context["entities"]["tickets"]])
        self.assertIsNone(context["businessContext"]["lastTicket"])

    def test_legacy_mode_keeps_old_request_path(self) -> None:
        request = CampusAssistantRequest(
            conversationId="session-v2",
            question="那个工单怎么样？",
            history=[{
                "question": "查看旧链路工单",
                "answer": "旧链路已查询",
                "businessCards": [{"type": "ticket", "id": 31}],
            }],
            memory=memory_payload(mode="legacy"),
        )

        context = build_request_memory_context(request)

        self.assertEqual("legacy", context["mode"])
        self.assertEqual(31, context["businessContext"]["lastTicket"]["id"])

    def test_conversation_mismatch_cannot_activate_v2_memory(self) -> None:
        request = CampusAssistantRequest(
            conversationId="session-other",
            question="那个工单怎么样？",
            lastBusinessContext={
                "lastTicket": {
                    "type": "ticket",
                    "id": 12,
                    "sourceConversationId": "session-v2",
                }
            },
            memory=memory_payload(),
        )

        context = build_request_memory_context(request)

        self.assertEqual("legacy", context["mode"])
        self.assertEqual([], context["entities"]["tickets"])
        self.assertIsNone(context["businessContext"]["lastTicket"])

    def test_shadow_keeps_legacy_effective_context_and_builds_v2_comparison(self) -> None:
        payload = memory_payload(mode="shadow")
        request = CampusAssistantRequest(
            conversationId="session-v2",
            question="刚才那个工单怎么样？",
            history=[{
                "question": "查看 12 号工单",
                "answer": "旧链路已查询",
                "businessCards": [{"type": "ticket", "id": 12}],
            }],
            memory=payload,
        )

        context = build_request_memory_context(request)

        self.assertEqual("shadow", context["mode"])
        self.assertEqual("legacy", context["effectiveMode"])
        self.assertEqual(12, context["businessContext"]["lastTicket"]["id"])
        self.assertEqual("v2", context["shadowV2"]["mode"])
        self.assertEqual(12, context["shadowV2"]["businessContext"]["lastTicket"]["id"])
        self.assertGreater(context["estimatedTokens"], 0)


if __name__ == "__main__":
    unittest.main()
