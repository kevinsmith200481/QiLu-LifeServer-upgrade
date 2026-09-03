from __future__ import annotations

import os
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from dataclasses import FrozenInstanceError, replace
from unittest.mock import MagicMock, patch

import rag.retriever as retriever_module
from rag.chunking import KnowledgeIndexConfigurationError
from rag.retriever import (
    CampusKnowledgeRetriever,
    KnowledgeDocument,
    KnowledgeReloadError,
)


class _FakeHit:
    def __init__(self, row: dict[str, object]) -> None:
        self.entity = row
        self.score = 1.0


class _FakeCollection:
    def __init__(self, backend: "_FakeMilvus", name: str, schema) -> None:
        self.backend = backend
        self.name = name
        self.schema = schema
        self.rows: list[dict[str, object]] = []
        self.index_ready = False

    @property
    def num_entities(self) -> int:
        return len(self.rows)

    def insert(self, rows) -> None:
        self._fail("insert")
        self.rows.extend(dict(row) for row in rows)

    def flush(self) -> None:
        self._fail("flush")

    def create_index(self, *_args, **_kwargs) -> None:
        self._fail("index")
        self.index_ready = True

    def load(self) -> None:
        self._fail("load")

    def has_index(self) -> bool:
        return self.index_ready

    def search(self, *_args, **_kwargs):
        self._fail("probe")
        return [[_FakeHit(self.rows[0])]] if self.rows else [[]]

    def _fail(self, stage: str) -> None:
        if self.backend.fail_stage == stage:
            raise RuntimeError(f"injected-{stage}-failure")


class _FakeMilvus:
    """覆盖本阶段使用的 pymilvus 最小协议，并保存 alias/Collection 可断言状态。"""

    def __init__(self) -> None:
        self.collections: dict[str, _FakeCollection] = {}
        self.aliases: dict[str, str] = {}
        self.fail_stage: str | None = None
        self.cleanup_failures: set[str] = set()
        self.embedding_calls = 0

    def collection(self, name: str, schema=None, using=None):
        resolved = self.aliases.get(name, name)
        if schema is not None:
            collection = _FakeCollection(self, name, schema)
            self.collections[name] = collection
            return collection
        if resolved not in self.collections:
            raise RuntimeError(f"collection does not exist: {resolved}")
        return self.collections[resolved]

    def has_collection(self, name: str, using=None) -> bool:
        return self.aliases.get(name, name) in self.collections

    def drop_collection(self, name: str, using=None) -> None:
        resolved = self.aliases.get(name, name)
        if resolved in self.cleanup_failures:
            raise RuntimeError("injected-cleanup-failure")
        self.collections.pop(resolved, None)
        for alias, target in list(self.aliases.items()):
            if target == resolved:
                self.aliases.pop(alias, None)

    def list_collections(self, using=None):
        return list(self.collections)

    def list_aliases(self, collection_name: str, using=None):
        return [alias for alias, target in self.aliases.items() if target == collection_name]

    def create_alias(self, collection_name: str, alias: str, using=None) -> None:
        self._switch_alias(collection_name, alias)

    def alter_alias(self, collection_name: str, alias: str, using=None) -> None:
        self._switch_alias(collection_name, alias)

    def drop_alias(self, alias: str, using=None) -> None:
        if self.fail_stage == "alias":
            raise RuntimeError("injected-alias-failure")
        self.aliases.pop(alias, None)

    def _switch_alias(self, collection_name: str, alias: str) -> None:
        if self.fail_stage == "alias":
            raise RuntimeError("injected-alias-failure")
        if collection_name not in self.collections:
            raise RuntimeError("candidate collection is missing")
        self.aliases[alias] = collection_name


class _CountingEmbeddings:
    def __init__(self, backend: _FakeMilvus, delegate) -> None:
        self.backend = backend
        self.delegate = delegate

    def embed_documents(self, texts):
        self.backend.embedding_calls += 1
        return self.delegate.embed_documents(texts)

    def embed_query(self, text):
        return self.delegate.embed_query(text)


