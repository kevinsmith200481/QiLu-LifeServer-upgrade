from __future__ import annotations

import math
import os
import unittest
from dataclasses import replace
from unittest.mock import MagicMock, patch

import rag.retriever as retriever_module
from rag.chunking import (
    ChunkConfig,
    EmbeddingDescriptor,
    KnowledgeIndexConfigurationError,
    KnowledgeIndexValidationError,
    LexicalDescriptor,
    build_index_fingerprints,
    build_knowledge_chunks,
    validate_embedding_vectors,
)
from rag.retriever import (
    CampusKnowledgeRetriever,
    KnowledgeDocument,
    MilvusCandidate,
    MilvusKnowledgeRetriever,
    build_embeddings,
    configure_faiss_search_threads,
    embedding_descriptor,
)


class RagChunkingTest(unittest.TestCase):
    def test_faiss_search_thread_configuration_is_bounded(self) -> None:
        with patch.dict(os.environ, {"RAG_FAISS_SEARCH_THREADS": "1"}):
            self.assertEqual(1, configure_faiss_search_threads())
        for invalid in ("0", "65", "not-an-integer"):
            with self.subTest(invalid=invalid), patch.dict(
                os.environ,
                {"RAG_FAISS_SEARCH_THREADS": invalid},
            ):
                with self.assertRaises(KnowledgeIndexConfigurationError):
                    configure_faiss_search_threads()

    def test_rc_u01_chinese_700_plus_characters_use_300_40_boundaries(self) -> None:
        content = "".join(f"第{index:04d}项甲规则；" for index in range(100))
        chunks = self._chunks([self._document(1, content)])

        self.assertGreater(len(content), 700)
        self.assertGreater(len(chunks), 2)
        self.assertTrue(all(0 < len(chunk.chunk_content) <= 300 for chunk in chunks))
        self.assertEqual(list(range(len(chunks))), [chunk.chunk_index for chunk in chunks])
        self.assertTrue(any(self._suffix_overlap(left.chunk_content, right.chunk_content) >= 40
                            for left, right in zip(chunks, chunks[1:])))

    def test_rc_u02_metadata_is_inherited_but_embedding_text_excludes_internal_fields(self) -> None:
        document = self._document(7, "正文只包含公开办理规则。", title="公开标题", category="办事分类")
        chunks = self._chunks([document], knowledge_version="kb-secret-version")
        chunk = chunks[0]

        self.assertEqual(7, chunk.knowledge_id)
        self.assertEqual("公开标题\n办事分类\n正文只包含公开办理规则。", chunk.embedding_text)
        self.assertNotIn("ai_knowledge", chunk.embedding_text)
        self.assertNotIn("kb-secret-version", chunk.embedding_text)
        self.assertNotIn(chunk.chunk_id, chunk.embedding_text)

    def test_rc_u03_document_order_does_not_change_per_document_chunk_indices(self) -> None:
        first = self._document(11, "甲" * 680)
        second = self._document(22, "乙" * 680)
        normal = self._chunks([first, second])
        reversed_chunks = self._chunks([second, first])

        self.assertEqual(self._group_signature(normal), self._group_signature(reversed_chunks))
        self.assertEqual(0, min(chunk.chunk_index for chunk in normal if chunk.knowledge_id == 11))
        self.assertEqual(0, min(chunk.chunk_index for chunk in normal if chunk.knowledge_id == 22))

    def test_rc_u04_empty_and_duplicate_chunks_are_rejected_deterministically(self) -> None:
        with self.assertRaises(KnowledgeIndexValidationError):
            self._chunks([self._document(1, "   ")])

        config = ChunkConfig(size=6, overlap=0, unit="character", algorithm="recursive-v1")
        chunks = self._chunks([self._document(1, "相同片段。\n\n相同片段。")], config=config)
        self.assertEqual(len({chunk.chunk_content for chunk in chunks}), len(chunks))

    def test_rc_u05_formal_ids_must_be_unique_positive_integers(self) -> None:
        invalid_documents = [
            [self._document(None, "正文")],
            [self._document(True, "正文")],
            [self._document(0, "正文")],
            [self._document(1, "正文甲"), self._document(1, "正文乙")],
        ]
        for documents in invalid_documents:
            with self.subTest(ids=[document.id for document in documents]):
                with self.assertRaises(KnowledgeIndexValidationError):
                    self._chunks(documents)

        retriever = self._loaded_retriever()
        old_state = (retriever.knowledge_version, retriever.index_version, list(retriever.chunks))
        for documents in invalid_documents:
            with self.subTest(active_unchanged_ids=[document.id for document in documents]):
                with self.assertRaises(KnowledgeIndexValidationError):
                    retriever.reload_documents(documents, "kb-invalid", "ai_knowledge")
                self.assertEqual(old_state, (
                    retriever.knowledge_version,
                    retriever.index_version,
                    retriever.chunks,
                ))

    def test_rc_u06_milvus_text_limits_fail_before_silent_truncation(self) -> None:
        config = ChunkConfig(size=5000, overlap=0, unit="character", algorithm="recursive-v1")
        with self.assertRaisesRegex(KnowledgeIndexValidationError, "chunkContent length"):
            self._chunks([self._document(1, "超" * 4200)], config=config)

    def test_all_milvus_metadata_limits_are_validated_before_reload(self) -> None:
        cases = [
            ({"title": "题" * 513}, "title length"),
            ({"category": "类" * 129}, "category length"),
        ]
        for overrides, expected_message in cases:
            with self.subTest(expected_message=expected_message):
                document = self._document(1, "正文", **overrides)
                with self.assertRaisesRegex(KnowledgeIndexValidationError, expected_message):
                    self._chunks([document])

        document = self._document(1, "正文")
        document.source = "源" * 513
        with self.assertRaisesRegex(KnowledgeIndexValidationError, "source length"):
            self._chunks([document])
        with self.assertRaisesRegex(KnowledgeIndexValidationError, "knowledgeVersion length"):
            self._chunks([self._document(1, "正文")], knowledge_version="v" * 129)

    def test_rc_u07_same_input_rebuild_is_fully_deterministic(self) -> None:
        documents = [self._document(1, "确定性正文。" * 120)]
        first = self._chunks(documents)
        second = self._chunks(documents)

        self.assertEqual(first, second)
        self.assertEqual(
            [(chunk.chunk_id, chunk.chunk_hash, chunk.index_version) for chunk in first],
            [(chunk.chunk_id, chunk.chunk_hash, chunk.index_version) for chunk in second],
        )

    def test_rc_u08_chunk_parameter_changes_index_version(self) -> None:
        base = self._fingerprints()
        changed_size = self._fingerprints(chunk=ChunkConfig(301, 40, "character", "recursive-v1"))
        changed_overlap = self._fingerprints(chunk=ChunkConfig(300, 39, "character", "recursive-v1"))
        changed_algorithm = self._fingerprints(chunk=ChunkConfig(300, 40, "character", "recursive-v2"))

        self.assertEqual(4, len({
            base.index_version,
            changed_size.index_version,
            changed_overlap.index_version,
            changed_algorithm.index_version,
        }))

    def test_long_unbroken_text_preserves_exact_300_40_character_windows(self) -> None:
        content = "".join(chr(0x4E00 + (index % 2000)) for index in range(700))
        chunks = self._chunks([self._document(1, content)])

        self.assertEqual([300, 300, 180], [len(chunk.chunk_content) for chunk in chunks])
        self.assertEqual(40, self._suffix_overlap(chunks[0].chunk_content, chunks[1].chunk_content))
        self.assertEqual(40, self._suffix_overlap(chunks[1].chunk_content, chunks[2].chunk_content))

    def test_rc_u09_local_provider_defaults_to_local_hash_model(self) -> None:
        environment = self._environment(MILVUS_ENABLED="true")
        environment.pop("AI_EMBEDDING_MODEL")
        with patch.dict(os.environ, environment, clear=True):
            descriptor = embedding_descriptor()

        self.assertEqual("local-hash-v1", descriptor.model)
        self.assertEqual("local-hash-v1", descriptor.algorithm_revision)

    def test_rc_u10_vector_dimension_mismatch_is_rejected_before_activation(self) -> None:
        retriever = self._loaded_retriever()
        old_state = (retriever.knowledge_version, retriever.index_version, list(retriever.chunks))
        invalid_embeddings = MagicMock()
        invalid_embeddings.embed_documents.return_value = [[0.0, 1.0]]
        retriever.milvus.reload.reset_mock()

        with patch("rag.retriever.vector_dependencies_enabled", return_value=True), patch.object(
            retriever,
            "_embeddings",
            return_value=invalid_embeddings,
        ):
            with self.assertRaises(KnowledgeIndexValidationError):
                retriever.reload_documents([self._document(2, "新版本正文")], "kb-v2", "ai_knowledge")

        self.assertEqual(old_state, (retriever.knowledge_version, retriever.index_version, retriever.chunks))
        retriever.milvus.reload.assert_not_called()

    def test_rc_u11_nan_infinity_and_non_numeric_vectors_are_rejected(self) -> None:
        for value in [math.nan, math.inf, -math.inf, "not-number", True]:
            with self.subTest(value=value):
                with self.assertRaises(KnowledgeIndexValidationError):
                    validate_embedding_vectors([[0.0, value]], 1, 2)

    def test_rc_u12_embedding_identity_changes_index_version_at_same_dimension(self) -> None:
        base_embedding = self._embedding()
        variants = [
            replace(base_embedding, provider="openai-compatible"),
            replace(base_embedding, model="local-hash-v2"),
            replace(base_embedding, dimension=5),
            replace(base_embedding, algorithm_revision="local-hash-v2"),
            replace(base_embedding, deployment_revision="deployment-b"),
        ]
        versions = {self._fingerprints(embedding=base_embedding).index_version}
        versions.update(self._fingerprints(embedding=item).index_version for item in variants)
        self.assertEqual(6, len(versions))

    def test_rc_u13_embedding_batch_count_must_match_chunk_count(self) -> None:
        with self.assertRaisesRegex(KnowledgeIndexValidationError, "batch count mismatch"):
            validate_embedding_vectors([[0.0] * 4], expected_count=2, expected_dimension=4)

    def test_query_vector_is_validated_before_milvus_search(self) -> None:
        retriever = MilvusKnowledgeRetriever()
        retriever.connect = MagicMock(return_value=True)
        retriever._embeddings = MagicMock()
        retriever._embeddings.return_value.embed_query.return_value = [math.nan] * 4
        collection = MagicMock()

        with patch.object(retriever_module.utility, "has_collection", return_value=True), patch.object(
            retriever_module,
            "Collection",
            return_value=collection,
        ):
            hits = retriever.search("查询", 3, "kb-v1", "index-v1")

        self.assertEqual([], hits)
        self.assertEqual("KnowledgeIndexValidationError", retriever.last_error)
        collection.search.assert_not_called()

    def test_lexical_identity_changes_index_version(self) -> None:
        base = self._fingerprints()
        changed = build_index_fingerprints(
            "kb-v1",
            self._embedding(),
            ChunkConfig(300, 40, "character", "recursive-v1"),
            LexicalDescriptor("legacy-keyword-count-v2", "cjk-2-4-v2", "normalization=changed"),
        )
        self.assertNotEqual(base.index_version, changed.index_version)

    def test_openai_embedding_client_receives_explicit_dimension(self) -> None:
        client = MagicMock(return_value=object())
        environment = self._environment(
            AI_EMBEDDING_PROVIDER="openai",
            AI_EMBEDDING_MODEL="text-embedding-3-small",
            AI_EMBEDDING_DIMENSION="1024",
            AI_EMBEDDING_ALGORITHM_REVISION="openai-v1",
            AI_EMBEDDING_DEPLOYMENT_REVISION="compatible-a",
            AI_LOCAL_EMBEDDINGS="false",
            OPENAI_API_KEY="placeholder",
        )
        with patch.dict(os.environ, environment, clear=True), patch.object(
            retriever_module,
            "OpenAIEmbeddings",
            client,
        ):
            build_embeddings()

        self.assertEqual(1024, client.call_args.kwargs["dimensions"])

    def test_embedding_client_can_use_endpoint_and_key_separate_from_answer_model(self) -> None:
        client = MagicMock(return_value=object())
        environment = self._environment(
            AI_EMBEDDING_PROVIDER="openai",
            AI_EMBEDDING_MODEL="compatible-model",
            AI_EMBEDDING_DIMENSION="384",
            AI_EMBEDDING_ALGORITHM_REVISION="compatible-v1",
            AI_EMBEDDING_DEPLOYMENT_REVISION="stage-g",
            AI_LOCAL_EMBEDDINGS="false",
            AI_EMBEDDING_BASE_URL="http://127.0.0.1:18004/v1",
            AI_EMBEDDING_API_KEY="embedding-key",
            OPENAI_BASE_URL="https://answer-model.example/v1",
            OPENAI_API_KEY="answer-key",
        )
        with patch.dict(os.environ, environment, clear=True), patch.object(
            retriever_module,
            "OpenAIEmbeddings",
            client,
        ):
            build_embeddings()

        self.assertEqual("http://127.0.0.1:18004/v1", client.call_args.kwargs["base_url"])
        self.assertEqual("embedding-key", client.call_args.kwargs["api_key"])

    def test_openai_embedding_vectors_are_l2_normalized_for_backend_equivalence(self) -> None:
        delegate = MagicMock()
        delegate.embed_documents.return_value = [[3.0, 4.0]]
        delegate.embed_query.return_value = [0.0, 5.0]
        client = MagicMock(return_value=delegate)
        environment = self._environment(
            AI_EMBEDDING_PROVIDER="openai",
            AI_EMBEDDING_MODEL="compatible-model",
            AI_EMBEDDING_DIMENSION="2",
            AI_EMBEDDING_ALGORITHM_REVISION="compatible-v1",
            AI_EMBEDDING_DEPLOYMENT_REVISION="compatible-a",
            AI_LOCAL_EMBEDDINGS="false",
            OPENAI_API_KEY="placeholder",
        )
        with patch.dict(os.environ, environment, clear=True), patch.object(
            retriever_module,
            "OpenAIEmbeddings",
            client,
        ):
            embeddings = build_embeddings()
            descriptor = retriever_module.embedding_descriptor()

        self.assertEqual([[0.6, 0.8]], embeddings.embed_documents(["document"]))
        self.assertEqual([0.0, 1.0], embeddings.embed_query("query"))
        self.assertEqual("compatible-v1+l2-normalized-v1", descriptor.algorithm_revision)

    def test_acceptance_embedding_fault_only_blocks_candidate_documents(self) -> None:
        environment = self._environment(
            APP_PROFILE="acceptance",
            QILU_ACCEPTANCE_FAULTS_ENABLED="true",
            QILU_ACCEPTANCE_RAG_EMBED_DOCUMENTS_FAILURE="true",
        )
        with patch.dict(os.environ, environment, clear=True):
            embeddings = build_embeddings()

        with self.assertRaisesRegex(RuntimeError, "acceptance-rag-embed-documents-failure"):
            embeddings.embed_documents(["candidate"])
        self.assertEqual(4, len(embeddings.embed_query("旧 active 查询")))

    def test_invalid_chunk_configuration_fails_fast(self) -> None:
        for overrides in [
            {"RAG_CHUNK_SIZE": "0"},
            {"RAG_CHUNK_SIZE": "300", "RAG_CHUNK_OVERLAP": "300"},
            {"RAG_CHUNK_OVERLAP": "-1"},
            {"RAG_CHUNK_UNIT": "token"},
        ]:
            with self.subTest(overrides=overrides), patch.dict(
                os.environ,
                self._environment(**overrides),
                clear=True,
            ):
                with self.assertRaises(KnowledgeIndexConfigurationError):
                    CampusKnowledgeRetriever()

    def test_production_embedding_identity_must_be_explicit(self) -> None:
        environment = self._environment(CAMPUS_KB_MODE="production")
        environment.pop("AI_EMBEDDING_ALGORITHM_REVISION")
        with patch.dict(os.environ, environment, clear=True):
            with self.assertRaisesRegex(KnowledgeIndexConfigurationError, "configuration is incomplete"):
                CampusKnowledgeRetriever()

    def test_faiss_milvus_and_lexical_share_identical_active_chunk_ids(self) -> None:
        # 本用例专门验证 Milvus 启用后的三路投影一致性，不能依赖开发机默认开关。
        environment = self._environment(MILVUS_ENABLED="true")
        with patch.dict(os.environ, environment, clear=True), patch(
            "rag.retriever.vector_dependencies_enabled",
            return_value=True,
        ), patch.object(retriever_module.FAISS, "from_embeddings", return_value=MagicMock()) as faiss_build:
            retriever = CampusKnowledgeRetriever()
            embeddings = MagicMock()
            embeddings.embed_documents.side_effect = lambda texts: [[0.0] * 4 for _ in texts]
            retriever._embeddings = MagicMock(return_value=embeddings)
            retriever.milvus.prepare_candidate = MagicMock(side_effect=lambda chunks, vectors: MilvusCandidate(
                "qilu_ai_knowledge__shared",
                chunks[0].knowledge_version,
                chunks[0].index_version,
                len(chunks),
            ))
            retriever.milvus.activate_candidate = MagicMock(return_value=None)
            retriever.reload_documents(
                [self._document(31, "统一检索单元正文。" * 100)],
                "kb-shared-v1",
                "ai_knowledge",
            )

        active_ids = [chunk.chunk_id for chunk in retriever.chunks]
        faiss_ids = faiss_build.call_args.kwargs["ids"]
        milvus_ids = [
            chunk.chunk_id
            for chunk in retriever.milvus.prepare_candidate.call_args.args[0]
        ]
        lexical_ids = [chunk.chunk_id for chunk in retriever.bm25_index.chunks]
        self.assertEqual(active_ids, faiss_ids)
        self.assertEqual(active_ids, milvus_ids)
        self.assertEqual(active_ids, lexical_ids)
        embeddings.embed_documents.assert_called_once()

    @staticmethod
    def _document(
        knowledge_id,
        content: str,
        title: str = "合成知识标题",
        category: str = "合成分类",
    ) -> KnowledgeDocument:
        return KnowledgeDocument(
            id=knowledge_id,
            title=title,
            content=content,
            keywords=[],
            category=category,
            source="ai_knowledge",
        )

    def _chunks(
        self,
        documents,
        knowledge_version: str = "kb-v1",
        config: ChunkConfig | None = None,
    ):
        chunk_config = config or ChunkConfig(300, 40, "character", "recursive-v1")
        fingerprints = self._fingerprints(knowledge_version=knowledge_version, chunk=chunk_config)
        return build_knowledge_chunks(
            documents,
            knowledge_version,
            fingerprints.index_version,
            chunk_config,
            require_formal_ids=True,
        )

    def _fingerprints(
        self,
        knowledge_version: str = "kb-v1",
        embedding: EmbeddingDescriptor | None = None,
        chunk: ChunkConfig | None = None,
    ):
        return build_index_fingerprints(
            knowledge_version,
            embedding or self._embedding(),
            chunk or ChunkConfig(300, 40, "character", "recursive-v1"),
            LexicalDescriptor("legacy-keyword-count-v1", "cjk-2-4-v1", "normalization=legacy"),
        )

    @staticmethod
    def _embedding() -> EmbeddingDescriptor:
        return EmbeddingDescriptor("local", "local-hash-v1", 4, "local-hash-v1", "deployment-a")

    def _loaded_retriever(self) -> CampusKnowledgeRetriever:
        with patch.dict(os.environ, self._environment(), clear=True), patch(
            "rag.retriever.vector_dependencies_enabled",
            return_value=False,
        ):
            retriever = CampusKnowledgeRetriever()
            retriever.milvus.reload = MagicMock(return_value=True)
            retriever.reload_documents([self._document(1, "旧版本正文")], "kb-v1", "ai_knowledge")
        return retriever

    @staticmethod
    def _group_signature(chunks) -> dict:
        grouped = {}
        for chunk in chunks:
            grouped.setdefault(chunk.knowledge_id, []).append(
                (chunk.chunk_index, chunk.chunk_content, chunk.chunk_id)
            )
        return grouped

    @staticmethod
    def _suffix_overlap(left: str, right: str) -> int:
        maximum = min(len(left), len(right))
        for size in range(maximum, 0, -1):
            if left[-size:] == right[:size]:
                return size
        return 0

    @staticmethod
    def _environment(**overrides: str) -> dict[str, str]:
        environment = {
            "AI_EMBEDDING_PROVIDER": "local",
            "AI_EMBEDDING_MODEL": "local-hash-v1",
            "AI_EMBEDDING_DIMENSION": "4",
            "AI_EMBEDDING_ALGORITHM_REVISION": "local-hash-v1",
            "AI_EMBEDDING_DEPLOYMENT_REVISION": "stage-b-tests",
            "AI_LOCAL_EMBEDDINGS": "true",
            "MILVUS_ENABLED": "false",
            "OPENAI_API_KEY": "",
            "RAG_CHUNK_SIZE": "300",
            "RAG_CHUNK_OVERLAP": "40",
            "RAG_CHUNK_UNIT": "character",
            "RAG_CHUNK_ALGORITHM": "recursive-v1",
        }
        environment.update(overrides)
        return environment


if __name__ == "__main__":
    unittest.main()
