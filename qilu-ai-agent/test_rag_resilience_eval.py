from __future__ import annotations

import unittest
from pathlib import Path

from rag.resilience_eval import (
    _percentile,
    _sync_documents,
    capture_process_resources,
)


class RagResilienceEvaluationTest(unittest.TestCase):
    def test_sync_documents_have_unique_formal_ids_and_sources(self) -> None:
        documents = _sync_documents(100, "unit")

        self.assertEqual(100, len(documents))
        self.assertEqual(100, len({document.id for document in documents}))
        self.assertTrue(all(document.source == "ai_knowledge" for document in documents))

    def test_process_resource_snapshot_is_non_negative(self) -> None:
        snapshot = capture_process_resources(Path.cwd())

        self.assertGreater(snapshot.handleCount, 0)
        self.assertGreater(snapshot.threadCount, 0)
        self.assertGreater(snapshot.workingSetBytes, 0)
        self.assertGreaterEqual(snapshot.tcpSocketCount, 0)

    def test_resilience_percentile_matches_quality_metric_contract(self) -> None:
        self.assertEqual(3.0, _percentile([1.0, 2.0, 3.0], 0.95))


if __name__ == "__main__":
    unittest.main()
