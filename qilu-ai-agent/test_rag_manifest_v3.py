from __future__ import annotations

import json
import os
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

from agent.campus_support_agent import CampusSupportAgent
from app.metrics import CallMetrics
from app.schemas import KnowledgeReloadItem, KnowledgeReloadResponse
from rag.retriever import CampusKnowledgeRetriever, KnowledgeDocument


class _TraceSpan:
    def __init__(self) -> None:
        self.attributes: dict[str, object] = {}

    def set_attribute(self, name: str, value: object) -> None:
        self.attributes[name] = value


class RagManifestV3Test(unittest.TestCase):
    def test_manifest_v3_records_complete_query_projection_identity(self) -> None:
        with self._temporary_directory() as directory, self._runtime(directory):
            retriever = self._write_v3(directory, "manifest-v3")
            manifest = self._manifest(directory)

        required = {
            "manifestSchemaVersion", "documentCount", "chunkCount", "knowledgeVersion",
            "indexVersion", "embeddingProvider", "embeddingModel", "embeddingDimension",
            "embeddingAlgorithmRevision", "embeddingDeploymentRevision", "embeddingFingerprint",
            "chunkSize", "chunkOverlap", "chunkUnit", "chunkAlgorithm", "chunkFingerprint",
            "lexicalImplementation", "lexicalRevision", "lexicalParameters", "lexicalFingerprint",
            "indexSchemaVersion", "faissIndexPresent", "bm25IndexPresent",
            "milvusCollectionName", "backendStates",
        }
        self.assertTrue(required.issubset(manifest))
        self.assertEqual(3, manifest["manifestSchemaVersion"])
        self.assertEqual(retriever.index_version, manifest["indexVersion"])
        self.assertEqual(len(retriever.chunks), manifest["chunkCount"])
        self.assertEqual({"bm25", "faiss", "milvus"}, set(manifest["backendStates"]))

    def test_rc_u14_v2_documents_rebuild_v3_without_reusing_old_indexes(self) -> None:
        with self._temporary_directory() as directory, self._runtime(directory):
            self._write_v3(directory, "migration-v2")
            self._downgrade_to_v2(directory, vector_present=True)
            (directory / "index.faiss").write_bytes(b"legacy-faiss-must-not-load")
            (directory / "index.pkl").write_bytes(b"legacy-pickle-must-not-load")

            with patch("rag.retriever.vector_dependencies_enabled", return_value=False):
                restored = CampusKnowledgeRetriever(persist_dir=str(directory))
            manifest = self._manifest(directory)
            stale_indexes_removed = not (directory / "index.faiss").exists() and not (
                directory / "index.pkl"
            ).exists()

        self.assertTrue(restored.is_initialized())
        self.assertEqual("v2_migrated_to_v3", restored.status()["cacheValidationReason"])
        self.assertEqual(3, manifest["manifestSchemaVersion"])
        self.assertFalse(manifest["faissIndexPresent"])
        self.assertTrue(stale_indexes_removed)

    def test_v2_migration_failure_keeps_committed_v2_manifest(self) -> None:
        with self._temporary_directory() as directory, self._runtime(directory):
            self._write_v3(directory, "migration-failure")
            self._downgrade_to_v2(directory, vector_present=False)

            with patch.object(
                CampusKnowledgeRetriever,
                "_build_bm25_index",
                side_effect=RuntimeError("injected-migration-failure"),
            ):
                restored = CampusKnowledgeRetriever(persist_dir=str(directory))
            manifest = self._manifest(directory)
            staging_directories = list(directory.glob(".cache-write-*"))

        self.assertFalse(restored.is_initialized())
        self.assertEqual(2, manifest["manifestSchemaVersion"])
        self.assertEqual("CACHE_MIGRATION_FAILED", restored.status()["cacheMigrationErrorCode"])
        self.assertEqual([], staging_directories)

    def test_v2_to_v3_commit_failure_rolls_back_all_v2_files(self) -> None:
        with self._temporary_directory() as directory, self._runtime(directory):
            self._write_v3(directory, "migration-commit-failure")
            self._downgrade_to_v2(directory, vector_present=False)
            original_documents = (directory / "documents.json").read_bytes()
            real_replace = os.replace

            def fail_v3_manifest_commit(source, target):
                source_path = Path(source)
                target_path = Path(target)
                if source_path.parent.name.startswith(".cache-write-") and target_path.name == "manifest.json":
                    raise OSError("injected-v3-manifest-commit-failure")
                return real_replace(source, target)

            with patch("rag.retriever.os.replace", side_effect=fail_v3_manifest_commit):
                restored = CampusKnowledgeRetriever(persist_dir=str(directory))
            manifest = self._manifest(directory)
            restored_documents = (directory / "documents.json").read_bytes()
            status = restored.status()
            backup_directories = list(directory.glob(".cache-backup-*"))

        self.assertEqual(2, manifest["manifestSchemaVersion"])
        self.assertEqual(original_documents, restored_documents)
        self.assertEqual("CACHE_MIGRATION_FAILED", status["cacheMigrationErrorCode"])
        self.assertEqual("DEGRADED", status["reloadState"])
        self.assertEqual([], backup_directories)

    def test_v3_fingerprint_mismatch_is_rejected(self) -> None:
        with self._temporary_directory() as directory, self._runtime(directory):
            self._write_v3(directory, "fingerprint-v3")
            manifest = self._manifest(directory)
            manifest["chunkSize"] = int(manifest["chunkSize"]) + 1
            self._write_manifest(directory, manifest)
            restored = CampusKnowledgeRetriever(persist_dir=str(directory))

        self.assertFalse(restored.is_initialized())
        self.assertEqual("chunk_size_mismatch", restored.status()["cacheValidationReason"])

    def test_v3_faiss_file_hash_mismatch_is_rejected_before_deserialization(self) -> None:
        with self._temporary_directory() as directory, self._runtime(
            directory,
            AI_EMBEDDING_PROVIDER="local",
            AI_EMBEDDING_MODEL="local-hash-v1",
            AI_EMBEDDING_DIMENSION="16",
            AI_EMBEDDING_ALGORITHM_REVISION="local-hash-v1",
            AI_LOCAL_EMBEDDINGS="true",
        ):
            self._write_v3(directory, "faiss-hash-v3")
            with (directory / "index.faiss").open("ab") as stream:
                stream.write(b"tampered")
            restored = CampusKnowledgeRetriever(persist_dir=str(directory))

        self.assertFalse(restored.is_initialized())
        self.assertEqual("faiss_index_hash_mismatch", restored.status()["cacheValidationReason"])

    def test_empty_v3_snapshot_survives_restart_without_old_knowledge(self) -> None:
        with self._temporary_directory() as directory, self._runtime(directory):
            retriever = self._write_v3(directory, "before-empty")
            retriever.reload_documents([], "empty-v3", "ai_knowledge")
            restarted = CampusKnowledgeRetriever(persist_dir=str(directory))
            status = restarted.status()

        self.assertEqual("empty-v3", status["activeKnowledgeVersion"])
        self.assertEqual(0, status["knowledgeDocumentCount"])
        self.assertEqual(0, status["knowledgeChunkCount"])
        self.assertEqual([], restarted.retrieve_documents("stage-e-token"))

    def test_reload_response_uses_stable_error_and_preserves_active_version(self) -> None:
        with self._temporary_directory() as directory, self._runtime(directory):
            agent = CampusSupportAgent(kb_dir=str(directory / "missing"))
            active = KnowledgeReloadItem(
                id=1,
                title="阶段 E 契约",
                content="stage-e-active-token",
                category="acceptance",
                source="ai_knowledge",
            )
            agent.reload_knowledge([active], "active-v1")
            invalid = KnowledgeReloadItem(
                title="缺少正式 ID",
                content="candidate must fail",
                category="acceptance",
                source="ai_knowledge",
            )
            payload = agent.reload_knowledge_contract([invalid], "candidate-v2")
            response = KnowledgeReloadResponse(**payload)

        self.assertFalse(response.success)
        self.assertFalse(response.activated)
        self.assertEqual("RAG_DOCUMENTS_INVALID", response.errorCode)
        self.assertEqual("active-v1", response.activeKnowledgeVersion)
        self.assertNotIn("candidate must fail", response.message)

    def test_status_uses_collection_summaries_and_omits_absolute_cache_path(self) -> None:
        with self._temporary_directory() as directory, self._runtime(
            directory,
            MILVUS_COLLECTION="private_collection_stage_e",
        ):
            status = CampusKnowledgeRetriever(persist_dir=str(directory)).status()

        serialized = json.dumps(status, ensure_ascii=False)
        self.assertNotIn("vectorIndexDir", status)
        self.assertNotIn("milvusCollectionName", status)
        self.assertNotIn("private_collection_stage_e", serialized)
        self.assertRegex(status["milvusCollectionSummary"], r"^sha256:[0-9a-f]{12}$")

    def test_metrics_and_trace_expose_bounded_rag_diagnostics(self) -> None:
        collector = CallMetrics()
        collector.record_rag_prepare("bm25", "ready", 4.5)
        collector.record_rag_activate("active")
        collector.record_rag_cleanup("failed")
        collector.set_rag_active_snapshot("knowledge-secret", "index-secret", 2)
        collector.record_rag_retrieval("bm25", 3, 1, 2.5)
        collector.record_rag_fusion("none")
        collector.record_rag_no_source("no_usable_candidates")
        collector.record_rag_degraded("milvus", "unavailable")
        prometheus = collector.prometheus()

        with self._temporary_directory() as directory, self._runtime(directory):
            retriever = self._write_v3(directory, "trace-v3")
            span = _TraceSpan()
            hits = retriever.retrieve_documents("stage-e-token", trace_span=span)

        self.assertTrue(hits)
        self.assertIn('rag_index_prepare_total{backend="bm25",result="ready"} 1', prometheus)
        self.assertIn('rag_active_snapshot_info{knowledge_version="', prometheus)
        self.assertNotIn("knowledge-secret", prometheus)
        expected_trace_fields = {
            "ai.rag.knowledge_version", "ai.rag.index_version", "ai.rag.chunk_count",
            "ai.rag.vector_backend", "ai.rag.vector_candidate_count",
            "ai.rag.lexical_candidate_count", "ai.rag.filtered_candidate_count",
            "ai.rag.final_hit_count", "ai.rag.degraded", "ai.rag.fallback_reason",
        }
        self.assertEqual(expected_trace_fields, set(span.attributes))
        self.assertFalse(any("stage-e-token" in str(value) for value in span.attributes.values()))

    @staticmethod
    def _write_v3(directory: Path, version: str) -> CampusKnowledgeRetriever:
        retriever = CampusKnowledgeRetriever(persist_dir=str(directory))
        retriever.reload_documents(
            [KnowledgeDocument(
                id=1,
                title="阶段 E 索引",
                content="正式知识包含 stage-e-token。",
                keywords=["stage-e-token"],
                category="acceptance",
                source="ai_knowledge",
            )],
            version,
            "ai_knowledge",
        )
        return retriever

    @staticmethod
    def _downgrade_to_v2(directory: Path, vector_present: bool) -> None:
        v3 = RagManifestV3Test._manifest(directory)
        v2 = {
            "manifestSchemaVersion": 2,
            "knowledgeSource": v3["knowledgeSource"],
            "knowledgeVersion": v3["knowledgeVersion"],
            "knowledgeUpdatedAt": v3["knowledgeUpdatedAt"],
            "documentCount": v3["documentCount"],
            "contentHash": v3["contentHash"],
            "embeddingProvider": v3["embeddingProvider"],
            "embeddingModel": v3["embeddingModel"],
            "embeddingDimension": v3["embeddingDimension"],
            "vectorIndexPresent": vector_present,
        }
        RagManifestV3Test._write_manifest(directory, v2)

    @staticmethod
    def _manifest(directory: Path) -> dict[str, object]:
        return json.loads((directory / "manifest.json").read_text(encoding="utf-8"))

    @staticmethod
    def _write_manifest(directory: Path, manifest: dict[str, object]) -> None:
        (directory / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )

    @staticmethod
    def _runtime(directory: Path, **overrides: str):
        environment = {
            "AI_SKIP_DOTENV": "true",
            "AI_CHECKPOINT_ENABLED": "false",
            "AI_EMBEDDING_PROVIDER": "disabled",
            "AI_EMBEDDING_MODEL": "disabled-v1",
            "AI_EMBEDDING_DIMENSION": "16",
            "AI_EMBEDDING_ALGORITHM_REVISION": "disabled-v1",
            "AI_EMBEDDING_DEPLOYMENT_REVISION": "rag-stage-e-tests",
            "AI_LOCAL_EMBEDDINGS": "false",
            "OPENAI_API_KEY": "",
            "CAMPUS_KB_MODE": "production",
            "CAMPUS_REQUIRE_AI_KNOWLEDGE_SYNC": "true",
            "CAMPUS_ALLOW_SAMPLE_KB": "false",
            "CAMPUS_VECTOR_INDEX_DIR": str(directory),
            "MILVUS_ENABLED": "false",
            "MILVUS_COLLECTION": "qilu_ai_knowledge_stage_e",
            "RAG_REQUIRED_BACKENDS": "bm25",
        }
        environment.update(overrides)
        return patch.dict(os.environ, environment, clear=False)

    @staticmethod
    @contextmanager
    def _temporary_directory():
        root = Path(__file__).resolve().parents[1] / ".tmp"
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as directory:
            yield Path(directory)


if __name__ == "__main__":
    unittest.main()
