from __future__ import annotations

import importlib
import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from agent.campus_support_agent import (
    CampusSupportAgent,
    build_structured_response,
    generate_response,
    knowledge_hits_to_sources,
    knowledge_policy,
    load_kb_texts,
)
from app.schemas import CampusAssistantRequest, KnowledgeReloadItem
from rag.retriever import (
    CampusKnowledgeRetriever,
    KnowledgeHit,
    MilvusKnowledgeRetriever,
    keyword_retrieve,
)


class KnowledgeBoundaryTest(unittest.TestCase):
    def test_active_knowledge_version_is_preserved_in_response_source(self) -> None:
        sources = knowledge_hits_to_sources([
            KnowledgeHit(
                content="Formal active knowledge",
                metadata={
                    "knowledgeId": 810001,
                    "title": "Formal knowledge",
                    "source": "ai_knowledge",
                    "knowledgeVersion": "formal-active-v1",
                },
                score=0.9,
                retriever="milvus",
                normalized_score=0.9,
            )
        ])

        self.assertEqual("formal-active-v1", sources[0].knowledgeVersion)

    def test_default_kb_excludes_engineering_docs_from_retrieval_inputs(self) -> None:
        engineering_sentinel = "engineering-boundary-sentinel-7f31"
        faq_text = "# Campus FAQ\nCampus card replacement is handled at the card center."

        temporary_root = Path(__file__).resolve().parents[1] / ".tmp"
        temporary_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary_root) as temporary_directory:
            project_root = Path(temporary_directory)
            docs_directory = project_root / "docs"
            knowledge_directory = project_root / "knowledge"
            bootstrap_directory = project_root / "bootstrap"
            vector_directory = project_root / "faiss"
            docs_directory.mkdir()
            knowledge_directory.mkdir()
            bootstrap_directory.mkdir()

            (docs_directory / "campus_faq.md").write_text(faq_text, encoding="utf-8")
            (docs_directory / "engineering_notes.md").write_text(
                "# Internal recovery design\n" + engineering_sentinel,
                encoding="utf-8",
            )
            (knowledge_directory / "campus_faq.md").write_text(faq_text, encoding="utf-8")

            isolated_environment = {
                "AI_CHECKPOINT_ENABLED": "false",
                "AI_EMBEDDING_PROVIDER": "disabled",
                "AI_LIGHTWEIGHT_RUNTIME": "true",
                "AI_LOCAL_EMBEDDINGS": "false",
                "CAMPUS_ALLOW_SAMPLE_KB": "false",
                "CAMPUS_KB_DIR": str(bootstrap_directory),
                "CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC": "true",
                "CAMPUS_VECTOR_INDEX_DIR": str(vector_directory),
                "MILVUS_ENABLED": "false",
                "OPENAI_API_KEY": "",
            }
            with patch.dict(os.environ, isolated_environment, clear=False):
                sys.modules.pop("app.main", None)
                app_main = importlib.import_module("app.main")

            configured_directory = project_root / app_main.default_kb_dir.name
            texts = load_kb_texts(str(configured_directory), allow_sample=True)
            self.assertTrue(any("Campus card replacement" in text for text in texts))
            with patch("rag.retriever.vector_dependencies_enabled", return_value=False):
                retriever = CampusKnowledgeRetriever(
                    texts=texts,
                    knowledge_source="sample-dev",
                )

            active_document_hits = [
                document.title
                for document in retriever.documents
                if engineering_sentinel in document.content
            ]
            keyword_hits = [
                hit.content
                for hit in keyword_retrieve(engineering_sentinel, retriever.documents)
                if engineering_sentinel in hit.content
            ]

            self.assertEqual(
                {"activeDocuments": [], "keywordHits": []},
                {
                    "activeDocuments": active_document_hits,
                    "keywordHits": keyword_hits,
                },
                "engineering docs entered the active documents shared by FAISS and keyword retrieval",
            )

    def test_loader_requires_explicit_sample_permission(self) -> None:
        with self._temporary_directory() as temporary_directory:
            knowledge_directory = Path(temporary_directory) / "knowledge"
            knowledge_directory.mkdir()
            (knowledge_directory / "campus_faq.md").write_text("campus faq", encoding="utf-8")

            self.assertEqual([], load_kb_texts(str(knowledge_directory), allow_sample=False))

    def test_loader_uses_only_sorted_direct_visible_utf8_files(self) -> None:
        with self._temporary_directory() as temporary_directory:
            knowledge_directory = Path(temporary_directory) / "knowledge"
            nested_directory = knowledge_directory / "nested"
            nested_directory.mkdir(parents=True)
            (knowledge_directory / "z-last.txt").write_text("last", encoding="utf-8")
            (knowledge_directory / "a-first.md").write_text("first", encoding="utf-8")
            (knowledge_directory / ".hidden.md").write_text("hidden", encoding="utf-8")
            (knowledge_directory / "ignored.json").write_text("ignored", encoding="utf-8")
            (nested_directory / "nested.md").write_text("nested", encoding="utf-8")

            self.assertEqual(
                ["first", "last"],
                load_kb_texts(str(knowledge_directory), allow_sample=True),
            )

    def test_loader_ignores_symbolic_link_entries(self) -> None:
        with self._temporary_directory() as temporary_directory:
            knowledge_directory = Path(temporary_directory) / "knowledge"
            knowledge_directory.mkdir()
            linked_path = MagicMock()
            linked_path.name = "linked.md"
            linked_path.is_symlink.return_value = True

            with patch.object(Path, "iterdir", return_value=iter([linked_path])):
                texts = load_kb_texts(str(knowledge_directory), allow_sample=True)

            self.assertEqual([], texts)
            linked_path.is_file.assert_not_called()

    def test_loader_missing_directory_returns_empty(self) -> None:
        with self._temporary_directory() as temporary_directory:
            missing_directory = Path(temporary_directory) / "missing"
            self.assertEqual([], load_kb_texts(str(missing_directory), allow_sample=True))

    def test_loader_invalid_utf8_is_fail_closed(self) -> None:
        with self._temporary_directory() as temporary_directory:
            knowledge_directory = Path(temporary_directory) / "knowledge"
            knowledge_directory.mkdir()
            (knowledge_directory / "a-valid.md").write_text("valid campus knowledge", encoding="utf-8")
            (knowledge_directory / "b-invalid.md").write_bytes(b"\xff\xfe\xfa")

            with self.assertLogs("agent.campus_support_agent", level="WARNING") as captured:
                texts = load_kb_texts(str(knowledge_directory), allow_sample=True)

            self.assertEqual([], texts)
            self.assertTrue(any("UnicodeDecodeError" in message for message in captured.output))
            self.assertFalse(any("b-invalid.md" in message for message in captured.output))

    def test_production_policy_rejects_conflicting_sample_configuration(self) -> None:
        environment = self._agent_environment(
            CAMPUS_KB_MODE="production",
            CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC="false",
            CAMPUS_ALLOW_SAMPLE_KB="true",
        )
        with patch.dict(os.environ, environment, clear=False):
            policy = knowledge_policy()

        self.assertEqual("production", policy.mode)
        self.assertTrue(policy.require_ai_knowledge_sync)
        self.assertFalse(policy.allow_sample_kb)

    def test_demo_policy_allows_samples_only_without_required_sync(self) -> None:
        allowed = self._agent_environment(
            CAMPUS_KB_MODE="demo",
            CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC="false",
            CAMPUS_ALLOW_SAMPLE_KB="true",
        )
        required = dict(allowed, CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC="true")

        with patch.dict(os.environ, allowed, clear=False):
            allowed_policy = knowledge_policy()
        with patch.dict(os.environ, required, clear=False):
            required_policy = knowledge_policy()

        self.assertFalse(allowed_policy.require_ai_knowledge_sync)
        self.assertTrue(allowed_policy.allow_sample_kb)
        self.assertTrue(required_policy.require_ai_knowledge_sync)
        self.assertFalse(required_policy.allow_sample_kb)

    def test_local_files_never_report_ai_knowledge(self) -> None:
        with self._temporary_directory() as temporary_directory:
            knowledge_directory = Path(temporary_directory) / "knowledge"
            knowledge_directory.mkdir()
            (knowledge_directory / "campus_faq.md").write_text("campus card replacement", encoding="utf-8")
            cases = [
                ("production", "false", "true", "uninitialized", 0),
                ("demo", "false", "false", "uninitialized", 0),
                ("demo", "false", "true", "sample-dev", 1),
            ]
            for mode, require_sync, allow_sample, expected_source, expected_count in cases:
                with self.subTest(mode=mode, require_sync=require_sync, allow_sample=allow_sample):
                    environment = self._agent_environment(
                        CAMPUS_KB_MODE=mode,
                        CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC=require_sync,
                        CAMPUS_ALLOW_SAMPLE_KB=allow_sample,
                    )
                    with patch.dict(os.environ, environment, clear=False), patch(
                        "rag.retriever.vector_dependencies_enabled",
                        return_value=False,
                    ):
                        agent = CampusSupportAgent(kb_dir=str(knowledge_directory))
                        status = agent.retriever_status()
                    self.assertEqual(expected_source, status["knowledgeSource"])
                    self.assertEqual(expected_count, status["knowledgeDocumentCount"])
                    self.assertNotEqual("ai_knowledge", status["knowledgeSource"])

    def test_formal_reload_sets_ai_knowledge_source(self) -> None:
        with self._temporary_directory() as temporary_directory:
            environment = self._agent_environment(
                CAMPUS_KB_MODE="production",
                CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC="true",
                CAMPUS_ALLOW_SAMPLE_KB="false",
            )
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                agent = CampusSupportAgent(kb_dir=str(Path(temporary_directory) / "knowledge"))
                count = agent.reload_knowledge(
                    [KnowledgeReloadItem(id=1, title="Campus card", content="Bring student ID.")],
                    knowledge_version="formal-v1",
                )
                status = agent.retriever_status()
            self.assertEqual(1, count)
            self.assertTrue(status["knowledgeInitialized"])
            self.assertEqual("ai_knowledge", status["knowledgeSource"])
            self.assertEqual("formal-v1", status["knowledgeVersion"])

    def test_manifest_v3_restores_formal_knowledge_without_resync(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(
                CAMPUS_KB_MODE="production",
                CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC="true",
                CAMPUS_ALLOW_SAMPLE_KB="false",
                CAMPUS_VECTOR_INDEX_DIR=str(cache_directory),
            )
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                first_agent = CampusSupportAgent(kb_dir=str(Path(temporary_directory) / "missing"))
                first_agent.reload_knowledge(
                    [KnowledgeReloadItem(
                        id=101,
                        title="Restart archive desk",
                        content="The restart archive desk accepts the formal-recovery-token.",
                        keywords=["zeta", "formal-recovery-token", "zeta"],
                        category="campus-service",
                        source="ai-knowledge-acceptance",
                    )],
                    knowledge_version="formal-restart-v1",
                )

                documents_bytes = (cache_directory / "documents.json").read_bytes()
                records = json.loads(documents_bytes.decode("utf-8"))
                manifest = self._read_manifest(cache_directory)
                self.assertEqual(
                    ["id", "title", "content", "keywords", "category", "source"],
                    list(records[0]),
                )
                self.assertEqual(["formal-recovery-token", "zeta"], records[0]["keywords"])
                self.assertEqual(3, manifest["manifestSchemaVersion"])
                self.assertEqual("ai_knowledge", manifest["knowledgeSource"])
                self.assertEqual(status_index := first_agent.retriever_status()["indexVersion"], manifest["indexVersion"])
                self.assertEqual(1, manifest["chunkCount"])
                self.assertEqual(
                    "sha256:" + hashlib.sha256(documents_bytes).hexdigest(),
                    manifest["contentHash"],
                )

                restarted_agent = CampusSupportAgent(kb_dir=str(Path(temporary_directory) / "missing"))
                status = restarted_agent.retriever_status()
                response = restarted_agent.chat(CampusAssistantRequest(question="formal-recovery-token"))

            self.assertTrue(status["knowledgeInitialized"])
            self.assertEqual("ai_knowledge", status["knowledgeSource"])
            self.assertEqual("formal-restart-v1", status["knowledgeVersion"])
            self.assertEqual(status_index, status["indexVersion"])
            self.assertEqual("valid", status["cacheValidationStatus"])
            self.assertEqual("v3_restored", status["cacheValidationReason"])
            self.assertIsNone(response.fallbackReason)
            self.assertTrue(any(source.type == "knowledge" for source in response.sources))

    def test_manifest_without_knowledge_source_is_rejected(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(CAMPUS_VECTOR_INDEX_DIR=str(cache_directory))
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                self._write_formal_keyword_cache(cache_directory)
                manifest = self._read_manifest(cache_directory)
                manifest.pop("knowledgeSource")
                self._write_manifest(cache_directory, manifest)
                restored = CampusKnowledgeRetriever(persist_dir=str(cache_directory))

            self.assertFalse(restored.is_initialized())
            self.assertEqual("manifest_fields_missing", restored.status()["cacheValidationReason"])

    def test_sample_dev_manifest_is_rejected_in_production(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(
                CAMPUS_KB_MODE="production",
                CAMPUS_VECTOR_INDEX_DIR=str(cache_directory),
            )
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                self._write_formal_keyword_cache(cache_directory)
                manifest = self._read_manifest(cache_directory)
                manifest["knowledgeSource"] = "sample-dev"
                self._write_manifest(cache_directory, manifest)
                restored = CampusKnowledgeRetriever(persist_dir=str(cache_directory))

            self.assertFalse(restored.is_initialized())
            self.assertEqual("knowledge_source_invalid", restored.status()["cacheValidationReason"])

    def test_manifest_with_missing_documents_is_rejected(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(CAMPUS_VECTOR_INDEX_DIR=str(cache_directory))
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                self._write_formal_keyword_cache(cache_directory)
                (cache_directory / "documents.json").unlink()
                restored = CampusKnowledgeRetriever(persist_dir=str(cache_directory))

            self.assertFalse(restored.is_initialized())
            self.assertEqual("documents_missing", restored.status()["cacheValidationReason"])

    def test_manifest_document_count_mismatch_is_rejected(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(CAMPUS_VECTOR_INDEX_DIR=str(cache_directory))
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                self._write_formal_keyword_cache(cache_directory)
                manifest = self._read_manifest(cache_directory)
                manifest["documentCount"] = 2
                self._write_manifest(cache_directory, manifest)
                restored = CampusKnowledgeRetriever(persist_dir=str(cache_directory))

            self.assertFalse(restored.is_initialized())
            self.assertEqual("document_count_mismatch", restored.status()["cacheValidationReason"])

    def test_manifest_content_hash_mismatch_is_rejected(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(CAMPUS_VECTOR_INDEX_DIR=str(cache_directory))
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                self._write_formal_keyword_cache(cache_directory)
                manifest = self._read_manifest(cache_directory)
                manifest["contentHash"] = "sha256:" + ("0" * 64)
                self._write_manifest(cache_directory, manifest)
                restored = CampusKnowledgeRetriever(persist_dir=str(cache_directory))

            self.assertFalse(restored.is_initialized())
            self.assertEqual("content_hash_mismatch", restored.status()["cacheValidationReason"])

    def test_embedding_configuration_changes_reject_cache(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            base_environment = self._agent_environment(CAMPUS_VECTOR_INDEX_DIR=str(cache_directory))
            with patch.dict(os.environ, base_environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                self._write_formal_keyword_cache(cache_directory)

            cases = [
                ({"AI_EMBEDDING_PROVIDER": "changed-provider"}, "embedding_provider_mismatch"),
                ({"AI_EMBEDDING_MODEL": "changed-model"}, "embedding_model_mismatch"),
                ({"AI_EMBEDDING_DIMENSION": "1024"}, "embedding_dimension_mismatch"),
            ]
            for overrides, expected_reason in cases:
                with self.subTest(expected_reason=expected_reason):
                    environment = dict(base_environment, **overrides)
                    with patch.dict(os.environ, environment, clear=False), patch(
                        "rag.retriever.vector_dependencies_enabled",
                        return_value=False,
                    ):
                        restored = CampusKnowledgeRetriever(persist_dir=str(cache_directory))
                    self.assertFalse(restored.is_initialized())
                    self.assertEqual(expected_reason, restored.status()["cacheValidationReason"])

    def test_manifest_with_only_one_faiss_file_is_rejected(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(CAMPUS_VECTOR_INDEX_DIR=str(cache_directory))
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                self._write_formal_keyword_cache(cache_directory)
                manifest = self._read_manifest(cache_directory)
                manifest["vectorIndexPresent"] = True
                manifest["faissIndexPresent"] = True
                manifest["faissIndexSha256"] = "sha256:" + ("0" * 64)
                manifest["faissMetadataSha256"] = "sha256:" + ("0" * 64)
                manifest["backendStates"]["faiss"] = "READY"
                self._write_manifest(cache_directory, manifest)
                (cache_directory / "index.faiss").write_bytes(b"incomplete-index")
                restored = CampusKnowledgeRetriever(persist_dir=str(cache_directory))

            self.assertFalse(restored.is_initialized())
            self.assertEqual("faiss_index_incomplete", restored.status()["cacheValidationReason"])

    def test_manifest_without_vector_index_restores_bm25_documents(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(CAMPUS_VECTOR_INDEX_DIR=str(cache_directory))
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                self._write_formal_keyword_cache(cache_directory)
                restored = CampusKnowledgeRetriever(persist_dir=str(cache_directory))
                hits = restored.retrieve_documents("formal-cache-token")
                status = restored.status()

            self.assertTrue(restored.is_initialized())
            self.assertTrue(hits)
            self.assertTrue(all(hit.retriever == "bm25" for hit in hits))
            self.assertFalse(status["vectorIndexEnabled"])
            self.assertFalse(status["vectorIndexPersistent"])

    def test_faiss_manifest_v3_restores_fingerprinted_vector_index(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(
                AI_EMBEDDING_PROVIDER="local",
                AI_EMBEDDING_MODEL="local-hash-v1",
                AI_EMBEDDING_DIMENSION="64",
                AI_LIGHTWEIGHT_RUNTIME="false",
                CAMPUS_VECTOR_INDEX_DIR=str(cache_directory),
            )
            with patch.dict(os.environ, environment, clear=False):
                writer = CampusKnowledgeRetriever(persist_dir=str(cache_directory))
                writer.reload(
                    ["Formal vector cache contains vector-recovery-token."],
                    knowledge_version="formal-vector-v1",
                    knowledge_source="ai_knowledge",
                )
                manifest = self._read_manifest(cache_directory)
                restored = CampusKnowledgeRetriever(persist_dir=str(cache_directory))
                hits = restored.retrieve_documents("vector-recovery-token")
                status = restored.status()

            self.assertTrue(manifest["vectorIndexPresent"])
            self.assertTrue((cache_directory / "index.faiss").is_file())
            self.assertTrue((cache_directory / "index.pkl").is_file())
            self.assertEqual("formal-vector-v1", restored.knowledge_version)
            self.assertTrue(hits)
            self.assertIn("faiss", hits[0].retrievers)
            self.assertTrue(status["vectorIndexEnabled"])
            self.assertTrue(status["vectorIndexPersistent"])
            self.assertEqual("v3_restored", status["cacheValidationReason"])

    def test_cache_without_committed_manifest_is_not_visible(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(CAMPUS_VECTOR_INDEX_DIR=str(cache_directory))
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                self._write_formal_keyword_cache(cache_directory)
                manifest_bytes = (cache_directory / "manifest.json").read_bytes()
                documents_bytes = (cache_directory / "documents.json").read_bytes()
                (cache_directory / "manifest.json").unlink()
                staging_directory = cache_directory / ".cache-write-interrupted"
                staging_directory.mkdir()
                (staging_directory / "documents.json").write_bytes(documents_bytes)
                (staging_directory / "manifest.json").write_bytes(manifest_bytes)
                restored = CampusKnowledgeRetriever(persist_dir=str(cache_directory))

            self.assertFalse(restored.is_initialized())
            self.assertEqual([], restored.retrieve_documents("formal-cache-token"))
            self.assertEqual("manifest_missing", restored.status()["cacheValidationReason"])

    def test_milvus_current_version_hit_preserves_formal_source(self) -> None:
        environment = self._agent_environment(
            AI_EMBEDDING_PROVIDER="local",
            AI_EMBEDDING_DIMENSION="64",
            MILVUS_ENABLED="true",
            MILVUS_COLLECTION="qilu_ai_knowledge_unit_current",
        )
        collection = MagicMock()
        collection.num_entities = 1
        collection.search.return_value = [[self._milvus_hit(
            content="Current formal knowledge.",
            knowledge_version="formal-v2",
            source="ai-knowledge-sync",
        )]]
        utility_mock = MagicMock()
        utility_mock.has_collection.return_value = True

        with patch.dict(os.environ, environment, clear=False), patch(
            "rag.retriever.utility",
            utility_mock,
        ), patch("rag.retriever.Collection", return_value=collection):
            milvus = MilvusKnowledgeRetriever()
            with patch.object(milvus, "connect", return_value=True):
                hits = milvus.search("formal", 3, "formal-v2")

        self.assertEqual(1, len(hits))
        self.assertEqual("ai-knowledge-sync", hits[0].metadata["source"])
        self.assertEqual("formal-v2", hits[0].metadata["knowledgeVersion"])
        self.assertIn("knowledgeVersion", collection.search.call_args.kwargs["output_fields"])

    def test_milvus_status_is_a_non_blocking_snapshot(self) -> None:
        environment = self._agent_environment(
            AI_EMBEDDING_PROVIDER="local",
            MILVUS_ENABLED="true",
        )
        connections_mock = MagicMock()
        with patch.dict(os.environ, environment, clear=False), patch(
            "rag.retriever.connections",
            connections_mock,
        ), patch("rag.retriever.Collection", MagicMock()):
            status = MilvusKnowledgeRetriever().status()

        self.assertFalse(status["milvusConnected"])
        connections_mock.connect.assert_not_called()

    def test_milvus_connect_uses_configured_timeout(self) -> None:
        environment = self._agent_environment(
            AI_EMBEDDING_PROVIDER="local",
            MILVUS_ENABLED="true",
            MILVUS_CONNECT_TIMEOUT_SECONDS="0.75",
        )
        connections_mock = MagicMock()
        connections_mock.connect.side_effect = RuntimeError("unavailable")
        with patch.dict(os.environ, environment, clear=False), patch(
            "rag.retriever.connections",
            connections_mock,
        ), patch("rag.retriever.Collection", MagicMock()):
            connected = MilvusKnowledgeRetriever().connect()

        self.assertFalse(connected)
        self.assertEqual(0.75, connections_mock.connect.call_args.kwargs["timeout"])

    def test_milvus_missing_and_old_versions_are_discarded(self) -> None:
        environment = self._agent_environment(
            AI_EMBEDDING_PROVIDER="local",
            AI_EMBEDDING_DIMENSION="64",
            MILVUS_ENABLED="true",
            MILVUS_COLLECTION="qilu_ai_knowledge_unit_versions",
        )
        collection = MagicMock()
        collection.num_entities = 3
        collection.search.return_value = [[
            self._milvus_hit("Old formal knowledge.", "formal-v1", "old-source"),
            self._milvus_hit("Missing version knowledge.", None, "missing-source"),
            self._milvus_hit("Current formal knowledge.", "formal-v2", "current-source"),
        ]]
        utility_mock = MagicMock()
        utility_mock.has_collection.return_value = True

        with patch.dict(os.environ, environment, clear=False), patch(
            "rag.retriever.utility",
            utility_mock,
        ), patch("rag.retriever.Collection", return_value=collection):
            milvus = MilvusKnowledgeRetriever()
            with patch.object(milvus, "connect", return_value=True):
                hits = milvus.search("formal", 3, "formal-v2")
                status = milvus.status()

        self.assertEqual(["Current formal knowledge."], [hit.content for hit in hits])
        self.assertEqual(2, status["milvusDiscardedVersionHits"])
        self.assertEqual("knowledge_version_missing_or_mismatch", status["milvusLastVersionFilterReason"])

    def test_milvus_failure_falls_back_to_faiss(self) -> None:
        environment = self._agent_environment(MILVUS_ENABLED="false")
        with patch.dict(os.environ, environment, clear=False), patch(
            "rag.retriever.vector_dependencies_enabled",
            return_value=False,
        ):
            retriever = CampusKnowledgeRetriever()
            retriever.reload(
                ["Formal FAISS fallback contains faiss-fallback-token."],
                knowledge_version="formal-faiss-v1",
            )
        retriever.milvus.available = MagicMock(return_value=True)
        retriever.milvus.search = MagicMock(return_value=[])
        # 构造时关闭了 Milvus；注入“已配置但搜索失败”实例后同步冻结开关，才能覆盖 FAISS 降级链路。
        retriever.milvus_configured = True
        retriever.milvus.connected = False
        retriever.milvus.last_error = "MilvusUnavailable"
        vector_document = MagicMock()
        vector_document.page_content = "Formal FAISS fallback contains faiss-fallback-token."
        vector_document.metadata = retriever.chunks[0].metadata()
        retriever.vectorstore = MagicMock()
        retriever.vectorstore.similarity_search_with_score.return_value = [(vector_document, 0.1)]

        hits = retriever.retrieve_documents("faiss-fallback-token")

        self.assertEqual(["faiss"], [hit.retriever for hit in hits])
        self.assertEqual(("faiss", "bm25"), hits[0].retrievers)
        self.assertEqual("formal-faiss-v1", hits[0].metadata["knowledgeVersion"])
        retriever.milvus.search.assert_called_once_with(
            "faiss-fallback-token",
            12,
            "formal-faiss-v1",
            retriever.index_version,
        )

    def test_bm25_uses_only_active_formal_documents(self) -> None:
        engineering_sentinel = "QILU_ENGINEERING_DOC_SENTINEL_20260719"
        environment = self._agent_environment(MILVUS_ENABLED="false")
        with patch.dict(os.environ, environment, clear=False), patch(
            "rag.retriever.vector_dependencies_enabled",
            return_value=False,
        ):
            retriever = CampusKnowledgeRetriever(
                texts=["Internal engineering material " + engineering_sentinel],
                knowledge_source="sample-dev",
            )
            retriever.reload(
                ["Formal active knowledge contains active-formal-token."],
                knowledge_version="formal-active-v1",
            )
        retriever.milvus.search = MagicMock(return_value=[])
        retriever.vectorstore = MagicMock()
        retriever.vectorstore.similarity_search_with_score.side_effect = RuntimeError("FAISS unavailable")

        formal_hits = retriever.retrieve_documents("active-formal-token")
        engineering_hits = retriever.retrieve_documents(engineering_sentinel)

        self.assertEqual(["bm25"], [hit.retriever for hit in formal_hits])
        self.assertEqual("formal-active-v1", formal_hits[0].metadata["knowledgeVersion"])
        self.assertEqual([], engineering_hits)
        self.assertFalse(any(engineering_sentinel in document.content for document in retriever.documents))

    def test_initialized_knowledge_with_no_retriever_hit_returns_no_source(self) -> None:
        with self._temporary_directory() as temporary_directory:
            environment = self._agent_environment(
                AGENT_ORCHESTRATOR="legacy",
                CAMPUS_KB_MODE="production",
                CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC="true",
                CAMPUS_ALLOW_SAMPLE_KB="false",
            )
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                agent = CampusSupportAgent(kb_dir=str(Path(temporary_directory) / "missing"))
                agent.reload_knowledge(
                    [KnowledgeReloadItem(id=1, title="Campus card", content="Bring student ID.")],
                    knowledge_version="formal-no-source-v1",
                )
                response = agent.chat(CampusAssistantRequest(question="unmatched-query-7e21"))

        self.assertEqual("NO_SOURCE", response.fallbackReason)
        self.assertEqual([], response.sources)

    def test_empty_reload_clears_all_local_retrievers_and_old_hits(self) -> None:
        with self._temporary_directory() as temporary_directory:
            cache_directory = Path(temporary_directory) / "faiss"
            environment = self._agent_environment(CAMPUS_VECTOR_INDEX_DIR=str(cache_directory))
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                retriever = CampusKnowledgeRetriever(persist_dir=str(cache_directory))
                retriever.reload(
                    ["Old formal knowledge contains empty-reload-old-token."],
                    knowledge_version="formal-before-empty-v1",
                )
                retriever.vectorstore = MagicMock()
                retriever.milvus.clear = MagicMock(return_value=True)
                retriever.milvus.search = MagicMock(return_value=[KnowledgeHit(
                    "stale Milvus content",
                    {"knowledgeVersion": "formal-before-empty-v1"},
                    0.9,
                    "milvus",
                )])

                retriever.reload([], knowledge_version="formal-empty-v2")
                hits = retriever.retrieve_documents("empty-reload-old-token")
                status = retriever.status()

            self.assertEqual([], hits)
            self.assertFalse(status["knowledgeInitialized"])
            # 空同步是有版本的显式 active 状态，既阻断旧知识，又可与其他实例比较同步进度。
            self.assertEqual("ai_knowledge", status["knowledgeSource"])
            self.assertEqual("formal-empty-v2", status["knowledgeVersion"])
            self.assertEqual(0, status["knowledgeDocumentCount"])
            self.assertIsNone(retriever.vectorstore)
            retriever.milvus.clear.assert_called_once_with()
            retriever.milvus.search.assert_not_called()
            self.assertTrue((cache_directory / "documents.json").is_file())
            self.assertTrue((cache_directory / "manifest.json").is_file())
            self.assertFalse((cache_directory / "index.faiss").exists())
            self.assertFalse((cache_directory / "index.pkl").exists())
            manifest = self._read_manifest(cache_directory)
            self.assertEqual(3, manifest["manifestSchemaVersion"])
            self.assertEqual(0, manifest["documentCount"])
            self.assertEqual(0, manifest["chunkCount"])
            self.assertEqual("formal-empty-v2", manifest["knowledgeVersion"])

    def test_milvus_clear_drops_existing_collection(self) -> None:
        environment = self._agent_environment(
            AI_EMBEDDING_PROVIDER="local",
            AI_EMBEDDING_DIMENSION="64",
            MILVUS_ENABLED="true",
            MILVUS_COLLECTION="qilu_ai_knowledge_unit_clear",
        )
        utility_mock = MagicMock()
        utility_mock.has_collection.return_value = True

        with patch.dict(os.environ, environment, clear=False), patch(
            "rag.retriever.utility",
            utility_mock,
        ):
            milvus = MilvusKnowledgeRetriever()
            milvus.document_count = 4
            with patch.object(milvus, "connect", return_value=True):
                cleared = milvus.clear()

        self.assertTrue(cleared)
        utility_mock.drop_collection.assert_called_once_with(
            "qilu_ai_knowledge_unit_clear",
            using="qilu_ai_agent",
        )
        self.assertEqual(0, milvus.document_count)

    def test_sample_dev_documents_do_not_write_or_query_milvus(self) -> None:
        with patch("rag.retriever.vector_dependencies_enabled", return_value=False):
            retriever = CampusKnowledgeRetriever()
            retriever.milvus.reload = MagicMock(return_value=True)
            retriever.milvus.search = MagicMock(return_value=[KnowledgeHit(
                "formal content must stay isolated",
                {"knowledgeVersion": "sample-local-v1"},
                0.9,
                "milvus",
            )])

            retriever.reload(
                ["Local sample contains sample-local-token."],
                knowledge_version="sample-local-v1",
                knowledge_source="sample-dev",
            )
            hits = retriever.retrieve_documents("sample-local-token")

        retriever.milvus.reload.assert_not_called()
        retriever.milvus.search.assert_not_called()
        self.assertEqual(["bm25"], [hit.retriever for hit in hits])

    def test_invalid_retriever_source_is_not_active(self) -> None:
        retriever = CampusKnowledgeRetriever(
            texts=["engineering content"],
            knowledge_source="unknown-local-source",
        )

        self.assertFalse(retriever.is_initialized())
        self.assertEqual([], retriever.documents)
        self.assertEqual([], retriever.retrieve_documents("engineering"))

    def test_general_question_without_knowledge_returns_not_synced(self) -> None:
        with self._temporary_directory() as temporary_directory:
            environment = self._agent_environment(
                AGENT_ORCHESTRATOR="legacy",
                CAMPUS_KB_MODE="production",
                CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC="true",
                CAMPUS_ALLOW_SAMPLE_KB="false",
            )
            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ):
                agent = CampusSupportAgent(kb_dir=str(Path(temporary_directory) / "knowledge"))
                response = agent.chat(CampusAssistantRequest(question="How do I replace a campus card?"))

        self.assertEqual("KNOWLEDGE_NOT_SYNCED", response.fallbackReason)
        self.assertEqual([], response.sources)

    def test_business_tool_result_wins_when_knowledge_is_uninitialized(self) -> None:
        state = self._response_state({
            "toolName": "query_my_tickets",
            "success": True,
            "data": [{"id": 7, "title": "Door repair", "statusText": "processing"}],
            "count": 1,
            "message": None,
        })

        state.update(generate_response(state))
        response = build_structured_response(state)

        self.assertIsNone(response.fallbackReason)
        self.assertTrue(response.sources)
        self.assertTrue(response.businessCards)

    def test_permission_denied_wins_when_knowledge_is_uninitialized(self) -> None:
        state = self._response_state({
            "toolName": "query_ticket_detail",
            "success": False,
            "data": None,
            "count": 0,
            "message": "PERMISSION_DENIED",
            "errorType": "PERMISSION_DENIED",
        })

        state.update(generate_response(state))
        response = build_structured_response(state)

        self.assertEqual("PERMISSION_DENIED", response.fallbackReason)
        self.assertEqual([], response.sources)
        self.assertEqual([], response.businessCards)

    def test_business_tool_chat_flow_does_not_require_knowledge(self) -> None:
        with self._temporary_directory() as temporary_directory:
            environment = self._agent_environment(
                AGENT_ORCHESTRATOR="legacy",
                CAMPUS_KB_MODE="production",
                CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC="true",
                CAMPUS_ALLOW_SAMPLE_KB="false",
            )
            tool_calls = []

            def successful_tool(state, tool_name, arguments):
                tool_calls.append(tool_name)
                return {
                    "toolName": tool_name,
                    "success": True,
                    "data": [{"id": 1, "name": "Dorm repair center", "address": "Building 1"}],
                    "count": 1,
                    "message": None,
                    "latencyMs": 1.0,
                    "toolProtocol": "http_internal",
                }

            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ), patch("agent.campus_support_agent.call_business_tool", side_effect=successful_tool):
                agent = CampusSupportAgent(kb_dir=str(Path(temporary_directory) / "knowledge"))
                response = agent.chat(CampusAssistantRequest(question="My dorm door needs repair", role="student"))

        self.assertEqual(["query_service_points"], tool_calls)
        self.assertIsNone(response.fallbackReason)
        self.assertTrue(response.sources)
        self.assertFalse(any(source.type == "knowledge" for source in response.sources))

    def test_permission_denied_chat_flow_stays_sanitized_without_knowledge(self) -> None:
        with self._temporary_directory() as temporary_directory:
            environment = self._agent_environment(
                AGENT_ORCHESTRATOR="legacy",
                CAMPUS_KB_MODE="production",
                CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC="true",
                CAMPUS_ALLOW_SAMPLE_KB="false",
            )

            def denied_tool(state, tool_name, arguments):
                return {
                    "toolName": tool_name,
                    "success": False,
                    "data": None,
                    "count": 0,
                    "message": "PERMISSION_DENIED",
                    "errorType": "PERMISSION_DENIED",
                    "latencyMs": 1.0,
                    "toolProtocol": "http_internal",
                }

            with patch.dict(os.environ, environment, clear=False), patch(
                "rag.retriever.vector_dependencies_enabled",
                return_value=False,
            ), patch("agent.campus_support_agent.call_business_tool", side_effect=denied_tool):
                agent = CampusSupportAgent(kb_dir=str(Path(temporary_directory) / "knowledge"))
                response = agent.chat(CampusAssistantRequest(question="Show ticket 999 status", role="student"))

        self.assertEqual("PERMISSION_DENIED", response.fallbackReason)
        self.assertEqual([], response.sources)
        self.assertEqual([], response.businessCards)

    @staticmethod
    def _agent_environment(**overrides: str) -> dict[str, str]:
        environment = {
            "AGENT_ORCHESTRATOR": "legacy",
            "AI_INTENT_ROUTER_MODE": "keyword",
            "AI_CHECKPOINT_ENABLED": "false",
            "AI_EMBEDDING_PROVIDER": "disabled",
            "AI_EMBEDDING_MODEL": "text-embedding-3-small",
            "AI_EMBEDDING_DIMENSION": "1536",
            "AI_EMBEDDING_ALGORITHM_REVISION": "disabled-v1",
            "AI_EMBEDDING_DEPLOYMENT_REVISION": "knowledge-boundary-tests",
            "AI_LOCAL_EMBEDDINGS": "false",
            "CAMPUS_VECTOR_INDEX_DIR": "",
            "MILVUS_ENABLED": "false",
            "OPENAI_API_KEY": "",
        }
        environment.update(overrides)
        return environment

    @staticmethod
    def _write_formal_keyword_cache(cache_directory: Path) -> None:
        writer = CampusKnowledgeRetriever(persist_dir=str(cache_directory))
        writer.reload(
            ["Formal cache entry contains formal-cache-token."],
            knowledge_version="formal-cache-v1",
            knowledge_source="ai_knowledge",
        )

    @staticmethod
    def _read_manifest(cache_directory: Path) -> dict[str, object]:
        return json.loads((cache_directory / "manifest.json").read_text(encoding="utf-8"))

    @staticmethod
    def _write_manifest(cache_directory: Path, manifest: dict[str, object]) -> None:
        (cache_directory / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )

    @staticmethod
    def _milvus_hit(
        content: str,
        knowledge_version: str | None,
        source: str,
    ) -> MagicMock:
        hit = MagicMock()
        hit.score = 0.9
        hit.entity = {
            "knowledgeId": 1,
            "title": "Formal knowledge",
            "category": "acceptance",
            "source": source,
            "content": content,
            "knowledgeVersion": knowledge_version,
        }
        return hit

    @staticmethod
    def _response_state(tool_result: dict[str, object]) -> dict[str, object]:
        return {
            "response": "",
            "user_input": "show my ticket",
            "intent": "ticket_status",
            "escalate": False,
            "knowledge_initialized": False,
            "knowledge_sources": [],
            "retrieved_context": "",
            "business_tool_results": [tool_result],
            "recommended_service_points": [],
            "service_points": [],
            "tickets": [],
            "appointments": [],
            "execution_records": [],
            "fallback_records": [],
            "lang_graph_nodes": [],
        }

    @staticmethod
    def _temporary_directory() -> tempfile.TemporaryDirectory:
        temporary_root = Path(__file__).resolve().parents[1] / ".tmp"
        temporary_root.mkdir(parents=True, exist_ok=True)
        return tempfile.TemporaryDirectory(dir=temporary_root)


if __name__ == "__main__":
    unittest.main()