class RagAtomicReloadTest(unittest.TestCase):
    @unittest.skipUnless(
        os.getenv("AI_RAG_LIVE_MILVUS_TEST", "").strip().lower() in {"1", "true", "yes", "on"},
        "live Milvus acceptance is disabled",
    )
    def test_live_milvus_versioned_collection_alias_and_idempotence(self) -> None:
        collection_base = os.getenv(
            "AI_RAG_LIVE_MILVUS_COLLECTION",
            "qilu_ai_knowledge_stage_d_acceptance",
        ) or "qilu_ai_knowledge_stage_d_acceptance"
        self.assertIn("stage_d", collection_base, "live acceptance must use an isolated stage_d collection")
        # 独立变量避免宿主环境中的空 MILVUS_COLLECTION 覆盖验收前缀，再在测试作用域内显式绑定。
        with patch.dict(os.environ, {"MILVUS_COLLECTION": collection_base}, clear=False):
            retriever = CampusKnowledgeRetriever()
            self._assert_live_milvus_reload(retriever, collection_base)

    def _assert_live_milvus_reload(
        self,
        retriever: CampusKnowledgeRetriever,
        collection_base: str,
    ) -> None:
        try:
            first = self._load(retriever, "live-v1", "live version-one atomic-token")
            v1_collection = retriever._capture_active_snapshot().milvus_physical_collection
            second = self._load(retriever, "live-v2", "live version-two atomic-token")
            v2_collection = retriever._capture_active_snapshot().milvus_physical_collection
            idempotent = self._load(retriever, "live-v2", "live version-two atomic-token")

            self.assertTrue(first.activated)
            self.assertTrue(second.activated)
            self.assertTrue(idempotent.idempotent)
            self.assertNotEqual(v1_collection, v2_collection)
            self.assertIn(
                retriever.milvus.active_alias,
                retriever_module.utility.list_aliases(v2_collection, using=retriever.milvus.alias),
            )
            self.assertEqual(["live-v2"], self._versions(retriever, "atomic-token"))
        finally:
            retriever.milvus.clear()
            retriever.milvus.cleanup_superseded(force=True)
            if retriever.milvus.connect():
                for collection_name in retriever_module.utility.list_collections(using=retriever.milvus.alias):
                    if collection_name == collection_base or collection_name.startswith(collection_base + "__"):
                        retriever_module.utility.drop_collection(collection_name, using=retriever.milvus.alias)
                retriever_module.connections.disconnect(retriever.milvus.alias)

    def test_rc_u25_embedding_failure_keeps_v1_active(self) -> None:
        with self._fake_milvus_runtime() as (retriever, backend):
            self._load(retriever, "v1", "version-one atomic-token")
            old_snapshot = retriever._capture_active_snapshot()
            old_alias = backend.aliases[retriever.milvus.active_alias]
            invalid_embeddings = MagicMock()
            invalid_embeddings.embed_documents.return_value = [[0.0, 1.0]]

            with patch.object(retriever, "_embeddings", return_value=invalid_embeddings):
                with self.assertRaises(KnowledgeReloadError):
                    self._load(retriever, "v2", "version-two atomic-token")

            self.assertIs(old_snapshot, retriever._capture_active_snapshot())
            self.assertEqual(old_alias, backend.aliases[retriever.milvus.active_alias])
            self.assertEqual(["v1"], self._versions(retriever, "atomic-token"))

    def test_cleanup_configuration_fails_fast_before_any_alias_write(self) -> None:
        for overrides in (
            {"RAG_MILVUS_RETAINED_COLLECTIONS": "0"},
            {"RAG_MILVUS_CLEANUP_GRACE_SECONDS": "not-a-number"},
        ):
            with self.subTest(overrides=overrides), patch.dict(
                os.environ,
                self._environment(**overrides),
                clear=True,
            ):
                with self.assertRaises(KnowledgeIndexConfigurationError):
                    CampusKnowledgeRetriever()

    def test_rc_u26_to_u28_candidate_and_alias_failures_keep_v1_active(self) -> None:
        for stage in ("insert", "flush", "index", "load", "probe", "alias"):
            with self.subTest(stage=stage), self._fake_milvus_runtime() as (retriever, backend):
                self._load(retriever, "v1", "version-one atomic-token")
                old_snapshot = retriever._capture_active_snapshot()
                old_alias = backend.aliases[retriever.milvus.active_alias]
                backend.fail_stage = stage

                with self.assertRaises(KnowledgeReloadError):
                    self._load(retriever, "v2", "version-two atomic-token")

                candidate_name = retriever.milvus._physical_collection_name(
                    retriever._build_chunk_projection(
                        [self._document("version-two atomic-token")],
                        "v2",
                        "ai_knowledge",
                    )[0].index_version
                )
                self.assertIs(old_snapshot, retriever._capture_active_snapshot())
                self.assertEqual(old_alias, backend.aliases[retriever.milvus.active_alias])
                self.assertNotIn(candidate_name, backend.collections)
                self.assertEqual(["v1"], self._versions(retriever, "atomic-token"))

    def test_rc_u29_required_faiss_or_bm25_failure_rejects_activation(self) -> None:
        with patch.dict(os.environ, self._environment(MILVUS_ENABLED="false"), clear=True), patch(
            "rag.retriever.vector_dependencies_enabled",
            return_value=False,
        ):
            retriever = CampusKnowledgeRetriever()
            self._load(retriever, "v1", "version-one atomic-token")
            old_snapshot = retriever._capture_active_snapshot()

            retriever.required_backends = frozenset({"bm25", "faiss"})
            with self.assertRaises(KnowledgeReloadError):
                self._load(retriever, "v2", "version-two atomic-token")
            self.assertIs(old_snapshot, retriever._capture_active_snapshot())

            retriever.required_backends = frozenset({"bm25"})
            with patch.object(retriever, "_build_bm25_index", side_effect=RuntimeError("bm25-failed")):
                with self.assertRaises(KnowledgeReloadError):
                    self._load(retriever, "v3", "version-three atomic-token")
            self.assertIs(old_snapshot, retriever._capture_active_snapshot())

    def test_rc_u30_optional_milvus_failure_activates_degraded_snapshot(self) -> None:
        with self._fake_milvus_runtime(required="bm25") as (retriever, backend):
            self._load(retriever, "v1", "version-one atomic-token")
            backend.fail_stage = "insert"

            result = self._load(retriever, "v2", "version-two atomic-token")

            self.assertTrue(result.success)
            self.assertTrue(result.activated)
            self.assertTrue(result.degraded)
            self.assertEqual("FAILED", result.backend_states["milvus"])
            self.assertEqual(["v2"], self._versions(retriever, "atomic-token"))

    def test_rc_u31_same_index_version_is_idempotent(self) -> None:
        with self._fake_milvus_runtime() as (retriever, backend):
            counting = _CountingEmbeddings(backend, retriever._embeddings())
            with patch.object(retriever, "_embeddings", return_value=counting):
                first = self._load(retriever, "v1", "idempotent atomic-token")
                collection_count = len(backend.collections)
                second = self._load(retriever, "v1", "idempotent atomic-token")

            self.assertTrue(first.activated)
            self.assertTrue(second.idempotent)
            self.assertFalse(second.activated)
            self.assertEqual(1, backend.embedding_calls)
            self.assertEqual(collection_count, len(backend.collections))

    def test_rc_u32_two_concurrent_reloads_are_single_writer(self) -> None:
        with patch.dict(os.environ, self._environment(MILVUS_ENABLED="false"), clear=True), patch(
            "rag.retriever.vector_dependencies_enabled",
            return_value=False,
        ):
            retriever = CampusKnowledgeRetriever()
            self._load(retriever, "v1", "version-one atomic-token")
            original_builder = retriever._build_bm25_index
            guard = threading.Lock()
            active_builders = 0
            maximum_builders = 0

            def slow_builder(chunks, documents):
                nonlocal active_builders, maximum_builders
                with guard:
                    active_builders += 1
                    maximum_builders = max(maximum_builders, active_builders)
                time.sleep(0.02)
                try:
                    return original_builder(chunks, documents)
                finally:
                    with guard:
                        active_builders -= 1

            with patch.object(retriever, "_build_bm25_index", side_effect=slow_builder):
                with ThreadPoolExecutor(max_workers=2) as executor:
                    results = list(executor.map(
                        lambda item: self._load(retriever, item[0], item[1]),
                        (("v2", "version-two atomic-token"), ("v3", "version-three atomic-token")),
                    ))

            self.assertEqual(1, maximum_builders)
            self.assertTrue(all(result.success for result in results))
            active_version = retriever._capture_active_snapshot().knowledge_version
            self.assertIn(active_version, {"v2", "v3"})
            self.assertEqual([active_version], self._versions(retriever, "atomic-token"))

    def test_rc_u33_fifty_queries_see_complete_v1_or_v2_snapshot(self) -> None:
        with patch.dict(os.environ, self._environment(MILVUS_ENABLED="false"), clear=True), patch(
            "rag.retriever.vector_dependencies_enabled",
            return_value=False,
        ):
            retriever = CampusKnowledgeRetriever()
            self._load(retriever, "v1", "version-one atomic-token")
            old_snapshot = retriever._capture_active_snapshot()
            started = threading.Barrier(26)
            release = threading.Event()

            class BlockingIndex:
                def search(self, query, limit):
                    started.wait(timeout=5)
                    release.wait(timeout=5)
                    return old_snapshot.bm25_index.search(query, limit)

            retriever._publish_snapshot(replace(old_snapshot, bm25_index=BlockingIndex()))
            with ThreadPoolExecutor(max_workers=50) as executor:
                old_futures = [
                    executor.submit(self._versions, retriever, "atomic-token")
                    for _ in range(25)
                ]
                started.wait(timeout=5)
                self._load(retriever, "v2", "version-two atomic-token")
                release.set()
                new_futures = [
                    executor.submit(self._versions, retriever, "atomic-token")
                    for _ in range(25)
                ]
                responses = [future.result(timeout=5) for future in old_futures + new_futures]

            self.assertEqual([["v1"]] * 25, responses[:25])
            self.assertEqual([["v2"]] * 25, responses[25:])
            self.assertTrue(all(len(response) == 1 for response in responses))

    def test_old_snapshot_keeps_querying_v1_physical_collection_after_alias_switch(self) -> None:
        with self._fake_milvus_runtime() as (retriever, _backend):
            self._load(retriever, "v1", "version-one atomic-token")
            old_snapshot = retriever._capture_active_snapshot()
            self._load(retriever, "v2", "version-two atomic-token")

            old_hits = retriever.milvus.search(
                "atomic-token",
                3,
                old_snapshot.knowledge_version,
                old_snapshot.index_version,
                physical_collection=old_snapshot.milvus_physical_collection,
            )
            filtered_old_hits = retriever._filter_active_candidates(old_hits, (), old_snapshot)

            self.assertEqual(["v1"], [hit.metadata["knowledgeVersion"] for hit in filtered_old_hits])
            self.assertEqual(["v2"], self._versions(retriever, "atomic-token"))

    def test_rc_u34_cleanup_failure_does_not_roll_back_v2(self) -> None:
        with self._fake_milvus_runtime(
            RAG_MILVUS_RETAINED_COLLECTIONS="1",
            RAG_MILVUS_CLEANUP_GRACE_SECONDS="60",
        ) as (retriever, backend):
            self._load(retriever, "v1", "version-one atomic-token")
            v1_collection = backend.aliases[retriever.milvus.active_alias]
            backend.cleanup_failures.add(v1_collection)
            self._load(retriever, "v2", "version-two atomic-token")

            retriever.milvus.cleanup_superseded(force=True)

            self.assertEqual("RuntimeError", retriever.milvus.last_cleanup_error)
            self.assertEqual(
                retriever._capture_active_snapshot().milvus_physical_collection,
                backend.aliases[retriever.milvus.active_alias],
            )
            self.assertEqual(["v2"], self._versions(retriever, "atomic-token"))

    def test_stage_e_cleanup_failure_remains_pending_and_retry_succeeds(self) -> None:
        with self._fake_milvus_runtime(
            RAG_MILVUS_RETAINED_COLLECTIONS="1",
            RAG_MILVUS_CLEANUP_GRACE_SECONDS="60",
        ) as (retriever, backend):
            self._load(retriever, "v1", "version-one cleanup-retry-token")
            v1_collection = backend.aliases[retriever.milvus.active_alias]
            backend.cleanup_failures.add(v1_collection)
            self._load(retriever, "v2", "version-two cleanup-retry-token")

            retriever.milvus.cleanup_superseded(force=True)
            failed_status = retriever.milvus.status()
            backend.cleanup_failures.remove(v1_collection)
            retriever.milvus.cleanup_superseded(force=True)
            recovered_status = retriever.milvus.status()

        self.assertEqual("RAG_COLLECTION_CLEANUP_FAILED", failed_status["milvusLastCleanup"]["errorCode"])
        self.assertEqual(1, failed_status["milvusPendingCleanupCount"])
        self.assertEqual("SUCCESS", recovered_status["milvusLastCleanup"]["state"])
        self.assertEqual(0, recovered_status["milvusPendingCleanupCount"])

    def test_rc_u35_empty_sync_activates_explicit_empty_snapshot(self) -> None:
        with self._fake_milvus_runtime() as (retriever, backend):
            self._load(retriever, "v1", "version-one atomic-token")

            result = retriever.reload_documents([], "empty-v2", "ai_knowledge")
            snapshot = retriever._capture_active_snapshot()

            self.assertTrue(result.activated)
            self.assertEqual("empty-v2", snapshot.knowledge_version)
            self.assertEqual("ai_knowledge", snapshot.knowledge_source)
            self.assertEqual((), snapshot.documents)
            self.assertNotIn(retriever.milvus.active_alias, backend.aliases)
            self.assertEqual([], retriever.retrieve_documents("atomic-token"))

    def test_snapshot_structure_is_immutable_and_uses_tuple_collections(self) -> None:
        with patch.dict(os.environ, self._environment(MILVUS_ENABLED="false"), clear=True), patch(
            "rag.retriever.vector_dependencies_enabled",
            return_value=False,
        ):
            retriever = CampusKnowledgeRetriever()
            self._load(retriever, "v1", "immutable atomic-token")
            snapshot = retriever._capture_active_snapshot()

        self.assertIsInstance(snapshot.documents, tuple)
        self.assertIsInstance(snapshot.chunks, tuple)
        with self.assertRaises(FrozenInstanceError):
            snapshot.knowledge_version = "mutated"
        with self.assertRaises(TypeError):
            snapshot.backend_states["bm25"] = "FAILED"

    def _fake_milvus_runtime(self, required: str = "bm25,milvus", **overrides):
        backend = _FakeMilvus()
        environment = self._environment(RAG_REQUIRED_BACKENDS=required, **overrides)
        environment_patch = patch.dict(os.environ, environment, clear=True)
        collection_patch = patch.object(retriever_module, "Collection", backend.collection)
        utility_patch = patch.object(retriever_module, "utility", backend)
        connections_patch = patch.object(retriever_module, "connections", MagicMock())
        vector_patch = patch("rag.retriever.vector_dependencies_enabled", return_value=False)

        class RuntimeContext:
            def __enter__(self_nonlocal):
                environment_patch.start()
                collection_patch.start()
                utility_patch.start()
                connections_patch.start()
                vector_patch.start()
                retriever = CampusKnowledgeRetriever()
                return retriever, backend

            def __exit__(self_nonlocal, exc_type, exc, traceback):
                vector_patch.stop()
                connections_patch.stop()
                utility_patch.stop()
                collection_patch.stop()
                environment_patch.stop()

        return RuntimeContext()

    @staticmethod
    def _load(retriever: CampusKnowledgeRetriever, version: str, content: str):
        return retriever.reload_documents(
            [RagAtomicReloadTest._document(content)],
            version,
            "ai_knowledge",
        )

    @staticmethod
    def _document(content: str) -> KnowledgeDocument:
        return KnowledgeDocument(
            id=1,
            title="原子索引测试",
            content=content,
            keywords=["atomic-token"],
            category="acceptance",
            source="ai_knowledge",
        )

    @staticmethod
    def _versions(retriever: CampusKnowledgeRetriever, query: str) -> list[str]:
        return [str(hit.metadata["knowledgeVersion"]) for hit in retriever.retrieve_documents(query)]

    @staticmethod
    def _environment(**overrides) -> dict[str, str]:
        environment = {
            "AI_SKIP_DOTENV": "true",
            "AI_EMBEDDING_PROVIDER": "local",
            "AI_EMBEDDING_MODEL": "local-hash-v1",
            "AI_EMBEDDING_DIMENSION": "4",
            "AI_EMBEDDING_ALGORITHM_REVISION": "local-hash-v1",
            "AI_EMBEDDING_DEPLOYMENT_REVISION": "rag-stage-d-tests",
            "AI_LOCAL_EMBEDDINGS": "true",
            "CAMPUS_KB_MODE": "test",
            "MILVUS_ENABLED": "true",
            "MILVUS_COLLECTION": "qilu_ai_knowledge_atomic_test",
            "RAG_REQUIRED_BACKENDS": "bm25",
            "RAG_MILVUS_RETAINED_COLLECTIONS": "2",
            "RAG_MILVUS_CLEANUP_GRACE_SECONDS": "30",
            "RAG_MIN_MILVUS_SCORE": "0.2",
            "RAG_MIN_BM25_SCORE": "0.2",
        }
        environment.update(overrides)
        return environment


if __name__ == "__main__":
    unittest.main()
