from __future__ import annotations

import os
import unittest
from unittest.mock import MagicMock, patch

from agent.campus_support_agent import knowledge_hits_to_sources
from rag.retriever import (
    CampusKnowledgeRetriever,
    KnowledgeDocument,
    KnowledgeHit,
    finalize_fused_hits,
)


class RagHybridRetrievalTest(unittest.TestCase):
    def setUp(self) -> None:
        self.environment = patch.dict(os.environ, self._environment(), clear=False)
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_rc_u15_semantic_rewrite_uses_vector_candidate_without_exact_terms(self) -> None:
        retriever = self._retriever([
            self._document(1, "校园卡遗失后，应先在服务中心挂失，再携带学生证补办。", "校园卡遗失"),
        ])
        target = retriever.chunks[0]
        self._healthy_milvus(retriever, [self._hit(target, 0.91, "milvus")])

        hits = retriever.retrieve_documents("证件找不到了该怎么处理")

        self.assertEqual([1], [hit.metadata["knowledgeId"] for hit in hits])
        self.assertEqual(("milvus",), hits[0].retrievers)

    def test_rc_u16_exact_identifier_is_recalled_by_bm25(self) -> None:
        retriever = self._retriever([
            self._document(1, "普通打印问题由服务台处理。", "普通打印"),
            self._document(2, "设备编号 ZX-2048 的退款凭证须保留七天。", "专用设备规则"),
        ])
        self._unavailable_milvus(retriever)

        hits = retriever.retrieve_documents("ZX-2048 退款凭证")

        self.assertEqual(2, hits[0].metadata["knowledgeId"])
        self.assertEqual(("bm25",), hits[0].retrievers)
        self.assertGreater(hits[0].retriever_scores["bm25"], 0.0)

    def test_rc_u17_vector_and_bm25_rankings_are_combined_by_rrf(self) -> None:
        retriever = self._retriever([
            self._document(1, "青岚预约材料包括身份证和学生证。", "青岚预约"),
            self._document(2, "其他预约只需要申请表。", "普通预约"),
        ])
        target = retriever.chunks[0]
        self._healthy_milvus(retriever, [self._hit(target, 0.88, "milvus")])

        hits = retriever.retrieve_documents("青岚预约材料")

        self.assertEqual(1, hits[0].metadata["knowledgeId"])
        self.assertEqual(("milvus", "bm25"), hits[0].retrievers)
        self.assertEqual({"milvus", "bm25"}, set(hits[0].retriever_scores))
        self.assertGreater(hits[0].fusion_score or 0.0, 1.0 / 61.0)

    def test_rc_u18_unavailable_milvus_switches_to_faiss_plus_bm25(self) -> None:
        retriever = self._retriever([
            self._document(1, "星河打印点支持票据补打。", "星河打印"),
        ])
        chunk = retriever.chunks[0]
        self._unavailable_milvus(retriever, search_result=[])
        vector_document = MagicMock()
        vector_document.page_content = chunk.chunk_content
        vector_document.metadata = chunk.metadata()
        retriever.vectorstore = MagicMock()
        retriever.vectorstore.similarity_search_with_score.return_value = [(vector_document, 0.1)]

        hits = retriever.retrieve_documents("星河打印票据")

        self.assertEqual(("faiss", "bm25"), hits[0].retrievers)
        retriever.milvus.search.assert_called_once()
        retriever.vectorstore.similarity_search_with_score.assert_called_once()

    def test_rc_u19_bm25_survives_vector_outage_and_unrelated_query_returns_no_source(self) -> None:
        retriever = self._retriever([
            self._document(1, "云舟快递柜异常码为 E417。", "云舟快递"),
        ])
        self._unavailable_milvus(retriever)
        retriever.vectorstore = None

        self.assertEqual(1, retriever.retrieve_documents("E417 异常码")[0].metadata["knowledgeId"])
        self.assertEqual([], retriever.retrieve_documents("完全不存在的游泳馆规则"))

    def test_bm25_query_coverage_rejects_single_generic_fragment(self) -> None:
        retriever = self._retriever([
            self._document(1, "宿舍设施损坏后可提交报修。", "宿舍报修"),
        ])
        self._unavailable_milvus(retriever)

        hits = retriever.retrieve_documents("宿舍是否允许饲养翼龙作为宠物")

        self.assertEqual([], hits)

    def test_rc_u20_topic_match_cannot_bypass_minimum_quality_threshold(self) -> None:
        retriever = self._retriever([
            self._document(1, "打印退款需要保留订单号。", "打印退款"),
        ])
        chunk = retriever.chunks[0]
        self._healthy_milvus(retriever, [self._hit(chunk, 0.01, "milvus")])
        retriever.bm25_index = MagicMock()
        retriever.bm25_index.search.return_value = []

        hits = retriever.retrieve_documents("请给我帮助", topic_keywords=("打印",))

        self.assertEqual([], hits)

    def test_rc_u21_candidates_are_overfetched_before_version_filtering(self) -> None:
        retriever = self._retriever([
            self._document(index, f"第 {index} 份候选知识。", f"候选 {index}")
            for index in range(1, 5)
        ])
        stale = []
        for chunk in retriever.chunks[:3]:
            stale_metadata = {**chunk.metadata(), "knowledgeVersion": "old-v0"}
            stale.append(KnowledgeHit(chunk.chunk_content, stale_metadata, 0.99, "milvus"))
        current = self._hit(retriever.chunks[3], 0.70, "milvus")
        self._healthy_milvus(retriever, stale + [current])
        retriever.bm25_index = MagicMock()
        retriever.bm25_index.search.return_value = []

        hits = retriever.retrieve_documents("语义候选", limit=3)

        self.assertEqual([4], [hit.metadata["knowledgeId"] for hit in hits])
        self.assertEqual(12, retriever.milvus.search.call_args.args[1])

    def test_rc_u22_one_document_cannot_fill_all_top_k_slots(self) -> None:
        retriever = self._retriever([
            self._document(1, "".join(f"甲段规则{index:03d}。" for index in range(80)), "甲知识"),
            self._document(2, "乙文档提供独立的有效事实。", "乙知识"),
        ], chunk_size=55, overlap=5)
        first_document = [chunk for chunk in retriever.chunks if chunk.knowledge_id == 1]
        second_document = [chunk for chunk in retriever.chunks if chunk.knowledge_id == 2]
        vector_hits = [
            self._hit(first_document[0], 0.95, "milvus"),
            self._hit(first_document[2], 0.90, "milvus"),
            self._hit(first_document[1], 0.85, "milvus"),
            self._hit(second_document[0], 0.80, "milvus"),
        ]
        self._healthy_milvus(retriever, vector_hits)
        retriever.bm25_index = MagicMock()
        retriever.bm25_index.search.return_value = []

        hits = retriever.retrieve_documents("语义排序", limit=3)

        knowledge_ids = [hit.metadata["knowledgeId"] for hit in hits]
        self.assertLessEqual(knowledge_ids.count(1), 2)
        self.assertIn(2, knowledge_ids)

    def test_rc_u23_adjacent_overlapping_chunks_are_merged_without_duplicate_text(self) -> None:
        left = self._fused_hit("chunk-a", 1, 0, "办理材料包括身份证和学生证", 0.04)
        right = self._fused_hit("chunk-b", 1, 1, "学生证，随后到窗口核验", 0.03)

        hits = finalize_fused_hits([left, right], 3, 2, 6000)

        self.assertEqual(1, len(hits))
        self.assertEqual("办理材料包括身份证和学生证，随后到窗口核验", hits[0].content)
        self.assertEqual([0, 1], hits[0].metadata["chunkIndexes"])

    def test_rc_u24_old_knowledge_or_index_candidates_are_removed_before_fusion(self) -> None:
        retriever = self._retriever([
            self._document(1, "当前版本事实。", "当前知识"),
            self._document(2, "另一条当前事实。", "另一知识"),
        ])
        wrong_index = KnowledgeHit(
            retriever.chunks[0].chunk_content,
            {**retriever.chunks[0].metadata(), "indexVersion": "old-index"},
            0.99,
            "milvus",
        )
        current = self._hit(retriever.chunks[1], 0.60, "milvus")
        self._healthy_milvus(retriever, [wrong_index, current])
        retriever.bm25_index = MagicMock()
        retriever.bm25_index.search.return_value = []

        hits = retriever.retrieve_documents("版本隔离")

        self.assertEqual([2], [hit.metadata["knowledgeId"] for hit in hits])

    def test_long_document_tail_and_distractors_return_the_correct_chunk(self) -> None:
        tail_fact = "尾部验证码 TAIL-7788 仅在晚间窗口核验。"
        retriever = self._retriever([
            self._document(1, "前置说明。" * 100 + tail_fact, "长文档规则"),
            self._document(2, "相似标题但只介绍白天窗口。", "长文档规则说明"),
            self._document(3, "TAIL 只是课程缩写，不是验证码。", "干扰文档"),
        ], chunk_size=90, overlap=10)
        self._unavailable_milvus(retriever)

        hits = retriever.retrieve_documents("TAIL-7788 晚间核验")

        self.assertEqual(1, hits[0].metadata["knowledgeId"])
        self.assertGreater(hits[0].metadata["chunkIndex"], 0)
        self.assertIn("TAIL-7788", hits[0].content)

    def test_formal_keywords_participate_in_bm25_without_changing_chunk_content(self) -> None:
        document = self._document(7, "办理地点在综合服务大厅。", "校务办理")
        document.keywords = ["KX-991"]
        retriever = self._retriever([document])
        self._unavailable_milvus(retriever)

        hits = retriever.retrieve_documents("KX-991")

        self.assertEqual(7, hits[0].metadata["knowledgeId"])
        self.assertNotIn("KX-991", hits[0].content)

    def test_bm25_parameter_change_produces_a_new_index_version(self) -> None:
        documents = [self._document(1, "参数指纹必须与实际 BM25 行为一致。", "参数指纹")]
        original = self._retriever(documents).index_version
        with patch.dict(os.environ, {"RAG_BM25_TITLE_WEIGHT": "3.0"}, clear=False):
            changed = self._retriever(documents).index_version

        self.assertNotEqual(original, changed)

    def test_bm25_query_cache_returns_isolated_lists_and_has_a_fixed_capacity(self) -> None:
        retriever = self._retriever([
            self._document(1, "设备编号 ZX-2048 的退款凭证须保留七天。", "专用设备规则"),
        ])
        index = retriever.bm25_index
        self.assertIsNotNone(index)

        first = index.search("ZX-2048 退款凭证", 12)
        first.clear()
        second = index.search("ZX-2048 退款凭证", 12)

        self.assertEqual(1, len(second))
        self.assertIsNot(first, second)
        # 空结果同样进入缓存，避免无答案高频问题反复执行分词和全索引扫描。
        for query_index in range(index._query_cache_capacity + 8):
            index.search(f"不存在的隔离查询 {query_index}", 12)
        self.assertLessEqual(len(index._query_cache), index._query_cache_capacity)

    def test_sources_are_deduplicated_and_keep_chunk_and_fusion_evidence(self) -> None:
        first = self._fused_hit("chunk-a", 9, 0, "第一段实际上下文", 0.04)
        second = self._fused_hit("chunk-c", 9, 2, "第三段实际上下文", 0.03)

        sources = knowledge_hits_to_sources([first, second])

        self.assertEqual(1, len(sources))
        self.assertEqual([0, 2], sources[0].chunkIndexes)
        self.assertEqual("index-v1", sources[0].indexVersion)
        self.assertEqual(["bm25"], sources[0].retrievers)
        self.assertIn("第一段实际上下文", sources[0].snippet or "")
        self.assertIn("第三段实际上下文", sources[0].snippet or "")

    def _retriever(
        self,
        documents: list[KnowledgeDocument],
        chunk_size: int = 80,
        overlap: int = 10,
    ) -> CampusKnowledgeRetriever:
        with patch.dict(os.environ, {
            "RAG_CHUNK_SIZE": str(chunk_size),
            "RAG_CHUNK_OVERLAP": str(overlap),
        }, clear=False), patch("rag.retriever.vector_dependencies_enabled", return_value=False):
            retriever = CampusKnowledgeRetriever()
            retriever.reload_documents(documents, "knowledge-v1", "ai_knowledge")
        retriever.vectorstore = None
        return retriever

    @staticmethod
    def _document(knowledge_id: int, content: str, title: str) -> KnowledgeDocument:
        return KnowledgeDocument(
            id=knowledge_id,
            title=title,
            content=content,
            keywords=[],
            category="校园服务",
            source="ai_knowledge",
        )

    @staticmethod
    def _hit(chunk, score: float, retriever: str) -> KnowledgeHit:
        return KnowledgeHit(
            content=chunk.chunk_content,
            metadata=chunk.metadata(),
            score=score,
            retriever=retriever,
        )

    @staticmethod
    def _healthy_milvus(retriever: CampusKnowledgeRetriever, hits: list[KnowledgeHit]) -> None:
        retriever.milvus = MagicMock()
        # Retriever 会冻结构造时的后端配置；测试替换后端实例时必须同步声明该实例已配置。
        retriever.milvus_configured = True
        retriever.milvus.available.return_value = True
        retriever.milvus.connected = True
        retriever.milvus.last_error = None
        retriever.milvus.search.return_value = hits

    @staticmethod
    def _unavailable_milvus(
        retriever: CampusKnowledgeRetriever,
        search_result: list[KnowledgeHit] | None = None,
    ) -> None:
        retriever.milvus = MagicMock()
        if search_result is None:
            retriever.milvus_configured = False
            retriever.milvus.available.return_value = False
            retriever.milvus.search.return_value = []
        else:
            # 显式 search_result 用于模拟“已配置但搜索失败”，从而覆盖 FAISS 降级路径。
            retriever.milvus_configured = True
            retriever.milvus.available.return_value = True
            retriever.milvus.search.return_value = search_result
        retriever.milvus.connected = False
        retriever.milvus.last_error = "MilvusUnavailable"

    @staticmethod
    def _fused_hit(
        chunk_id: str,
        knowledge_id: int,
        chunk_index: int,
        content: str,
        fusion_score: float,
    ) -> KnowledgeHit:
        return KnowledgeHit(
            content=content,
            metadata={
                "chunkId": chunk_id,
                "knowledgeId": knowledge_id,
                "chunkIndex": chunk_index,
                "title": "合成知识",
                "category": "校园服务",
                "source": "ai_knowledge",
                "knowledgeVersion": "knowledge-v1",
                "indexVersion": "index-v1",
            },
            score=2.0,
            retriever="bm25",
            normalized_score=2.0 / 3.0,
            fusion_score=fusion_score,
            retrievers=("bm25",),
            retriever_scores={"bm25": 2.0},
            normalized_retriever_scores={"bm25": 2.0 / 3.0},
        )

    @staticmethod
    def _environment() -> dict[str, str]:
        return {
            "AI_EMBEDDING_PROVIDER": "local",
            "AI_EMBEDDING_MODEL": "local-hash-v1",
            "AI_EMBEDDING_DIMENSION": "8",
            "AI_EMBEDDING_ALGORITHM_REVISION": "local-hash-v1",
            "AI_EMBEDDING_DEPLOYMENT_REVISION": "stage-c-tests",
            "AI_LOCAL_EMBEDDINGS": "true",
            "MILVUS_ENABLED": "false",
            "OPENAI_API_KEY": "",
            "RAG_LEXICAL_IMPLEMENTATION": "okapi-bm25-v1",
            "RAG_TOKENIZER_REVISION": "alnum-cjk-2-4-v1",
            "RAG_BM25_K1": "1.5",
            "RAG_BM25_B": "0.75",
            "RAG_BM25_TITLE_WEIGHT": "2.0",
            "RAG_BM25_CATEGORY_WEIGHT": "1.5",
            "RAG_BM25_KEYWORD_WEIGHT": "2.0",
            "RAG_VECTOR_CANDIDATE_K": "12",
            "RAG_LEXICAL_CANDIDATE_K": "12",
            "RAG_RRF_K": "60",
            "RAG_MAX_CHUNKS_PER_KNOWLEDGE": "2",
            "RAG_CONTEXT_MAX_CHARACTERS": "6000",
            "RAG_MIN_MILVUS_SCORE": "0.2",
            "RAG_MIN_FAISS_SCORE": "0.2",
            "RAG_MIN_BM25_SCORE": "0.2",
        }


if __name__ == "__main__":
    unittest.main()
