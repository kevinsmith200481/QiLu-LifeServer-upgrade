from __future__ import annotations

import os
import unittest
from unittest.mock import MagicMock, patch

import agent.campus_support_agent as support_agent
from agent.campus_support_agent import filter_knowledge_hits, should_skip_knowledge_retrieval
from rag.chunking import KnowledgeChunk
from rag.retriever import CampusKnowledgeRetriever, KnowledgeDocument, KnowledgeHit, LocalHashEmbeddings, keyword_retrieve, normalize_hits, rewrite_query


class RagQualityTest(unittest.TestCase):
    def test_local_hash_query_rewrite_is_vector_equivalent(self) -> None:
        embeddings = LocalHashEmbeddings(384)
        question = "校园卡补办窗口在哪里"

        self.assertEqual(
            embeddings.embed_query(question),
            embeddings.embed_query(rewrite_query(question)),
        )

    def test_faiss_l2_normalization_can_reject_unrelated_candidates(self) -> None:
        hits = normalize_hits([
            KnowledgeHit(content="related", metadata={}, score=1.0, retriever="faiss"),
            KnowledgeHit(content="unrelated", metadata={}, score=2.0, retriever="faiss"),
        ])

        self.assertEqual(0.5, hits[0].normalized_score)
        self.assertEqual(0.0, hits[1].normalized_score)

    def test_retrieval_policy_no_longer_depends_on_keyword_skip(self) -> None:
        class CountingRetriever:
            def __init__(self):
                self.calls = 0

            def is_initialized(self):
                return True

            def retrieve_documents(self, query, limit=3, topic_keywords=(), trace_span=None):
                self.calls += 1
                return []

        retriever = CountingRetriever()
        original_retriever = getattr(support_agent.retrieve_context, "retriever", None)
        had_retriever = hasattr(support_agent.retrieve_context, "retriever")
        original_skip = support_agent.should_skip_knowledge_retrieval
        support_agent.retrieve_context.retriever = retriever
        support_agent.should_skip_knowledge_retrieval = lambda query: (_ for _ in ()).throw(
            AssertionError("policy-routed requests must not use the keyword skip function")
        )
        try:
            support_agent.retrieve_context({
                "user_input": "预约需要准备什么材料？",
                "trace_id": "rag-policy-test",
                "retrieval_mode": "RAG_ONLY",
            })
            support_agent.retrieve_context({
                "user_input": "我的预约记录发生了什么",
                "trace_id": "rag-policy-test",
                "retrieval_mode": "BUSINESS_ONLY",
            })
        finally:
            support_agent.should_skip_knowledge_retrieval = original_skip
            if had_retriever:
                support_agent.retrieve_context.retriever = original_retriever
            else:
                delattr(support_agent.retrieve_context, "retriever")
        self.assertEqual(1, retriever.calls)

    def test_rewrite_query_keeps_business_terms_and_cjk_fragments(self) -> None:
        rewritten = rewrite_query("打印服务可用时段")

        self.assertIn("打印", rewritten)
        self.assertIn("服务", rewritten)
        self.assertIn("时段", rewritten)

    def test_rag_top_k_is_bounded(self) -> None:
        with patch.dict(os.environ, {"RAG_TOP_K": "1"}, clear=False):
            self.assertEqual(1, support_agent.rag_top_k())
        with patch.dict(os.environ, {"RAG_TOP_K": "99"}, clear=False):
            self.assertEqual(10, support_agent.rag_top_k())

    def test_java_ticket_status_is_rendered_for_users(self) -> None:
        self.assertEqual("处理中", support_agent.display_ticket_status("PROCESSING", 2))
        self.assertEqual("已受理", support_agent.display_ticket_status("ACCEPTED", 1))

    def test_admin_log_query_skips_knowledge_retrieval(self) -> None:
        self.assertTrue(should_skip_knowledge_retrieval("最近后台操作日志"))
        self.assertEqual("最近后台操作日志", rewrite_query("最近后台操作日志"))

    def test_appointment_query_skips_knowledge_retrieval(self) -> None:
        self.assertTrue(should_skip_knowledge_retrieval(
            "\u6211\u7684\u9884\u7ea6\u8bb0\u5f55\u53d1\u751f\u4e86\u4ec0\u4e48"
        ))
        self.assertFalse(should_skip_knowledge_retrieval(
            "\u56fe\u4e66\u9986\u9644\u8fd1\u54ea\u91cc\u53ef\u4ee5\u6253\u5370\u6750\u6599"
        ))

    def test_low_score_milvus_is_filtered_by_retriever_threshold(self) -> None:
        old_value = os.environ.get("RAG_MIN_MILVUS_SCORE")
        os.environ["RAG_MIN_MILVUS_SCORE"] = "0.2"
        try:
            hits = normalize_hits([
                KnowledgeHit("weak", {"title": "weak"}, 0.05, "milvus"),
                KnowledgeHit("strong", {"title": "strong"}, 0.8, "milvus"),
            ])

            filtered = filter_knowledge_hits(hits)

            self.assertEqual(["strong"], [hit.content for hit in filtered])
        finally:
            if old_value is None:
                os.environ.pop("RAG_MIN_MILVUS_SCORE", None)
            else:
                os.environ["RAG_MIN_MILVUS_SCORE"] = old_value

    def test_named_intent_keeps_only_matching_topic_hits(self) -> None:
        with patch.dict(os.environ, {"RAG_MIN_MILVUS_SCORE": "0.0"}, clear=False):
            hits = normalize_hits([
                KnowledgeHit("就业咨询支持简历修改。", {"title": "就业咨询", "category": "咨询服务"}, 0.8, "milvus"),
                KnowledgeHit("宿舍网络异常时提交网络工单。", {"title": "网络故障", "category": "网络服务"}, 0.8, "milvus"),
                KnowledgeHit("打印扣费未出纸时保留订单号。", {"title": "打印退款", "category": "打印服务"}, 0.8, "milvus"),
            ])

            filtered = filter_knowledge_hits(hits, intent="printing")

        self.assertEqual(["打印退款"], [hit.metadata["title"] for hit in filtered])

    def test_zero_score_is_not_a_general_knowledge_source(self) -> None:
        with patch.dict(os.environ, {"RAG_MIN_MILVUS_SCORE": "0.0"}, clear=False):
            hits = normalize_hits([KnowledgeHit("unrelated", {"title": "weak"}, -0.02, "milvus")])

            self.assertEqual([], filter_knowledge_hits(hits))

    def test_faiss_and_keyword_scores_are_normalized(self) -> None:
        hits = normalize_hits([
            KnowledgeHit("faiss", {}, 0.25, "faiss"),
            KnowledgeHit("keyword", {}, 2.0, "keyword"),
        ])

        self.assertGreater(hits[0].normalized_score or 0.0, 0.7)
        self.assertGreater(hits[1].normalized_score or 0.0, 0.6)

    def test_keyword_retrieve_provides_keyword_fallback(self) -> None:
        documents = [
            KnowledgeDocument(1, "快递异常", "快递取件码异常可到人工窗口处理。", ["快递", "取件码"], "express", "ai_knowledge")
        ]

        hits = keyword_retrieve(rewrite_query("快递取件码异常"), documents, limit=3)

        self.assertEqual(1, len(hits))
        self.assertEqual("keyword", hits[0].retriever)

    def test_low_quality_healthy_milvus_does_not_duplicate_ranking_with_faiss(self) -> None:
        retriever = self._tiered_retriever()
        retriever.milvus.search.return_value = [
            KnowledgeHit(
                retriever.chunks[0].chunk_content,
                retriever.chunks[0].metadata(),
                0.05,
                "milvus",
            )
        ]
        vector_document = MagicMock()
        vector_document.page_content = "usable faiss result"
        vector_document.metadata = {"knowledgeVersion": "formal-v1"}
        retriever.vectorstore.similarity_search_with_score.return_value = [(vector_document, 0.1)]

        hits = retriever.retrieve_documents("unrelated query")

        self.assertEqual([], hits)
        retriever.vectorstore.similarity_search_with_score.assert_not_called()

    def test_filtered_milvus_still_combines_with_bm25(self) -> None:
        retriever = self._tiered_retriever()
        retriever.milvus.search.return_value = [KnowledgeHit(
            retriever.chunks[0].chunk_content,
            retriever.chunks[0].metadata(),
            0.05,
            "milvus",
        )]

        hits = retriever.retrieve_documents("printing refund", topic_keywords=("printing",))

        self.assertEqual(["bm25"], [hit.retriever for hit in hits])
        self.assertEqual(["Printing refund instructions."], [hit.content for hit in hits])
        retriever.vectorstore.similarity_search_with_score.assert_not_called()

    def test_usable_milvus_hit_stops_fallback_chain(self) -> None:
        retriever = self._tiered_retriever()
        retriever.milvus.search.return_value = [
            KnowledgeHit(
                retriever.chunks[0].chunk_content,
                retriever.chunks[0].metadata(),
                0.9,
                "milvus",
            )
        ]

        hits = retriever.retrieve_documents("formal answer")

        self.assertEqual(["milvus"], [hit.retriever for hit in hits])
        retriever.vectorstore.similarity_search_with_score.assert_not_called()

    @staticmethod
    def _tiered_retriever() -> CampusKnowledgeRetriever:
        retriever = CampusKnowledgeRetriever.__new__(CampusKnowledgeRetriever)
        retriever.knowledge_source = "ai_knowledge"
        retriever.knowledge_version = "formal-v1"
        retriever.index_version = "index-v1"
        retriever.documents = [
            KnowledgeDocument(
                1,
                "Printing refund",
                "Printing refund instructions.",
                ["printing", "refund"],
                "printing",
                "ai_knowledge",
            )
        ]
        retriever.milvus = MagicMock()
        retriever.milvus.available.return_value = True
        retriever.milvus.connected = True
        retriever.milvus.last_error = None
        retriever.vectorstore = MagicMock()
        retriever.chunks = [KnowledgeChunk(
            chunk_id="c" * 64,
            knowledge_id=1,
            chunk_index=0,
            chunk_content="Printing refund instructions.",
            embedding_text="Printing refund\nprinting\nPrinting refund instructions.",
            title="Printing refund",
            category="printing",
            source="ai_knowledge",
            knowledge_version="formal-v1",
            index_version="index-v1",
            chunk_hash="d" * 64,
        )]
        return retriever


if __name__ == "__main__":
    unittest.main()
