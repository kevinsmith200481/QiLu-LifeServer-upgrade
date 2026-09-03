from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from pydantic import ValidationError

from app.schemas import CampusAssistantRequest, CampusAssistantResponse


FIXTURE_ROOT = (
    Path(__file__).resolve().parent.parent
    / "qilu-ai-service"
    / "src"
    / "test"
    / "resources"
)


class CampusMemoryContractTest(unittest.TestCase):
    """使用与 Java 相同的 fixture 验证跨语言字段、类型与严格边界。"""

    def setUp(self) -> None:
        self.request_payload = json.loads(
            (FIXTURE_ROOT / "campus-memory-request.json").read_text(encoding="utf-8")
        )

    def test_java_request_fixture_is_accepted(self) -> None:
        request = CampusAssistantRequest.model_validate(self.request_payload)

        self.assertEqual("turn-contract-002", request.turnId)
        self.assertEqual(12, request.memory.entities.tickets[0].id)
        self.assertEqual("2", request.memory.schemaVersion)

    def test_java_response_fixture_is_accepted(self) -> None:
        payload = json.loads(
            (FIXTURE_ROOT / "campus-memory-response.json").read_text(encoding="utf-8")
        )
        response = CampusAssistantResponse.model_validate(payload)

        self.assertEqual(["ticket"], response.memoryDiagnostics.entityTypes)
        self.assertFalse(response.memoryDiagnostics.degraded)

    def test_legacy_request_without_memory_remains_compatible(self) -> None:
        request = CampusAssistantRequest(question="legacy request")

        self.assertIsNone(request.turnId)
        self.assertIsNone(request.memory)

    def test_unknown_memory_field_is_rejected(self) -> None:
        payload = copy.deepcopy(self.request_payload)
        payload["memory"]["entities"]["tickets"][0]["status"] = "processing"

        with self.assertRaises(ValidationError):
            CampusAssistantRequest.model_validate(payload)

    def test_more_than_three_same_type_entities_are_rejected(self) -> None:
        payload = copy.deepcopy(self.request_payload)
        payload["memory"]["entities"]["tickets"] = [
            {"id": entity_id, "lastSeenTurnId": "turn", "lastSeenMessageId": entity_id}
            for entity_id in range(1, 5)
        ]

        with self.assertRaises(ValidationError):
            CampusAssistantRequest.model_validate(payload)

    def test_wrong_schema_version_is_rejected(self) -> None:
        payload = copy.deepcopy(self.request_payload)
        payload["memory"]["schemaVersion"] = "1"

        with self.assertRaises(ValidationError):
            CampusAssistantRequest.model_validate(payload)

    def test_string_entity_id_is_not_coerced(self) -> None:
        payload = copy.deepcopy(self.request_payload)
        payload["memory"]["entities"]["tickets"][0]["id"] = "12"

        with self.assertRaises(ValidationError):
            CampusAssistantRequest.model_validate(payload)

    def test_diagnostics_cannot_carry_body_or_entity_id(self) -> None:
        payload = json.loads(
            (FIXTURE_ROOT / "campus-memory-response.json").read_text(encoding="utf-8")
        )
        payload["memoryDiagnostics"]["rollingSummary"] = "must not pass"
        payload["memoryDiagnostics"]["ticketId"] = 12

        with self.assertRaises(ValidationError):
            CampusAssistantResponse.model_validate(payload)


if __name__ == "__main__":
    unittest.main()
