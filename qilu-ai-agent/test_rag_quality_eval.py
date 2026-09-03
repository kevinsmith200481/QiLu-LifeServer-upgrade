from __future__ import annotations

import os
import unittest
from unittest.mock import patch

from rag.quality_eval import _percentile, evaluate_path
from rag.quality_fixture import build_quality_fixture
from rag.retriever import CampusKnowledgeRetriever


class RagQualityEvaluationTest(unittest.TestCase):
    def test_fixture_meets_stage_f_schema_and_coverage(self) -> None:
        documents, cases = build_quality_fixture()

        self.assertGreaterEqual(len(documents), 12)
        self.assertGreaterEqual(len(cases), 60)
        self.assertEqual(len(cases), len({case["caseId"] for case in cases}))
        required_fields = {
            "caseId", "question", "expectedKnowledgeIds", "expectedChunkIndexes",
            "forbiddenKnowledgeIds", "answerable", "requiredTerms", "forbiddenTerms", "category",
        }
        self.assertTrue(all(required_fields == set(case) for case in cases))
        categories = {str(case["category"]) for case in cases}
        self.assertTrue({"semantic", "lexical", "long-middle", "long-tail", "no-answer", "engineering-boundary", "version-boundary"}.issubset(categories))

    def test_local_evaluation_records_faiss_and_bm25_candidates(self) -> None:
        environment = {
            "AI_SKIP_DOTENV": "true",
            "AI_EMBEDDING_PROVIDER": "local",
            "AI_EMBEDDING_MODEL": "local-hash-v1",
            "AI_EMBEDDING_DIMENSION": "384",
            "AI_EMBEDDING_ALGORITHM_REVISION": "local-hash-v1",
            "AI_EMBEDDING_DEPLOYMENT_REVISION": "rag-quality-unit",
            "AI_LOCAL_EMBEDDINGS": "true",
            "MILVUS_ENABLED": "false",
            "RAG_REQUIRED_BACKENDS": "bm25",
            "RAG_MIN_FAISS_SCORE": "0.2",
            "RAG_MIN_BM25_SCORE": "0.2",
        }
        documents, cases = build_quality_fixture()
        with patch.dict(os.environ, environment, clear=True):
            retriever = CampusKnowledgeRetriever()
            retriever.reload_documents(documents, "rag-quality-v1", "ai_knowledge")
            result = evaluate_path(retriever, cases[:5], "unit")

        self.assertEqual(5, result["caseCount"])
        self.assertEqual(5, result["vectorBackends"]["faiss"])
        self.assertGreater(result["averageCandidateCounts"]["lexical"], 0)

    def test_percentile_uses_nearest_rank(self) -> None:
        self.assertEqual(1.0, _percentile([1.0, 2.0, 3.0, 4.0], 0.25))
        self.assertEqual(4.0, _percentile([1.0, 2.0, 3.0, 4.0], 0.95))


if __name__ == "__main__":
    unittest.main()
