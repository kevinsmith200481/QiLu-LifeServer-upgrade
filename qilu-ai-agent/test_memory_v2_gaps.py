from __future__ import annotations

import json
import os
import sys
import unittest
from pathlib import Path

from agent.memory import build_memory_context, build_request_memory_context
from app.schemas import CampusAssistantRequest


class MemoryV2GapTest(unittest.TestCase):
    """按阶段收敛 Memory v2 缺口；已实现项必须作为正常回归持续通过。"""

    def test_incomplete_turn_is_excluded_from_recent_turns(self) -> None:
        memory = build_memory_context(
            "session-a",
            [
                {"question": "complete question", "answer": "complete answer"},
                {"question": "unfinished question", "answer": ""},
            ],
            None,
        )

        self.assertEqual(1, len(memory["recentTurns"]))
        self.assertEqual("complete question", memory["recentTurns"][0]["question"])

    def test_thirty_turns_keep_the_earliest_ticket_after_restart(self) -> None:
        # 模拟 Java 阶段 E 在 30 轮后持久化并跨进程重新发送的 Memory v2。
        persisted_memory = {
            "mode": "v2",
            "schemaVersion": "2",
            "conversationId": "session-a",
            "recentTurns": [
                {
                    "turnId": "turn-%s" % index,
                    "question": "general-%s" % index,
                    "answer": "answer-%s" % index,
                    "intent": "general",
                }
                for index in range(22, 30)
            ],
            "rollingSummary": "用户较早咨询过校园维修事项，随后进行了其他校园话题交流。",
            "entities": {
                "tickets": [{"id": 12, "lastSeenTurnId": "turn-1", "lastSeenMessageId": 2}],
                "appointments": [],
                "servicePoints": [],
                "pendingActionDraft": None,
            },
            "lastProcessedMessageId": 60,
            "summaryVersion": 8,
            "truncated": True,
            "estimatedTokens": 420,
        }
        restarted_memory = build_request_memory_context(
            CampusAssistantRequest(
                conversationId="session-a",
                question="最早提到的工单怎么样",
                memory=persisted_memory,
            )
        )

        self.assertEqual(12, restarted_memory["businessContext"]["lastTicket"]["id"])

    def test_two_ticket_candidates_are_retained_for_clarification(self) -> None:
        memory = build_memory_context(
            "session-a",
            [
                {
                    "question": "show tickets 12 and 18",
                    "answer": "two tickets",
                    "businessCards": [
                        {"type": "ticket", "id": 12},
                        {"type": "ticket", "id": 18},
                    ],
                }
            ],
            None,
        )

        ticket_ids = [item["id"] for item in memory.get("entities", {}).get("tickets", [])]
        self.assertEqual([18, 12], ticket_ids)

    def test_client_context_cannot_restore_another_session_ticket(self) -> None:
        memory = build_memory_context(
            "session-b",
            [],
            {
                "lastTicket": {
                    "type": "ticket",
                    "id": 12,
                    "sourceConversationId": "session-a",
                }
            },
        )

        self.assertIsNone(memory["businessContext"]["lastTicket"])

    def test_request_keeps_turn_id_for_concurrent_pairing(self) -> None:
        request = CampusAssistantRequest(question="concurrent question", turnId="turn-stage-a-001")
        payload = request.model_dump() if hasattr(request, "model_dump") else request.dict()

        self.assertEqual("turn-stage-a-001", payload.get("turnId"))

    def test_memory_drops_sensitive_client_fields(self) -> None:
        memory = build_memory_context(
            "session-a",
            [],
            {
                "lastTicket": {
                    "type": "ticket",
                    "id": 12,
                    "phone": "19900000001",
                    "attachmentUrl": "https://acceptance.invalid/private/file",
                    "token": "acceptance-secret-marker",
                }
            },
        )

        self.assertEqual({"type": "ticket", "id": 12}, memory["businessContext"]["lastTicket"])


def main() -> int:
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(MemoryV2GapTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    summary = {
        "schemaVersion": 1,
        "testCount": result.testsRun,
        "expectedFailureCount": len(result.expectedFailures),
        "unexpectedSuccessCount": len(result.unexpectedSuccesses),
        "errorCount": len(result.errors),
        "failureCount": len(result.failures),
        "stageProgressValid": (
            result.testsRun == 6
            and len(result.expectedFailures) == 0
            and not result.unexpectedSuccesses
            and not result.errors
            and not result.failures
        ),
    }
    output_path = os.getenv("AI_MEMORY_GAP_RESULT_PATH", "").strip()
    if output_path:
        # 证据只记录计数，不记录问题正文、业务实体或异常原文。
        Path(output_path).write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if summary["stageProgressValid"] else 1


if __name__ == "__main__":
    sys.exit(main())
