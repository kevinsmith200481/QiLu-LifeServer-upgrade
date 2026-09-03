from __future__ import annotations

import json
import os
import hashlib
import math
import re
import shutil
import threading
import time
import uuid
from collections import OrderedDict
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from pathlib import Path
from types import MappingProxyType
from typing import Dict, List, Mapping, Optional, Sequence, Tuple

from app.metrics import elapsed_ms, metrics, now
from rag.chunking import (
    EmbeddingDescriptor,
    IndexFingerprints,
    KnowledgeChunk,
    KnowledgeIndexConfigurationError,
    KnowledgeIndexValidationError,
    build_index_fingerprints,
    build_knowledge_chunks,
    chunk_config_from_env,
    validate_embedding_vectors,
    validate_milvus_chunk,
)
from rag.lexical import BM25LexicalIndex, LexicalConfig, lexical_config_from_env
from app.acceptance_faults import force_rag_embed_documents_failure

try:
    if os.getenv("AI_LIGHTWEIGHT_RUNTIME", "").strip().lower() in {"1", "true", "yes", "on"}:
        raise ImportError("optional vector runtime disabled for constrained acceptance")
    from langchain_core.embeddings import Embeddings
    from langchain_community.vectorstores import FAISS
    from langchain_openai import OpenAIEmbeddings
    import faiss as faiss_native
except ImportError:
    Embeddings = None
    FAISS = None
    OpenAIEmbeddings = None
    faiss_native = None

try:
    if os.getenv("AI_LIGHTWEIGHT_RUNTIME", "").strip().lower() in {"1", "true", "yes", "on"}:
        raise ImportError("optional Milvus runtime disabled for constrained acceptance")
    from pymilvus import Collection, CollectionSchema, DataType, FieldSchema, connections, utility
except ImportError:
    Collection = None
    CollectionSchema = None
    DataType = None
    FieldSchema = None
    connections = None
    utility = None


@dataclass
class KnowledgeDocument:
    id: Optional[int]
    title: str
    content: str
    keywords: List[str]
    category: Optional[str] = None
    source: Optional[str] = None


@dataclass(slots=True)
class KnowledgeHit:
    content: str
    metadata: Dict[str, object]
    score: Optional[float]
    retriever: str
    normalized_score: Optional[float] = None
    fusion_score: Optional[float] = None
    retrievers: Tuple[str, ...] = ()
    retriever_scores: Dict[str, float] = field(default_factory=dict)
    normalized_retriever_scores: Dict[str, float] = field(default_factory=dict)


@dataclass(frozen=True)
class MilvusCandidate:
    """已经完成构建和探针校验、但尚未切换 active alias 的候选索引。"""

    physical_collection: str
    knowledge_version: str
    index_version: str
    entity_count: int
    state: str = "READY"


@dataclass(frozen=True)
class KnowledgeSnapshot:
    """一次查询从开始到结束唯一读取的不可变知识投影。"""

    knowledge_version: str
    index_version: str
    documents: Tuple[KnowledgeDocument, ...]
    chunks: Tuple[KnowledgeChunk, ...]
    chunks_by_id: Mapping[str, KnowledgeChunk]
    faiss_store: object
    bm25_index: Optional[BM25LexicalIndex]
    milvus_physical_collection: Optional[str]
    fingerprints: Optional[IndexFingerprints]
    knowledge_source: str
    knowledge_updated_at: Optional[str]
    created_at: str
    backend_states: Mapping[str, str]


@dataclass(frozen=True)
class KnowledgeReloadResult:
    """阶段 D 的内部状态机结果；HTTP 契约扩展仍留在阶段 E。"""

    success: bool
    activated: bool
    degraded: bool
    idempotent: bool
    knowledge_version: str
    index_version: str
    backend_states: Mapping[str, str]
    candidate_collection: Optional[str] = None
    error_code: Optional[str] = None
    message: str = ""


class KnowledgeReloadError(RuntimeError):
    """候选未满足强制后端要求时抛出，旧 active snapshot 保持不变。"""

    def __init__(self, error_code: str, message: str) -> None:
        super().__init__(message)
        self.error_code = error_code


class MilvusCandidateError(RuntimeError):
    """携带失败阶段，便于故障注入验证候选不会影响旧 alias。"""

    def __init__(self, stage: str, cause: BaseException) -> None:
        super().__init__(f"Milvus candidate failed at {stage}: {type(cause).__name__}")
        self.stage = stage
        self.cause = cause


DEFAULT_KNOWLEDGE = [
    KnowledgeDocument(None, "campus card lost", "If a campus card is lost, report the loss in the campus card service center and bring your student ID for replacement.", ["card", "lost", "campus card"], "sample", "sample-dev"),
    KnowledgeDocument(None, "dorm repair", "For dormitory repair, submit a repair ticket with building, room number, issue description, and available visit time.", ["repair", "dorm", "broken", "leak"], "sample", "sample-dev"),
    KnowledgeDocument(None, "printing service", "Library printing points support self-service printing and binding. If payment fails, contact the printing point staff.", ["print", "printer", "printing"], "sample", "sample-dev"),
    KnowledgeDocument(None, "express pickup", "Campus express station handles parcel pickup and exception handling. Bring pickup code and valid identity proof.", ["express", "parcel", "package"], "sample", "sample-dev"),
    KnowledgeDocument(None, "career consultation", "Career consultation can help with resume review, interview preparation, and employment policy questions.", ["career", "resume", "job"], "sample", "sample-dev"),
]

ACTIVE_KNOWLEDGE_SOURCES = {"sample-dev", "ai_knowledge"}
MANIFEST_SCHEMA_VERSION = 3
LEGACY_MANIFEST_SCHEMA_VERSION = 2
DOCUMENT_FIELDS = ("id", "title", "content", "keywords", "category", "source")
CACHE_FILE_NAMES = ("documents.json", "index.faiss", "index.pkl", "manifest.json")
SUPPORTED_RAG_BACKENDS = frozenset({"bm25", "faiss", "milvus"})


def required_rag_backends() -> frozenset[str]:
    """解析激活门槛；默认只强制 BM25，生产模板会显式要求 Milvus。"""

    raw_value = os.getenv("RAG_REQUIRED_BACKENDS", "bm25")
    backends = frozenset(item.strip().lower() for item in raw_value.split(",") if item.strip())
    invalid = sorted(backends - SUPPORTED_RAG_BACKENDS)
    if invalid:
        raise KnowledgeIndexConfigurationError(
            "RAG_REQUIRED_BACKENDS contains unsupported backends: " + ",".join(invalid)
        )
    if not backends:
        raise KnowledgeIndexConfigurationError("RAG_REQUIRED_BACKENDS must not be empty")
    return backends


def milvus_cleanup_grace_seconds() -> float:
    return _non_negative_finite_float("RAG_MILVUS_CLEANUP_GRACE_SECONDS", 30.0)


def milvus_retained_collections() -> int:
    raw_value = os.getenv("RAG_MILVUS_RETAINED_COLLECTIONS", "2")
    try:
        value = int(raw_value)
    except (TypeError, ValueError) as exc:
        raise KnowledgeIndexConfigurationError(
            "RAG_MILVUS_RETAINED_COLLECTIONS must be an integer"
        ) from exc
    if value < 1:
        raise KnowledgeIndexConfigurationError(
            "RAG_MILVUS_RETAINED_COLLECTIONS must be at least 1"
        )
    return value


def _non_negative_finite_float(name: str, default: float) -> float:
    raw_value = os.getenv(name)
    try:
        value = default if raw_value is None or not raw_value.strip() else float(raw_value)
    except (TypeError, ValueError) as exc:
        raise KnowledgeIndexConfigurationError(f"{name} must be a number") from exc
    if not math.isfinite(value) or value < 0.0:
        raise KnowledgeIndexConfigurationError(f"{name} must be a non-negative finite number")
    return value


def _immutable_backend_states(states: Mapping[str, str]) -> Mapping[str, str]:
    return MappingProxyType(dict(sorted(states.items())))


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _identifier_summary(value: Optional[str]) -> Optional[str]:
    """只暴露稳定短摘要，避免状态、指标和响应泄漏真实 Collection 名称。"""

    if not value:
        return None
    return "sha256:" + hashlib.sha256(value.encode("utf-8")).hexdigest()[:12]


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return "sha256:" + digest.hexdigest()


def embedding_base_url() -> Optional[str]:
    """Embedding 可使用独立兼容端点；未配置时保持旧版共用模型端点的行为。"""

    return (
        os.getenv("AI_EMBEDDING_BASE_URL")
        or os.getenv("OPENAI_BASE_URL")
        or os.getenv("OPENAI_API_BASE")
    )


def embedding_api_key() -> Optional[str]:
    """Embedding 密钥与回答模型隔离，方便验收和生产分别轮换凭据。"""

    return os.getenv("AI_EMBEDDING_API_KEY") or os.getenv("OPENAI_API_KEY")


class LocalHashEmbeddings(Embeddings or object):
    def __init__(self, dimension: int) -> None:
        self.dimension = dimension
        self._query_cache: OrderedDict[str, Tuple[float, ...]] = OrderedDict()
        self._query_cache_lock = threading.Lock()
        self._query_cache_capacity = 256

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        # 文档向量只在 candidate 构建时使用，不写入查询 LRU，避免批量同步冲掉真实热点问题。
        return [self._embed_uncached(text) for text in texts]

    def embed_query(self, text: str) -> List[float]:
        cache_key = text or ""
        with self._query_cache_lock:
            cached = self._query_cache.get(cache_key)
            if cached is not None:
                self._query_cache.move_to_end(cache_key)
                return list(cached)
        vector = self._embed_uncached(cache_key)
        with self._query_cache_lock:
            self._query_cache[cache_key] = tuple(vector)
            self._query_cache.move_to_end(cache_key)
            while len(self._query_cache) > self._query_cache_capacity:
                self._query_cache.popitem(last=False)
        return vector

    def _embed_uncached(self, text: str) -> List[float]:
        tokens = query_terms(text)
        if not tokens:
            tokens = {char for char in (text or "") if char.strip()}
        sparse_values: Dict[int, float] = {}
        for token in tokens:
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimension
            sign = 1.0 if digest[4] % 2 == 0 else -1.0
            sparse_values[index] = sparse_values.get(index, 0.0) + sign
        norm = math.sqrt(sum(value * value for value in sparse_values.values()))
        vector = [0.0] * self.dimension
        if norm <= 0:
            return vector
        for index, value in sparse_values.items():
            vector[index] = value / norm
        return vector


class L2NormalizedEmbeddings(Embeddings or object):
    """统一兼容服务的向量范数，使 Milvus COSINE 与 FAISS 平方 L2 保持同一排序语义。"""

    def __init__(self, delegate) -> None:
        self.delegate = delegate

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        return [_l2_normalize_vector(vector) for vector in self.delegate.embed_documents(texts)]

    def embed_query(self, text: str) -> List[float]:
        return _l2_normalize_vector(self.delegate.embed_query(text))


class AcceptanceFailingDocumentEmbeddings(Embeddings or object):
    """阶段 F 专用故障包装器；只阻断新 candidate，不破坏旧 snapshot 查询。"""

    def __init__(self, delegate) -> None:
        self.delegate = delegate

    def embed_documents(self, texts: List[str]) -> List[List[float]]:
        raise RuntimeError("acceptance-rag-embed-documents-failure")

    def embed_query(self, text: str) -> List[float]:
        return self.delegate.embed_query(text)


def _l2_normalize_vector(vector: Sequence[float]) -> List[float]:
    values = [float(value) for value in vector]
    norm = math.sqrt(sum(value * value for value in values))
    if not math.isfinite(norm) or norm <= 0.0:
        raise KnowledgeIndexValidationError("embedding vector norm must be finite and greater than zero")
    return [value / norm for value in values]


def build_embeddings():
    descriptor = embedding_descriptor()
    if local_embeddings_enabled():
        embeddings = LocalHashEmbeddings(descriptor.dimension)
    else:
        # 显式传递维度，防止兼容服务按模型默认维度返回向量却被当前索引静默接收。
        embeddings = L2NormalizedEmbeddings(
            OpenAIEmbeddings(
                model=descriptor.model,
                dimensions=descriptor.dimension,
                base_url=embedding_base_url(),
                api_key=embedding_api_key(),
                # chunk 已按受控字符上限切分；兼容服务必须接收原文并使用目标 Embedding 自身 tokenizer。
                check_embedding_ctx_length=False,
            )
        )
    return (
        AcceptanceFailingDocumentEmbeddings(embeddings)
        if force_rag_embed_documents_failure()
        else embeddings
    )


def vector_dependencies_enabled() -> bool:
    return bool(FAISS and (local_embeddings_enabled() or (OpenAIEmbeddings and embedding_api_key())))


def configure_faiss_search_threads() -> int:
    raw_value = os.getenv("RAG_FAISS_SEARCH_THREADS", "1")
    try:
        thread_count = int(raw_value)
    except (TypeError, ValueError) as exc:
        raise KnowledgeIndexConfigurationError("RAG_FAISS_SEARCH_THREADS must be an integer") from exc
    if thread_count < 1 or thread_count > 64:
        raise KnowledgeIndexConfigurationError("RAG_FAISS_SEARCH_THREADS must be between 1 and 64")
    if faiss_native is not None:
        # 当前索引规模较小，固定线程数可避免 OpenMP 为微小查询反复调度全部逻辑处理器。
        faiss_native.omp_set_num_threads(thread_count)
    return thread_count


def local_embeddings_enabled() -> bool:
    provider = os.getenv("AI_EMBEDDING_PROVIDER", "openai").strip().lower()
    explicit = os.getenv("AI_LOCAL_EMBEDDINGS", "").strip().lower()
    return provider in {"local", "hash"} or explicit in {"1", "true", "yes", "on"}


def embedding_provider_name() -> str:
    if local_embeddings_enabled():
        return "local"
    return os.getenv("AI_EMBEDDING_PROVIDER", "openai").strip().lower() or "openai"


def embedding_model_name() -> str:
    configured = (os.getenv("AI_EMBEDDING_MODEL") or "").strip()
    if configured:
        return configured
    if local_embeddings_enabled():
        return "local-hash-v1"
    if embedding_provider_name() == "disabled":
        return "disabled"
    return "text-embedding-3-small"


def embedding_dimension() -> int:
    default_dimension = "384" if local_embeddings_enabled() else "1536"
    raw_value = os.getenv("AI_EMBEDDING_DIMENSION", default_dimension)
    try:
        dimension = int(raw_value)
    except (TypeError, ValueError) as exc:
        raise KnowledgeIndexConfigurationError("AI_EMBEDDING_DIMENSION must be an integer") from exc
    if dimension <= 0 or dimension > 65536:
        raise KnowledgeIndexConfigurationError("AI_EMBEDDING_DIMENSION must be between 1 and 65536")
    return dimension


def embedding_algorithm_revision() -> str:
    configured = (os.getenv("AI_EMBEDDING_ALGORITHM_REVISION") or "").strip()
    provider = embedding_provider_name()
    if configured:
        revision = configured
    elif provider == "local":
        revision = "local-hash-v1"
    elif provider == "disabled":
        revision = "disabled-v1"
    else:
        revision = "openai-v1"
    # 外部向量统一归一化后，算法身份必须变化，防止加载旧的非单位范数 FAISS 索引。
    if provider not in {"local", "disabled"} and not revision.endswith("+l2-normalized-v1"):
        revision += "+l2-normalized-v1"
    return revision


def embedding_descriptor() -> EmbeddingDescriptor:
    # 生产环境必须显式声明完整的非敏感模型身份，避免隐式默认值复用旧索引。
    production_mode = (os.getenv("CAMPUS_KB_MODE") or "").strip().lower() == "production"
    provider = embedding_provider_name()
    if production_mode and provider != "disabled":
        required_names = (
            "AI_EMBEDDING_PROVIDER",
            "AI_EMBEDDING_MODEL",
            "AI_EMBEDDING_DIMENSION",
            "AI_EMBEDDING_ALGORITHM_REVISION",
        )
        missing = [name for name in required_names if not (os.getenv(name) or "").strip()]
        if provider == "openai" and not (os.getenv("AI_EMBEDDING_DEPLOYMENT_REVISION") or "").strip():
            missing.append("AI_EMBEDDING_DEPLOYMENT_REVISION")
        if missing:
            raise KnowledgeIndexConfigurationError(
                "production embedding configuration is incomplete: " + ",".join(missing)
            )
    descriptor = EmbeddingDescriptor(
        provider=provider,
        model=embedding_model_name(),
        dimension=embedding_dimension(),
        algorithm_revision=embedding_algorithm_revision(),
        deployment_revision=(os.getenv("AI_EMBEDDING_DEPLOYMENT_REVISION") or "default").strip(),
    )
    if not descriptor.provider or not descriptor.model or not descriptor.algorithm_revision:
        raise KnowledgeIndexConfigurationError("embedding provider, model and revision must not be empty")
    if not descriptor.deployment_revision:
        raise KnowledgeIndexConfigurationError("AI_EMBEDDING_DEPLOYMENT_REVISION must not be empty")
    return descriptor


def milvus_enabled() -> bool:
    value = os.getenv("MILVUS_ENABLED", "").strip().lower()
    return value in {"1", "true", "yes", "on"}


def milvus_connect_timeout_seconds() -> float:
    try:
        configured = float(os.getenv("MILVUS_CONNECT_TIMEOUT_SECONDS", "2"))
    except (TypeError, ValueError):
        return 2.0
    if not math.isfinite(configured):
        return 2.0
    return max(0.1, configured)


class MilvusKnowledgeRetriever:
    def __init__(self) -> None:
        self.collection_name = os.getenv("MILVUS_COLLECTION", "qilu_ai_knowledge")
        self.active_alias = self.collection_name + "__active"
        self.alias = "qilu_ai_agent"
        self.dimension = embedding_dimension()
        # 清理参数必须在 alias 写入前 fail-fast，不能在切换成功后才因配置错误中断。
        self.cleanup_grace_seconds = milvus_cleanup_grace_seconds()
        self.retained_collections = milvus_retained_collections()
        self.connected = False
        self.last_error = None
        self.document_count = 0
        self.active_collection_name: Optional[str] = None
        self.candidate_collection_name: Optional[str] = None
        self.candidate_state: Optional[str] = None
        self.last_cleanup_error: Optional[str] = None
        self.last_prepare_status: Dict[str, object] = {"state": "NOT_STARTED"}
        self.last_activate_status: Dict[str, object] = {"state": "NOT_STARTED"}
        self.last_cleanup_status: Dict[str, object] = {"state": "NOT_STARTED"}
        self._known_collections: List[str] = []
        self._loaded_collections: Dict[str, object] = {}
        self._embedding_client = None
        self._pending_cleanup: Dict[str, float] = {}
        self._cleanup_lock = threading.Lock()
        self.last_search_knowledge_version: Optional[str] = None
        self.last_search_index_version: Optional[str] = None
        self.discarded_version_hits = 0
        self.last_version_filter_reason: Optional[str] = None

    def available(self) -> bool:
        return bool(milvus_enabled() and Collection and (local_embeddings_enabled() or (OpenAIEmbeddings and embedding_api_key())))

    def connect(self) -> bool:
        if not self.available():
            self.connected = False
            return False
        if self.connected:
            return True
        try:
            connections.connect(
                alias=self.alias,
                host=os.getenv("MILVUS_HOST", "localhost"),
                port=os.getenv("MILVUS_PORT", "19530"),
                user=os.getenv("MILVUS_USER") or "",
                password=os.getenv("MILVUS_PASSWORD") or "",
                secure=os.getenv("MILVUS_SECURE", "").strip().lower() in {"1", "true", "yes", "on"},
                timeout=milvus_connect_timeout_seconds(),
            )
            self.connected = True
            self.last_error = None
            return True
        except Exception as exc:
            self.connected = False
            self.last_error = type(exc).__name__
            return False

    def prepare_candidate(
        self,
        chunks: List[KnowledgeChunk],
        vectors: List[List[float]],
    ) -> MilvusCandidate:
        """在独立物理 Collection 中完成构建、数量校验和受控探针。"""

        if not chunks:
            raise KnowledgeIndexValidationError("Milvus candidate requires at least one chunk")
        stage = "validate"
        physical_collection = self._physical_collection_name(chunks[0].index_version)
        self.candidate_collection_name = physical_collection
        self.candidate_state = "PREPARING"
        created = False
        start = now()
        try:
            for chunk in chunks:
                validate_milvus_chunk(chunk)
                if (
                    chunk.knowledge_version != chunks[0].knowledge_version
                    or chunk.index_version != chunks[0].index_version
                ):
                    raise KnowledgeIndexValidationError(
                        "Milvus candidate chunks must share one knowledge/index version"
                    )
            vectors = validate_embedding_vectors(vectors, len(chunks), self.dimension)

            stage = "connect"
            if not self.connect():
                raise RuntimeError(self.last_error or "MilvusUnavailable")
            if utility.has_collection(physical_collection, using=self.alias):
                if physical_collection == self.active_collection_name:
                    self.candidate_state = "READY"
                    duration = elapsed_ms(start)
                    self.last_prepare_status = {
                        "state": "READY",
                        "durationMs": round(duration, 2),
                        "errorCode": None,
                    }
                    metrics.record_rag_prepare("milvus", "ready", duration)
                    return MilvusCandidate(
                        physical_collection,
                        chunks[0].knowledge_version,
                        chunks[0].index_version,
                        len(chunks),
                    )
                # 同名候选只能来自先前未完成构建；重建前先清理，绝不触碰 active Collection。
                self._drop_collection(physical_collection)

            stage = "create"
            collection = Collection(
                name=physical_collection,
                schema=self._schema(),
                using=self.alias,
            )
            created = True
            rows = [
                self._row(chunk, vector)
                for chunk, vector in zip(chunks, vectors)
            ]
            stage = "insert"
            collection.insert(rows)
            stage = "flush"
            collection.flush()
            stage = "index"
            collection.create_index(
                "embedding",
                {"index_type": "AUTOINDEX", "metric_type": "COSINE"},
            )
            stage = "load"
            collection.load()
            stage = "ready_validation"
            self._validate_ready_collection(collection, len(rows))
            stage = "probe"
            self._probe_candidate(collection, chunks[0], vectors[0])
            self._loaded_collections[physical_collection] = collection

            self.candidate_state = "READY"
            self.last_error = None
            duration = elapsed_ms(start)
            self.last_prepare_status = {
                "state": "READY",
                "durationMs": round(duration, 2),
                "errorCode": None,
            }
            metrics.record("rag.milvus_candidate_build", duration, success=True)
            metrics.record_rag_prepare("milvus", "ready", duration)
            return MilvusCandidate(
                physical_collection=physical_collection,
                knowledge_version=chunks[0].knowledge_version,
                index_version=chunks[0].index_version,
                entity_count=len(rows),
            )
        except Exception as exc:
            self.candidate_state = "FAILED"
            self.last_error = f"{stage}:{type(exc).__name__}"
            if created and physical_collection != self.active_collection_name:
                self._drop_collection(physical_collection, suppress_error=True)
            duration = elapsed_ms(start)
            self.last_prepare_status = {
                "state": "FAILED",
                "durationMs": round(duration, 2),
                "errorCode": "RAG_MILVUS_PREPARE_FAILED",
                "failureStage": stage,
            }
            metrics.record("rag.milvus_candidate_build", duration, success=False, error=exc)
            metrics.record_rag_prepare("milvus", "failed", duration)
            raise MilvusCandidateError(stage, exc) from exc

    def activate_candidate(self, candidate: MilvusCandidate) -> Optional[str]:
        """以 Milvus alias 作为唯一提交点；失败时删除候选并保持旧 alias。"""

        if candidate.state != "READY":
            raise KnowledgeIndexValidationError("only a READY Milvus candidate can be activated")
        if not self.connect():
            raise MilvusCandidateError("alias", RuntimeError(self.last_error or "MilvusUnavailable"))
        old_collection = self._resolve_active_collection()
        if old_collection == candidate.physical_collection:
            self._mark_active(candidate, old_collection)
            self.last_activate_status = {
                "state": "ACTIVE",
                "durationMs": 0.0,
                "errorCode": None,
            }
            metrics.record_rag_activate("idempotent")
            return old_collection
        start = now()
        try:
            if old_collection:
                utility.alter_alias(
                    candidate.physical_collection,
                    self.active_alias,
                    using=self.alias,
                )
            else:
                utility.create_alias(
                    candidate.physical_collection,
                    self.active_alias,
                    using=self.alias,
                )
            self._mark_active(candidate, old_collection)
            duration = elapsed_ms(start)
            self.last_activate_status = {
                "state": "ACTIVE",
                "durationMs": round(duration, 2),
                "errorCode": None,
            }
            metrics.record("rag.milvus_alias_switch", duration, success=True)
            return old_collection
        except Exception as exc:
            self.last_error = f"alias:{type(exc).__name__}"
            self.candidate_state = "FAILED"
            self._drop_collection(candidate.physical_collection, suppress_error=True)
            duration = elapsed_ms(start)
            self.last_activate_status = {
                "state": "FAILED",
                "durationMs": round(duration, 2),
                "errorCode": "RAG_MILVUS_ACTIVATE_FAILED",
            }
            metrics.record("rag.milvus_alias_switch", duration, success=False, error=exc)
            metrics.record_rag_activate("failed")
            raise MilvusCandidateError("alias", exc) from exc

    def reload(self, chunks: List[KnowledgeChunk], vectors: List[List[float]]) -> bool:
        """保留旧内部入口；新状态机使用 prepare_candidate/activate_candidate 两段式提交。"""

        if not chunks:
            return self.clear()
        try:
            candidate = self.prepare_candidate(chunks, vectors)
            self.activate_candidate(candidate)
            return True
        except Exception:
            return False

    def clear(self) -> bool:
        """立即撤销 active alias，旧物理 Collection 仅在宽限期后清理。"""

        self.last_search_knowledge_version = None
        self.last_search_index_version = None
        self.discarded_version_hits = 0
        self.last_version_filter_reason = None
        if not milvus_enabled():
            self.connected = False
            self.document_count = 0
            self.last_error = None
            return True
        if not self.connect():
            self.last_error = self.last_error or "MilvusUnavailable"
            return False
        start = now()
        try:
            old_collection = self._resolve_active_collection()
            if old_collection:
                utility.drop_alias(self.active_alias, using=self.alias)
                self.active_collection_name = None
                self._schedule_cleanup(old_collection, force_retire=True)
            elif utility.has_collection(self.collection_name, using=self.alias):
                # 兼容升级前的固定 Collection：没有 alias 时可以直接清理该旧查询投影。
                self._drop_collection(self.collection_name)
            self.active_collection_name = None
            self.document_count = 0
            self.last_error = None
            metrics.record("rag.milvus_clear", elapsed_ms(start), success=True)
            return True
        except Exception as exc:
            self.last_error = type(exc).__name__
            metrics.record("rag.milvus_clear", elapsed_ms(start), success=False, error=exc)
            return False

    def search(
        self,
        question: str,
        limit: int,
        knowledge_version: str,
        index_version: Optional[str] = None,
        physical_collection: Optional[str] = None,
    ) -> List[KnowledgeHit]:
        self.last_search_knowledge_version = knowledge_version
        self.last_search_index_version = index_version
        self.discarded_version_hits = 0
        self.last_version_filter_reason = None
        if not self.connect():
            return []
        start = now()
        try:
            # 查询绑定调用方 snapshot 的物理 Collection；alias 切换不会改变进行中的旧版本请求。
            target_collection = physical_collection or self.active_collection_name or self.active_alias
            if physical_collection:
                collection = self._loaded_collections.get(target_collection)
                if collection is None:
                    collection = Collection(target_collection, using=self.alias)
                    self._loaded_collections[target_collection] = collection
            elif not utility.has_collection(target_collection, using=self.alias):
                self.document_count = 0
                self.last_error = None
                metrics.record("rag.milvus_search", elapsed_ms(start), success=True)
                return []
            else:
                collection = Collection(target_collection, using=self.alias)
            # candidate 激活和 v3 恢复均已完成 load；查询期重复 load 会放大 Milvus 尾延迟。
            # 查询向量与建库向量执行同一维度和有限数值校验，失败时直接降级到本地检索。
            vector = validate_embedding_vectors(
                [self._embeddings().embed_query(question)],
                expected_count=1,
                expected_dimension=self.dimension,
            )[0]
            results = collection.search(
                data=[vector],
                anns_field="embedding",
                param={"metric_type": "COSINE"},
                limit=max(limit * 3, limit),
                output_fields=[
                    "chunkId", "knowledgeId", "chunkIndex", "title", "category", "source",
                    "content", "knowledgeVersion", "indexVersion", "chunkHash",
                ],
            )
            hits = []
            missing_version_hits = 0
            mismatched_version_hits = 0
            for hit in results[0]:
                entity = hit.entity
                hit_knowledge_version = entity.get("knowledgeVersion")
                if not isinstance(hit_knowledge_version, str) or not hit_knowledge_version:
                    missing_version_hits += 1
                    continue
                if hit_knowledge_version != knowledge_version:
                    mismatched_version_hits += 1
                    continue
                hit_index_version = entity.get("indexVersion")
                if index_version is not None and hit_index_version != index_version:
                    mismatched_version_hits += 1
                    continue
                content = entity.get("content")
                metadata = {
                    "chunkId": entity.get("chunkId"),
                    "knowledgeId": entity.get("knowledgeId"),
                    "chunkIndex": entity.get("chunkIndex"),
                    "title": entity.get("title"),
                    "category": entity.get("category"),
                    "source": entity.get("source"),
                    "knowledgeVersion": hit_knowledge_version,
                    "indexVersion": hit_index_version,
                    "chunkHash": entity.get("chunkHash"),
                }
                hits.append(KnowledgeHit(content=content, metadata=metadata, score=float(hit.score), retriever="milvus"))
                if len(hits) >= limit:
                    break
            self.discarded_version_hits = missing_version_hits + mismatched_version_hits
            if missing_version_hits and mismatched_version_hits:
                self.last_version_filter_reason = "knowledge_version_missing_or_mismatch"
            elif missing_version_hits:
                self.last_version_filter_reason = "knowledge_version_missing"
            elif mismatched_version_hits:
                self.last_version_filter_reason = "knowledge_version_mismatch"
            # 实体数已在 candidate 探针和激活阶段校验并记录；查询期再次读取会触发远程统计请求，
            # 既不提升当前 snapshot 的正确性，又会给每次检索增加固定的 Milvus 往返延迟。
            self.last_error = None
            metrics.record("rag.milvus_search", elapsed_ms(start), success=True)
            return hits
        except Exception as exc:
            self.last_error = type(exc).__name__
            metrics.record("rag.milvus_search", elapsed_ms(start), success=False, error=exc)
            return []

    def status(self) -> Dict[str, object]:
        self.cleanup_superseded()
        return {
            "milvusEnabled": milvus_enabled(),
            "milvusDependencyAvailable": bool(Collection),
            "milvusConnected": self.connected,
            "milvusCollectionSummary": _identifier_summary(self.collection_name),
            "milvusActiveAliasSummary": _identifier_summary(self.active_alias),
            "milvusActiveCollectionSummary": _identifier_summary(self.active_collection_name),
            "milvusCandidateCollectionSummary": _identifier_summary(self.candidate_collection_name),
            "milvusCandidateState": self.candidate_state,
            "milvusDocumentCount": self.document_count,
            "milvusLastError": self.last_error,
            "milvusPendingCleanupCount": len(self._pending_cleanup),
            "milvusLastCleanupError": self.last_cleanup_error,
            "milvusLastPrepare": dict(self.last_prepare_status),
            "milvusLastActivate": dict(self.last_activate_status),
            "milvusLastCleanup": dict(self.last_cleanup_status),
            "milvusLastSearchKnowledgeVersion": self.last_search_knowledge_version,
            "milvusLastSearchIndexVersion": self.last_search_index_version,
            "milvusDiscardedVersionHits": self.discarded_version_hits,
            "milvusLastVersionFilterReason": self.last_version_filter_reason,
        }

    def cleanup_superseded(self, force: bool = False) -> None:
        """清理超过保留数且已过宽限期的旧 Collection；失败只记录，不回滚 active。"""

        current_time = time.monotonic()
        with self._cleanup_lock:
            due = [
                name
                for name, deadline in self._pending_cleanup.items()
                if force or deadline <= current_time
            ]
        for collection_name in due:
            if collection_name == self.active_collection_name:
                continue
            start = now()
            try:
                self._drop_collection(collection_name)
                with self._cleanup_lock:
                    self._pending_cleanup.pop(collection_name, None)
                    self._known_collections = [
                        name for name in self._known_collections if name != collection_name
                    ]
                self.last_cleanup_error = None
                duration = elapsed_ms(start)
                self.last_cleanup_status = {
                    "state": "SUCCESS",
                    "durationMs": round(duration, 2),
                    "errorCode": None,
                }
                metrics.record_rag_cleanup("success")
            except Exception as exc:
                self.last_cleanup_error = type(exc).__name__
                duration = elapsed_ms(start)
                self.last_cleanup_status = {
                    "state": "FAILED",
                    "durationMs": round(duration, 2),
                    "errorCode": "RAG_COLLECTION_CLEANUP_FAILED",
                }
                metrics.record_rag_cleanup("failed")

    def _mark_active(
        self,
        candidate: MilvusCandidate,
        old_collection: Optional[str],
    ) -> None:
        self.active_collection_name = candidate.physical_collection
        self.candidate_collection_name = candidate.physical_collection
        self.candidate_state = "ACTIVE"
        self.document_count = candidate.entity_count
        self.last_error = None
        self.last_search_knowledge_version = candidate.knowledge_version
        self.last_search_index_version = candidate.index_version
        self.discarded_version_hits = 0
        self.last_version_filter_reason = None
        ordered = [candidate.physical_collection]
        ordered.extend(name for name in self._known_collections if name != candidate.physical_collection)
        if old_collection and old_collection not in ordered:
            ordered.append(old_collection)
        self._known_collections = ordered
        for collection_name in self._known_collections[self.retained_collections:]:
            self._schedule_cleanup(collection_name)

    def _schedule_cleanup(self, collection_name: str, force_retire: bool = False) -> None:
        if not collection_name:
            return
        deadline = time.monotonic() + self.cleanup_grace_seconds
        with self._cleanup_lock:
            self._pending_cleanup[collection_name] = deadline
            if force_retire and collection_name not in self._known_collections:
                self._known_collections.append(collection_name)
        self.last_cleanup_status = {
            "state": "PENDING",
            "durationMs": 0.0,
            "errorCode": None,
        }
        metrics.record_rag_cleanup("pending")
        timer = threading.Timer(
            max(0.0, deadline - time.monotonic()),
            self.cleanup_superseded,
        )
        timer.daemon = True
        try:
            timer.start()
        except Exception as exc:
            # 调度失败只影响延迟清理，pending 状态仍可由 status 或后续重载重试。
            self.last_cleanup_error = type(exc).__name__

    def _resolve_active_collection(self) -> Optional[str]:
        if self.active_collection_name:
            return self.active_collection_name
        try:
            for collection_name in utility.list_collections(using=self.alias):
                aliases = utility.list_aliases(collection_name, using=self.alias)
                if self.active_alias in aliases:
                    self.active_collection_name = collection_name
                    return collection_name
        except Exception:
            return None
        return None

    def _physical_collection_name(self, index_version: str) -> str:
        match = re.fullmatch(r"sha256:([0-9a-f]{64})", index_version or "")
        token = match.group(1)[:16] if match else hashlib.sha256(
            (index_version or "").encode("utf-8")
        ).hexdigest()[:16]
        return f"{self.collection_name}__{token}"

    def _validate_ready_collection(self, collection, expected_count: int) -> None:
        if type(collection.num_entities) is not int or collection.num_entities != expected_count:
            raise KnowledgeIndexValidationError(
                f"Milvus entity count mismatch: expected={expected_count}, actual={collection.num_entities}"
            )
        schema_fields = {field.name: field for field in collection.schema.fields}
        required_fields = {
            "chunkId", "knowledgeId", "chunkIndex", "content", "knowledgeVersion",
            "indexVersion", "chunkHash", "embedding",
        }
        if not required_fields.issubset(schema_fields):
            raise KnowledgeIndexValidationError("Milvus candidate schema fields are incomplete")
        dimension = schema_fields["embedding"].params.get("dim")
        if int(dimension) != self.dimension:
            raise KnowledgeIndexValidationError(
                f"Milvus schema dimension mismatch: expected={self.dimension}, actual={dimension}"
            )
        if not collection.has_index():
            raise KnowledgeIndexValidationError("Milvus candidate embedding index is missing")

    def _probe_candidate(
        self,
        collection,
        expected_chunk: KnowledgeChunk,
        probe_vector: List[float],
    ) -> None:
        results = collection.search(
            data=[probe_vector],
            anns_field="embedding",
            param={"metric_type": "COSINE"},
            limit=1,
            output_fields=["chunkId", "knowledgeVersion", "indexVersion"],
        )
        if not results or not results[0]:
            raise KnowledgeIndexValidationError("Milvus candidate probe returned no hits")
        entity = results[0][0].entity
        if (
            entity.get("chunkId") != expected_chunk.chunk_id
            or entity.get("knowledgeVersion") != expected_chunk.knowledge_version
            or entity.get("indexVersion") != expected_chunk.index_version
        ):
            raise KnowledgeIndexValidationError("Milvus candidate probe returned mismatched metadata")

    def _drop_collection(self, collection_name: str, suppress_error: bool = False) -> None:
        try:
            if utility.has_collection(collection_name, using=self.alias):
                utility.drop_collection(collection_name, using=self.alias)
            self._loaded_collections.pop(collection_name, None)
        except Exception:
            if not suppress_error:
                raise

    def _schema(self):
        fields = [
            FieldSchema(name="chunkId", dtype=DataType.VARCHAR, is_primary=True, max_length=80),
            FieldSchema(name="knowledgeId", dtype=DataType.INT64),
            FieldSchema(name="title", dtype=DataType.VARCHAR, max_length=512),
            FieldSchema(name="category", dtype=DataType.VARCHAR, max_length=128),
            FieldSchema(name="status", dtype=DataType.INT64),
            FieldSchema(name="source", dtype=DataType.VARCHAR, max_length=512),
            FieldSchema(name="chunkIndex", dtype=DataType.INT64),
            FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=4096),
            FieldSchema(name="knowledgeVersion", dtype=DataType.VARCHAR, max_length=128),
            FieldSchema(name="indexVersion", dtype=DataType.VARCHAR, max_length=128),
            FieldSchema(name="chunkHash", dtype=DataType.VARCHAR, max_length=80),
            FieldSchema(name="updatedAt", dtype=DataType.VARCHAR, max_length=64),
            FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=self.dimension),
        ]
        return CollectionSchema(fields=fields, description="Qilu AI knowledge chunks")

    def _row(self, chunk: KnowledgeChunk, vector: List[float]) -> Dict[str, object]:
        return {
            "chunkId": chunk.chunk_id,
            "knowledgeId": chunk.knowledge_id,
            "title": chunk.title,
            "category": chunk.category,
            "status": 1,
            "source": chunk.source,
            "chunkIndex": chunk.chunk_index,
            "content": chunk.chunk_content,
            "knowledgeVersion": chunk.knowledge_version,
            "indexVersion": chunk.index_version,
            "chunkHash": chunk.chunk_hash,
            "updatedAt": datetime.now(timezone.utc).isoformat(),
            "embedding": vector,
        }

    def _embeddings(self):
        # OpenAI 兼容客户端包含连接池和 HTTP 会话，按检索器实例复用可避免每次查询重新建连。
        if self._embedding_client is None:
            self._embedding_client = build_embeddings()
        return self._embedding_client


class CampusKnowledgeRetriever:
    def __init__(
        self,
        texts: Optional[List[str]] = None,
        persist_dir: Optional[str] = None,
        rebuild_on_start: bool = False,
        use_default_knowledge: bool = False,
        knowledge_source: str = "uninitialized",
    ):
        self._reload_lock = threading.Lock()
        self._snapshot_lock = threading.RLock()
        self._active_snapshot: Optional[KnowledgeSnapshot] = None
        self.reload_state = "PREPARING"
        self.candidate_knowledge_version: Optional[str] = None
        self.candidate_index_version: Optional[str] = None
        self.required_backends = required_rag_backends()
        self.faiss_search_threads = configure_faiss_search_threads()
        self.last_reload_result: Optional[KnowledgeReloadResult] = None
        self.last_prepare_status: Dict[str, object] = {"state": "NOT_STARTED"}
        self.last_activate_status: Dict[str, object] = {"state": "NOT_STARTED"}
        self.cache_migration_error_code: Optional[str] = None
        self.use_default_knowledge = use_default_knowledge
        self.rebuild_on_start = rebuild_on_start
        self.persist_dir = Path(persist_dir).resolve() if persist_dir else None
        self.vectorstore = None
        self._embedding_client = None
        self.milvus = MilvusKnowledgeRetriever()
        # Embedding/Milvus 开关属于本实例的索引身份，查询期复用冻结值，避免亚毫秒热路径反复读取环境变量。
        self.local_embedding_mode = local_embeddings_enabled()
        self.milvus_configured = self.milvus.available()
        self.chunk_config = chunk_config_from_env()
        self.embedding_descriptor = embedding_descriptor()
        self.lexical_config: LexicalConfig = lexical_config_from_env()
        self.lexical_descriptor = self.lexical_config.descriptor()
        self.minimum_retrieval_scores = retrieval_minimum_scores()
        self.vector_query_rewrite = os.getenv("RAG_VECTOR_QUERY_REWRITE", "true").strip().lower() in {
            "1", "true", "yes", "on",
        }
        self.vector_query_prefix = os.getenv("RAG_VECTOR_QUERY_PREFIX", "")
        self.vector_candidate_k = retrieval_candidate_limit("RAG_VECTOR_CANDIDATE_K", 12, 1)
        self.lexical_candidate_k = retrieval_candidate_limit("RAG_LEXICAL_CANDIDATE_K", 12, 1)
        self.rrf_k = retrieval_rrf_k()
        self.max_chunks_per_knowledge = retrieval_max_chunks_per_knowledge()
        self.context_character_budget = retrieval_context_character_budget()
        self.index_fingerprints: Optional[IndexFingerprints] = None
        self.index_version = "uninitialized"
        self.chunks: List[KnowledgeChunk] = []
        self.bm25_index: Optional[BM25LexicalIndex] = None
        self.persisted_vector_compatible = False
        self.cache_validation_status = "not_configured" if not self.persist_dir else "missing"
        self.cache_validation_reason = "persist_directory_not_configured" if not self.persist_dir else "manifest_missing"

        initial_source = knowledge_source if knowledge_source in ACTIVE_KNOWLEDGE_SOURCES else "uninitialized"
        self.knowledge_source = initial_source
        self.documents: List[KnowledgeDocument] = []
        self.texts: List[str] = []
        self._set_active_documents(self._normalize_documents(texts, initial_source), initial_source)
        self.knowledge_version = self.knowledge_source if self.documents else "uninitialized"
        self.knowledge_updated_at: Optional[str] = None
        self._refresh_active_chunks()

        if self.persist_dir:
            try:
                self.persist_dir.mkdir(parents=True, exist_ok=True)
            except OSError:
                self.cache_validation_status = "invalid"
                self.cache_validation_reason = "persist_directory_unavailable"
                self.persist_dir = None

        restored = self._restore_persisted_cache()
        if not restored:
            self._load_or_build_vectorstore()
            self._publish_current_fields_as_snapshot()
        elif self._active_snapshot is None:
            self._publish_current_fields_as_snapshot()
        if self.reload_state != "DEGRADED":
            self.reload_state = "ACTIVE" if self.is_initialized() else "READY"

    def reload(
        self,
        texts: List[str],
        knowledge_version: Optional[str] = None,
        knowledge_source: str = "ai_knowledge",
    ) -> KnowledgeReloadResult:
        return self.reload_documents(
            self._normalize_documents(texts, knowledge_source),
            knowledge_version=knowledge_version,
            knowledge_source=knowledge_source,
        )

    def reload_documents(
        self,
        documents: List[KnowledgeDocument],
        knowledge_version: Optional[str] = None,
        knowledge_source: str = "ai_knowledge",
    ) -> KnowledgeReloadResult:
        # 单写者锁覆盖完整 prepare/validate/activate 序列；查询只短暂读取 snapshot 指针，不参与该锁。
        with self._reload_lock:
            reload_started = now()
            self.reload_state = "PREPARING"
            self.last_reload_result = None
            try:
                result = self._reload_documents_locked(
                    documents,
                    knowledge_version,
                    knowledge_source,
                )
                self.last_prepare_status = {
                    "state": "READY",
                    "durationMs": round(elapsed_ms(reload_started), 2),
                    "errorCode": None,
                }
                return result
            except Exception as exc:
                self.reload_state = "FAILED"
                error_code = _stable_rag_error_code(exc)
                self.last_prepare_status = {
                    "state": "FAILED",
                    "durationMs": round(elapsed_ms(reload_started), 2),
                    "errorCode": error_code,
                }
                if self.last_reload_result is None or self.last_reload_result.success:
                    snapshot = self._capture_active_snapshot()
                    self.last_reload_result = KnowledgeReloadResult(
                        success=False,
                        activated=False,
                        degraded=False,
                        idempotent=False,
                        knowledge_version=self.candidate_knowledge_version or snapshot.knowledge_version,
                        index_version=self.candidate_index_version or snapshot.index_version,
                        backend_states=snapshot.backend_states,
                        error_code=error_code,
                        message=_safe_reload_message(error_code),
                    )
                raise

    def _reload_documents_locked(
        self,
        documents: List[KnowledgeDocument],
        knowledge_version: Optional[str],
        knowledge_source: str,
    ) -> KnowledgeReloadResult:
        if knowledge_source == "ai_knowledge":
            for position, document in enumerate(documents):
                if not document.title or not document.title.strip() or not document.content or not document.content.strip():
                    raise KnowledgeIndexValidationError(
                        f"document[{position}] title and content must not be empty"
                    )
        candidate_documents = self._normalize_active_documents(documents)
        if knowledge_source not in ACTIVE_KNOWLEDGE_SOURCES:
            candidate_documents = []
        candidate_version = knowledge_version or self._build_local_version(candidate_documents)
        candidate_fingerprints = build_index_fingerprints(
            candidate_version,
            self.embedding_descriptor,
            self.chunk_config,
            self.lexical_descriptor,
        )
        self.candidate_knowledge_version = candidate_version
        self.candidate_index_version = candidate_fingerprints.index_version

        if not candidate_documents:
            return self._activate_empty_candidate(
                candidate_version,
                candidate_fingerprints,
                knowledge_source,
            )

        candidate_fingerprints, candidate_chunks = self._build_chunk_projection(
            candidate_documents,
            candidate_version,
            knowledge_source,
        )
        active_snapshot = self._capture_active_snapshot()
        if candidate_fingerprints.index_version == active_snapshot.index_version:
            if (
                tuple(candidate_documents) != active_snapshot.documents
                or tuple(candidate_chunks) != active_snapshot.chunks
                or knowledge_source != active_snapshot.knowledge_source
            ):
                raise KnowledgeIndexValidationError(
                    "the same indexVersion cannot describe different active chunks"
                )
            # 幂等判断发生在 Embedding 和 Milvus candidate 创建之前，重试不会产生额外外部调用。
            result = KnowledgeReloadResult(
                success=True,
                activated=False,
                degraded="FAILED" in active_snapshot.backend_states.values(),
                idempotent=True,
                knowledge_version=active_snapshot.knowledge_version,
                index_version=active_snapshot.index_version,
                backend_states=active_snapshot.backend_states,
                candidate_collection=active_snapshot.milvus_physical_collection,
                message="identical indexVersion already active",
            )
            self.reload_state = "ACTIVE"
            self.last_reload_result = result
            metrics.record_rag_activate("idempotent")
            return result

        backend_states: Dict[str, str] = {"bm25": "PREPARING", "faiss": "SKIPPED", "milvus": "SKIPPED"}
        candidate_bm25_index: Optional[BM25LexicalIndex] = None
        backend_started = now()
        try:
            candidate_bm25_index = self._build_bm25_index(candidate_chunks, candidate_documents)
            backend_states["bm25"] = "READY"
            metrics.record_rag_prepare("bm25", "ready", elapsed_ms(backend_started))
        except Exception as exc:
            backend_states["bm25"] = "FAILED"
            metrics.record_rag_prepare("bm25", "failed", elapsed_ms(backend_started))
            self._reject_required_backends(
                backend_states,
                candidate_version,
                candidate_fingerprints.index_version,
                exc,
            )

        faiss_available = vector_dependencies_enabled()
        milvus_requested = knowledge_source == "ai_knowledge" and (
            milvus_enabled() or "milvus" in self.required_backends
        )
        milvus_available = milvus_requested and self.milvus.available()
        if faiss_available or "faiss" in self.required_backends:
            backend_states["faiss"] = "PREPARING" if faiss_available else "FAILED"
        if milvus_requested:
            backend_states["milvus"] = "PREPARING" if milvus_available else "FAILED"

        embeddings = None
        vectors: List[List[float]] = []
        if faiss_available or milvus_available:
            try:
                # 同一批向量只生成一次，同时供候选 FAISS 和 Milvus 使用。
                embeddings = self._embeddings()
                vectors = validate_embedding_vectors(
                    embeddings.embed_documents([chunk.embedding_text for chunk in candidate_chunks]),
                    expected_count=len(candidate_chunks),
                    expected_dimension=self.embedding_descriptor.dimension,
                )
            except Exception as exc:
                if faiss_available:
                    backend_states["faiss"] = "FAILED"
                if milvus_available:
                    backend_states["milvus"] = "FAILED"
                self._reject_required_backends(
                    backend_states,
                    candidate_version,
                    candidate_fingerprints.index_version,
                    exc,
                )
                # 向量批次形状或数值不可信时整个候选失败，不能把同一次同步悄悄降级为词法-only。
                raise

        candidate_vectorstore = None
        if backend_states["faiss"] == "PREPARING":
            backend_started = now()
            try:
                candidate_vectorstore = self._build_vectorstore(candidate_chunks, vectors, embeddings)
                backend_states["faiss"] = "READY" if candidate_vectorstore is not None else "FAILED"
                metrics.record_rag_prepare(
                    "faiss",
                    "ready" if candidate_vectorstore is not None else "failed",
                    elapsed_ms(backend_started),
                )
            except Exception as exc:
                backend_states["faiss"] = "FAILED"
                metrics.record_rag_prepare("faiss", "failed", elapsed_ms(backend_started))
                self._reject_required_backends(
                    backend_states,
                    candidate_version,
                    candidate_fingerprints.index_version,
                    exc,
                )

        milvus_candidate: Optional[MilvusCandidate] = None
        if backend_states["milvus"] == "PREPARING":
            try:
                milvus_candidate = self.milvus.prepare_candidate(candidate_chunks, vectors)
                backend_states["milvus"] = "READY"
            except Exception as exc:
                backend_states["milvus"] = "FAILED"
                self._reject_required_backends(
                    backend_states,
                    candidate_version,
                    candidate_fingerprints.index_version,
                    exc,
                )

        self._reject_required_backends(
            backend_states,
            candidate_version,
            candidate_fingerprints.index_version,
        )

        if milvus_candidate is not None:
            try:
                self.milvus.activate_candidate(milvus_candidate)
            except Exception as exc:
                backend_states["milvus"] = "FAILED"
                milvus_candidate = None
                self._reject_required_backends(
                    backend_states,
                    candidate_version,
                    candidate_fingerprints.index_version,
                    exc,
                )

        candidate_snapshot = self._new_snapshot(
            documents=candidate_documents,
            chunks=candidate_chunks,
            fingerprints=candidate_fingerprints,
            vectorstore=candidate_vectorstore,
            bm25_index=candidate_bm25_index,
            milvus_physical_collection=(
                milvus_candidate.physical_collection if milvus_candidate else None
            ),
            knowledge_version=candidate_version,
            knowledge_source=knowledge_source,
            knowledge_updated_at=_utc_now(),
            backend_states=backend_states,
        )
        activate_started = now()
        self._publish_snapshot(candidate_snapshot)

        degraded = "FAILED" in backend_states.values()
        cache_error_code = None
        if self.persist_dir and knowledge_source == "ai_knowledge":
            try:
                self._persist_formal_cache()
            except Exception:
                # 在线 active 已经原子发布；缓存提交失败只进入明确降级，不能回滚可用查询快照。
                degraded = True
                cache_error_code = "RAG_CACHE_WRITE_FAILED"

        self.reload_state = "DEGRADED" if degraded else "ACTIVE"
        self.last_activate_status = {
            "state": self.reload_state,
            "durationMs": round(elapsed_ms(activate_started), 2),
            "errorCode": cache_error_code,
        }
        metrics.record_rag_activate("degraded" if degraded else "active")
        if degraded:
            for backend, state in backend_states.items():
                if state == "FAILED":
                    metrics.record_rag_degraded(backend, "optional_backend_failed")
        result = KnowledgeReloadResult(
            success=True,
            activated=True,
            degraded=degraded,
            idempotent=False,
            knowledge_version=candidate_version,
            index_version=candidate_fingerprints.index_version,
            backend_states=_immutable_backend_states(backend_states),
            candidate_collection=(
                milvus_candidate.physical_collection if milvus_candidate else None
            ),
            error_code=cache_error_code,
            message="candidate validated and activated",
        )
        self.last_reload_result = result
        return result

    def retrieve(self, question: str, limit: int = 3) -> str:
        return "\n".join(hit.content for hit in self.retrieve_documents(question, limit))

    def retrieve_documents(
        self,
        question: str,
        limit: int = 3,
        topic_keywords: Tuple[str, ...] = (),
        trace_span=None,
    ) -> List[KnowledgeHit]:
        # 只在请求入口读取一次 active 指针，后续向量、词法、过滤和融合都绑定同一 snapshot。
        snapshot = self._capture_active_snapshot()
        if trace_span is not None:
            trace_span.set_attribute("ai.rag.knowledge_version", snapshot.knowledge_version)
            trace_span.set_attribute("ai.rag.index_version", snapshot.index_version)
            trace_span.set_attribute("ai.rag.chunk_count", len(snapshot.chunks))
        if not snapshot.documents or snapshot.knowledge_source not in ACTIVE_KNOWLEDGE_SOURCES:
            metrics.record_rag_no_source("knowledge_uninitialized")
            _complete_rag_trace(trace_span, fallback_reason="knowledge_uninitialized")
            return []
        if limit <= 0:
            metrics.record_rag_no_source("invalid_limit")
            _complete_rag_trace(trace_span, fallback_reason="invalid_limit")
            return []
        # local-hash 的改写词全部来自原问题的同一分词集合；真实语义模型可关闭 n-gram
        # 扩写，避免把自然问句改成关键词堆叠后破坏模型原本的句义表示。
        local_embedding_mode = getattr(self, "local_embedding_mode", None)
        if local_embedding_mode is None:
            # 兼容少量历史测试通过 __new__ 构造的最小 Retriever。
            local_embedding_mode = local_embeddings_enabled()
        rewritten_question = (
            rewrite_query(question)
            if getattr(self, "vector_query_rewrite", True) and not local_embedding_mode
            else question
        )
        vector_question = getattr(self, "vector_query_prefix", "") + rewritten_question
        configured_vector_k = getattr(self, "vector_candidate_k", None)
        if configured_vector_k is None:
            configured_vector_k = retrieval_candidate_limit("RAG_VECTOR_CANDIDATE_K", 12, 1)
        configured_lexical_k = getattr(self, "lexical_candidate_k", None)
        if configured_lexical_k is None:
            configured_lexical_k = retrieval_candidate_limit("RAG_LEXICAL_CANDIDATE_K", 12, 1)
        vector_candidate_limit = max(limit, configured_vector_k)
        lexical_candidate_limit = max(limit, configured_lexical_k)
        rankings: List[Tuple[str, List[KnowledgeHit]]] = []
        vector_candidate_count = 0
        lexical_candidate_count = 0
        degraded = False
        fallback_reason: Optional[str] = None
        vector_backend = "none"

        # 正常路径只允许一个向量排名参与融合；Milvus 连接失败时才切换到同 chunks 的 FAISS 备份。
        vector_backend_selected = False
        milvus_configured = getattr(self, "milvus_configured", None)
        if milvus_configured is None:
            milvus_configured = self.milvus.available()
        if (
            snapshot.knowledge_source == "ai_knowledge"
            and snapshot.backend_states.get("milvus") != "FAILED"
            and milvus_configured
        ):
            search_started = now()
            if snapshot.milvus_physical_collection:
                raw_milvus_hits = self.milvus.search(
                    vector_question,
                    vector_candidate_limit,
                    snapshot.knowledge_version,
                    snapshot.index_version,
                    physical_collection=snapshot.milvus_physical_collection,
                )
            else:
                # 兼容阶段 D 前恢复的 v2 documents；没有物理名时才通过 active alias 查询。
                raw_milvus_hits = self.milvus.search(
                    vector_question,
                    vector_candidate_limit,
                    snapshot.knowledge_version,
                    snapshot.index_version,
                )
            milvus_hits = normalize_hits(raw_milvus_hits)
            filtered_milvus_hits = self._filter_active_candidates(
                milvus_hits,
                topic_keywords,
                snapshot,
            )
            metrics.record_rag_retrieval(
                "milvus",
                len(milvus_hits),
                len(filtered_milvus_hits),
                elapsed_ms(search_started),
            )
            if self.milvus.connected is True and self.milvus.last_error is None:
                vector_backend_selected = True
                vector_backend = "milvus"
                vector_candidate_count = len(milvus_hits)
                rankings.append(("milvus", filtered_milvus_hits))
            else:
                degraded = True
                fallback_reason = "milvus_search_failed"
                metrics.record_rag_degraded("milvus", "search_failed")
        elif snapshot.knowledge_source == "ai_knowledge" and snapshot.backend_states.get("milvus") != "SKIPPED":
            degraded = True
            fallback_reason = "milvus_unavailable"
            metrics.record_rag_degraded("milvus", "unavailable")

        if not vector_backend_selected and snapshot.faiss_store is not None:
            start = now()
            try:
                docs = snapshot.faiss_store.similarity_search_with_score(
                    vector_question,
                    k=vector_candidate_limit,
                )
                metrics.record("rag.vector_search", elapsed_ms(start), success=True, fallback=milvus_configured)
                minimum_faiss_score = getattr(self, "minimum_retrieval_scores", {}).get("faiss", 0.0)
                faiss_hits = []
                for document, score in docs:
                    normalized_score = _faiss_similarity(score)
                    if (
                        normalized_score is None
                        or normalized_score <= 0.0
                        or normalized_score < minimum_faiss_score
                    ):
                        continue
                    faiss_hits.append(KnowledgeHit(
                        content=document.page_content,
                        metadata=document.metadata or {},
                        score=float(score) if score is not None else None,
                        retriever="faiss",
                        normalized_score=normalized_score,
                    ))
                filtered_faiss_hits = self._filter_active_candidates(
                    faiss_hits,
                    topic_keywords,
                    snapshot,
                )
                duration = elapsed_ms(start)
                metrics.record_rag_retrieval(
                    "faiss",
                    len(docs),
                    len(filtered_faiss_hits),
                    duration,
                )
                vector_backend_selected = True
                vector_backend = "faiss"
                vector_candidate_count = len(docs)
                rankings.append(("faiss", filtered_faiss_hits))
            except Exception as exc:
                metrics.record("rag.vector_search", elapsed_ms(start), success=False, error=exc)
                degraded = True
                fallback_reason = "faiss_search_failed"
                metrics.record_rag_degraded("faiss", "search_failed")

        if (
            snapshot.backend_states.get("bm25") != "FAILED"
            and (snapshot.bm25_index is not None or snapshot.chunks)
        ):
            start = now()
            lexical_hits = self._bm25_hits(question, lexical_candidate_limit, snapshot)
            filtered_lexical_hits = self._filter_active_candidates(
                lexical_hits,
                topic_keywords,
                snapshot,
            )
            duration = elapsed_ms(start)
            metrics.record("rag.bm25_search", duration, success=True, fallback=not rankings)
            metrics.record_rag_retrieval(
                "bm25",
                len(lexical_hits),
                len(filtered_lexical_hits),
                duration,
            )
            lexical_candidate_count = len(lexical_hits)
            rankings.append(("bm25", filtered_lexical_hits))

        configured_rrf_k = getattr(self, "rrf_k", None)
        if configured_rrf_k is None:
            configured_rrf_k = retrieval_rrf_k()
        configured_max_chunks = getattr(self, "max_chunks_per_knowledge", None)
        if configured_max_chunks is None:
            configured_max_chunks = retrieval_max_chunks_per_knowledge()
        configured_context_budget = getattr(self, "context_character_budget", None)
        if configured_context_budget is None:
            configured_context_budget = retrieval_context_character_budget()
        fused = reciprocal_rank_fusion(rankings, configured_rrf_k)
        final_hits = finalize_fused_hits(
            fused,
            limit=limit,
            max_chunks_per_knowledge=configured_max_chunks,
            context_character_budget=configured_context_budget,
        )
        metrics.record("rag.fusion", 0.0, success=True, fallback=not vector_backend_selected)
        metrics.record_rag_fusion(vector_backend)
        if not final_hits:
            fallback_reason = fallback_reason or "no_usable_candidates"
            metrics.record_rag_no_source("no_usable_candidates")
        _complete_rag_trace(
            trace_span,
            vector_backend=vector_backend,
            vector_candidate_count=vector_candidate_count,
            lexical_candidate_count=lexical_candidate_count,
            filtered_candidate_count=sum(len(hits) for _, hits in rankings),
            final_hit_count=len(final_hits),
            degraded=degraded,
            fallback_reason=fallback_reason,
        )
        return final_hits

    def status(self) -> dict:
        snapshot = self._capture_active_snapshot()
        manifest = self._read_manifest()
        vector_index_present = bool(manifest.get("faissIndexPresent", manifest.get("vectorIndexPresent")))
        failed_backends = sorted(
            backend for backend, state in snapshot.backend_states.items() if state == "FAILED"
        )
        last_result = self.last_reload_result
        status = {
            "vectorIndexEnabled": bool(snapshot.faiss_store is not None),
            "vectorIndexPersistent": bool(
                self.cache_validation_status == "valid"
                and snapshot.knowledge_source == "ai_knowledge"
                and vector_index_present
                and self.persisted_vector_compatible
                and self._index_exists()
            ),
            "vectorIndexConfigured": self.persist_dir is not None,
            "knowledgeDocumentCount": len(snapshot.documents) if self.is_initialized() else 0,
            "knowledgeChunkCount": len(snapshot.chunks) if self.is_initialized() else 0,
            "bm25IndexEnabled": bool(snapshot.bm25_index is not None),
            "knowledgeVersion": snapshot.knowledge_version,
            "indexVersion": snapshot.index_version,
            "activeKnowledgeVersion": snapshot.knowledge_version,
            "activeIndexVersion": snapshot.index_version,
            "candidateKnowledgeVersion": self.candidate_knowledge_version,
            "candidateIndexVersion": self.candidate_index_version,
            "embeddingFingerprint": (
                snapshot.fingerprints.embedding_fingerprint if snapshot.fingerprints else None
            ),
            "chunkFingerprint": snapshot.fingerprints.chunk_fingerprint if snapshot.fingerprints else None,
            "lexicalFingerprint": snapshot.fingerprints.lexical_fingerprint if snapshot.fingerprints else None,
            "indexSchemaVersion": snapshot.fingerprints.index_schema_version if snapshot.fingerprints else None,
            "embeddingProvider": self.embedding_descriptor.provider,
            "embeddingModel": self.embedding_descriptor.model,
            "embeddingDimension": self.embedding_descriptor.dimension,
            "embeddingAlgorithmRevision": self.embedding_descriptor.algorithm_revision,
            "embeddingDeploymentRevision": self.embedding_descriptor.deployment_revision,
            "chunkSize": self.chunk_config.size,
            "chunkOverlap": self.chunk_config.overlap,
            "chunkUnit": self.chunk_config.unit,
            "chunkAlgorithm": self.chunk_config.algorithm,
            "lexicalImplementation": self.lexical_descriptor.implementation,
            "lexicalRevision": self.lexical_descriptor.tokenizer_revision,
            "knowledgeUpdatedAt": snapshot.knowledge_updated_at,
            "knowledgeSource": snapshot.knowledge_source,
            "knowledgeInitialized": self.is_initialized(),
            "reloadState": self.reload_state,
            "requiredBackends": sorted(self.required_backends),
            "backendStates": dict(snapshot.backend_states),
            "degraded": bool(failed_backends) or self.reload_state == "DEGRADED",
            "degradedBackends": failed_backends,
            "snapshotCreatedAt": snapshot.created_at,
            "snapshotMilvusCollectionSummary": _identifier_summary(snapshot.milvus_physical_collection),
            "lastPrepare": dict(self.last_prepare_status),
            "lastActivate": dict(self.last_activate_status),
            "lastReloadErrorCode": last_result.error_code if last_result else None,
            "persistedDocumentCount": manifest.get("documentCount"),
            "persistedChunkCount": manifest.get("chunkCount"),
            "persistedKnowledgeVersion": manifest.get("knowledgeVersion"),
            "persistedIndexVersion": manifest.get("indexVersion"),
            "persistedEmbeddingModel": manifest.get("embeddingModel"),
            "manifestSchemaVersion": manifest.get("manifestSchemaVersion"),
            "persistedKnowledgeSource": manifest.get("knowledgeSource"),
            "persistedContentHash": manifest.get("contentHash"),
            "persistedEmbeddingProvider": manifest.get("embeddingProvider"),
            "persistedEmbeddingDimension": manifest.get("embeddingDimension"),
            "cacheValidationStatus": self.cache_validation_status,
            "cacheValidationReason": self.cache_validation_reason,
            "cacheMigrationErrorCode": self.cache_migration_error_code,
        }
        status.update(self.milvus.status())
        return status

    def is_initialized(self) -> bool:
        snapshot = self._capture_active_snapshot()
        return bool(snapshot.documents and snapshot.knowledge_source in ACTIVE_KNOWLEDGE_SOURCES)

    def _set_active_documents(self, documents: List[KnowledgeDocument], knowledge_source: str) -> None:
        if knowledge_source not in ACTIVE_KNOWLEDGE_SOURCES:
            documents = []
        self.documents = self._normalize_active_documents(documents)
        self.knowledge_source = knowledge_source if self.documents else "uninitialized"
        self.texts = [document_to_text(document) for document in self.documents]

    def _normalize_documents(self, texts: Optional[List[str]], knowledge_source: str) -> List[KnowledgeDocument]:
        normalized = [
            KnowledgeDocument(
                index + 1 if knowledge_source == "ai_knowledge" else None,
                first_line(text, index),
                text.strip(),
                [],
                "general",
                knowledge_source,
            )
            for index, text in enumerate(texts or [])
            if text and text.strip()
        ]
        if normalized:
            return normalized
        if self.use_default_knowledge and knowledge_source == "sample-dev":
            return list(DEFAULT_KNOWLEDGE)
        return []

    def _normalize_active_documents(self, documents: List[KnowledgeDocument]) -> List[KnowledgeDocument]:
        normalized = []
        for document in documents:
            title = (document.title or "").strip()
            content = (document.content or "").strip()
            if not title or not content:
                continue
            keywords = sorted({keyword.strip() for keyword in document.keywords if keyword and keyword.strip()})
            normalized.append(KnowledgeDocument(
                id=document.id if isinstance(document.id, int) and not isinstance(document.id, bool) else None,
                title=title,
                content=content,
                keywords=keywords,
                category=(document.category or "general").strip() or "general",
                source=(document.source or "ai_knowledge").strip() or "ai_knowledge",
            ))
        return sorted(normalized, key=document_sort_key)

    def _build_chunk_projection(
        self,
        documents: List[KnowledgeDocument],
        knowledge_version: str,
        knowledge_source: str,
    ) -> Tuple[IndexFingerprints, List[KnowledgeChunk]]:
        fingerprints = build_index_fingerprints(
            knowledge_version,
            self.embedding_descriptor,
            self.chunk_config,
            self.lexical_descriptor,
        )
        chunks = build_knowledge_chunks(
            documents,
            knowledge_version,
            fingerprints.index_version,
            self.chunk_config,
            require_formal_ids=knowledge_source == "ai_knowledge",
        )
        return fingerprints, chunks

    def _build_bm25_index(
        self,
        chunks: List[KnowledgeChunk],
        documents: List[KnowledgeDocument],
    ) -> BM25LexicalIndex:
        lexical_config = getattr(self, "lexical_config", None) or lexical_config_from_env()
        keywords = {
            int(document.id): tuple(document.keywords)
            for document in documents
            if isinstance(document.id, int) and not isinstance(document.id, bool)
        }
        return BM25LexicalIndex(chunks, keywords, lexical_config)

    def _bm25_hits(
        self,
        question: str,
        limit: int,
        snapshot: Optional[KnowledgeSnapshot] = None,
    ) -> List[KnowledgeHit]:
        active = snapshot or self._capture_active_snapshot()
        index = active.bm25_index
        if index is None and active.chunks and active.backend_states.get("bm25") != "FAILED":
            index = self._build_bm25_index(list(active.chunks), list(active.documents))
        if index is None:
            return []
        return [
            KnowledgeHit(
                content=match.chunk.chunk_content,
                metadata=match.chunk.metadata(),
                score=match.score,
                retriever="bm25",
                normalized_score=match.normalized_score,
            )
            for match in index.search(question, limit)
        ]

    def _filter_active_candidates(
        self,
        hits: List[KnowledgeHit],
        topic_keywords: Tuple[str, ...],
        snapshot: Optional[KnowledgeSnapshot] = None,
    ) -> List[KnowledgeHit]:
        active = snapshot or self._capture_active_snapshot()
        version_matched = []
        for hit in hits:
            chunk_id = hit.metadata.get("chunkId")
            active_chunk = active.chunks_by_id.get(chunk_id) if isinstance(chunk_id, str) else None
            if active_chunk is None:
                continue
            # 候选必须完整对应 active chunk；只伪造版本或 chunkId 不能进入融合结果。
            if (
                hit.metadata.get("knowledgeId") != active_chunk.knowledge_id
                or hit.metadata.get("source") != active_chunk.source
                or hit.metadata.get("knowledgeVersion") != active.knowledge_version
                or hit.metadata.get("indexVersion") != active.index_version
                or hit.metadata.get("status", 1) != 1
                or hit.content != active_chunk.chunk_content
            ):
                continue
            version_matched.append(hit)
        return filter_usable_hits(
            version_matched,
            topic_keywords,
            thresholds=getattr(self, "minimum_retrieval_scores", None),
        )

    def _activate_projection(
        self,
        documents: List[KnowledgeDocument],
        chunks: List[KnowledgeChunk],
        fingerprints: IndexFingerprints,
        vectorstore,
        bm25_index: Optional[BM25LexicalIndex],
        knowledge_version: str,
        knowledge_source: str,
        knowledge_updated_at: Optional[str],
        backend_states: Optional[Mapping[str, str]] = None,
        milvus_physical_collection: Optional[str] = None,
    ) -> None:
        resolved_backend_states = dict(backend_states or {
            "bm25": "READY" if bm25_index is not None else "FAILED",
            "faiss": "READY" if vectorstore is not None else "SKIPPED",
            "milvus": "READY" if self.milvus.active_collection_name else "SKIPPED",
        })
        self._publish_snapshot(self._new_snapshot(
            documents=documents,
            chunks=chunks,
            fingerprints=fingerprints,
            vectorstore=vectorstore,
            bm25_index=bm25_index,
            milvus_physical_collection=(
                milvus_physical_collection
                if milvus_physical_collection is not None
                else self.milvus.active_collection_name
            ),
            knowledge_version=knowledge_version,
            knowledge_source=knowledge_source,
            knowledge_updated_at=knowledge_updated_at,
            backend_states=resolved_backend_states,
        ))

    def _activate_empty(self) -> None:
        self._publish_snapshot(self._new_snapshot(
            documents=[],
            chunks=[],
            fingerprints=None,
            vectorstore=None,
            bm25_index=None,
            milvus_physical_collection=None,
            knowledge_version="uninitialized",
            knowledge_source="uninitialized",
            knowledge_updated_at=None,
            backend_states={"bm25": "SKIPPED", "faiss": "SKIPPED", "milvus": "SKIPPED"},
        ))
        self.persisted_vector_compatible = False

    def _activate_empty_candidate(
        self,
        knowledge_version: str,
        fingerprints: IndexFingerprints,
        knowledge_source: str,
    ) -> KnowledgeReloadResult:
        active = self._capture_active_snapshot()
        if (
            not active.documents
            and active.knowledge_version == knowledge_version
            and active.index_version == fingerprints.index_version
            and active.knowledge_source == knowledge_source
        ):
            result = KnowledgeReloadResult(
                True,
                False,
                "FAILED" in active.backend_states.values(),
                True,
                knowledge_version,
                fingerprints.index_version,
                active.backend_states,
                message="identical empty indexVersion already active",
            )
            self.reload_state = "READY"
            self.last_reload_result = result
            return result

        backend_states: Dict[str, str] = {
            "bm25": "READY",
            "faiss": "READY" if "faiss" in self.required_backends else "SKIPPED",
            "milvus": "SKIPPED",
        }
        if knowledge_source == "ai_knowledge":
            # 即使当前关闭 Milvus，也调用统一清理入口；关闭态是无外部 I/O 的幂等成功。
            milvus_cleared = self.milvus.clear()
            if milvus_enabled() or "milvus" in self.required_backends:
                backend_states["milvus"] = "READY" if milvus_cleared else "FAILED"
        self._reject_required_backends(
            backend_states,
            knowledge_version,
            fingerprints.index_version,
        )

        empty_bm25 = self._build_bm25_index([], [])
        snapshot = self._new_snapshot(
            documents=[],
            chunks=[],
            fingerprints=fingerprints,
            vectorstore=None,
            bm25_index=empty_bm25,
            milvus_physical_collection=None,
            knowledge_version=knowledge_version,
            knowledge_source=knowledge_source,
            knowledge_updated_at=_utc_now(),
            backend_states=backend_states,
        )
        self._publish_snapshot(snapshot)
        degraded = "FAILED" in backend_states.values()
        cache_error_code = None
        if self.persist_dir and knowledge_source == "ai_knowledge":
            try:
                # 空同步也提交带版本的 manifest v3，重启后不能重新暴露旧知识或丢失实例版本差异。
                self._persist_formal_cache()
            except Exception:
                degraded = True
                cache_error_code = "RAG_CACHE_WRITE_FAILED"
        self.persisted_vector_compatible = False
        self.reload_state = "DEGRADED" if degraded else "READY"
        self.last_activate_status = {
            "state": self.reload_state,
            "durationMs": 0.0,
            "errorCode": cache_error_code,
        }
        metrics.record_rag_activate("degraded" if degraded else "active")
        result = KnowledgeReloadResult(
            success=True,
            activated=True,
            degraded=degraded,
            idempotent=False,
            knowledge_version=knowledge_version,
            index_version=fingerprints.index_version,
            backend_states=_immutable_backend_states(backend_states),
            error_code=cache_error_code,
            message="empty snapshot activated",
        )
        self.last_reload_result = result
        return result

    def _reject_required_backends(
        self,
        backend_states: Mapping[str, str],
        candidate_knowledge_version: str,
        candidate_index_version: str,
        cause: Optional[BaseException] = None,
    ) -> None:
        failed = sorted(
            backend
            for backend in self.required_backends
            if backend_states.get(backend) != "READY"
        )
        if not failed:
            return
        error = KnowledgeReloadError(
            "RAG_REQUIRED_BACKEND_FAILED",
            "required RAG backends are not ready: " + ",".join(failed),
        )
        self.last_reload_result = KnowledgeReloadResult(
            success=False,
            activated=False,
            degraded=False,
            idempotent=False,
            knowledge_version=candidate_knowledge_version,
            index_version=candidate_index_version,
            backend_states=_immutable_backend_states(backend_states),
            candidate_collection=self.milvus.candidate_collection_name,
            error_code=error.error_code,
            message=(str(error) + (f"; cause={type(cause).__name__}" if cause else "")),
        )
        raise error from cause

    def _new_snapshot(
        self,
        documents: Sequence[KnowledgeDocument],
        chunks: Sequence[KnowledgeChunk],
        fingerprints: Optional[IndexFingerprints],
        vectorstore,
        bm25_index: Optional[BM25LexicalIndex],
        milvus_physical_collection: Optional[str],
        knowledge_version: str,
        knowledge_source: str,
        knowledge_updated_at: Optional[str],
        backend_states: Mapping[str, str],
    ) -> KnowledgeSnapshot:
        return KnowledgeSnapshot(
            knowledge_version=knowledge_version,
            index_version=fingerprints.index_version if fingerprints else "uninitialized",
            documents=tuple(documents),
            chunks=tuple(chunks),
            chunks_by_id=MappingProxyType({chunk.chunk_id: chunk for chunk in chunks}),
            faiss_store=vectorstore,
            bm25_index=bm25_index,
            milvus_physical_collection=milvus_physical_collection,
            fingerprints=fingerprints,
            knowledge_source=knowledge_source,
            knowledge_updated_at=knowledge_updated_at,
            created_at=_utc_now(),
            backend_states=_immutable_backend_states(backend_states),
        )

    def _publish_snapshot(self, snapshot: KnowledgeSnapshot) -> None:
        # 提交阶段只交换一个对象引用；公开字段是兼容旧调用方的副本，不被查询链路再次读取。
        lock = getattr(self, "_snapshot_lock", None)
        if lock is None:
            self._set_snapshot_fields(snapshot)
            return
        with lock:
            self._set_snapshot_fields(snapshot)

    def _set_snapshot_fields(self, snapshot: KnowledgeSnapshot) -> None:
        self._active_snapshot = snapshot
        self.documents = [replace(document) for document in snapshot.documents]
        self.texts = [document_to_text(document) for document in snapshot.documents]
        self.chunks = list(snapshot.chunks)
        self.index_fingerprints = snapshot.fingerprints
        self.index_version = snapshot.index_version
        self.vectorstore = snapshot.faiss_store
        self.bm25_index = snapshot.bm25_index
        self.knowledge_version = snapshot.knowledge_version
        self.knowledge_source = snapshot.knowledge_source
        self.knowledge_updated_at = snapshot.knowledge_updated_at
        # 兼容公开字段的对象引用只用于识别旧调用方是否替换过视图，正常查询不复制列表。
        self._published_documents_view = self.documents
        self._published_chunks_view = self.chunks
        self._published_vectorstore_view = self.vectorstore
        self._published_bm25_view = self.bm25_index
        metrics.set_rag_active_snapshot(
            snapshot.knowledge_version,
            snapshot.index_version,
            len(snapshot.chunks),
        )

    def _publish_current_fields_as_snapshot(self) -> None:
        fingerprints = getattr(self, "index_fingerprints", None)
        backend_states = {
            "bm25": "READY" if getattr(self, "bm25_index", None) is not None else "SKIPPED",
            "faiss": "READY" if getattr(self, "vectorstore", None) is not None else "SKIPPED",
            "milvus": "READY" if self.milvus.active_collection_name else "SKIPPED",
        }
        self._publish_snapshot(self._new_snapshot(
            documents=getattr(self, "documents", []),
            chunks=getattr(self, "chunks", []),
            fingerprints=fingerprints,
            vectorstore=getattr(self, "vectorstore", None),
            bm25_index=getattr(self, "bm25_index", None),
            milvus_physical_collection=self.milvus.active_collection_name,
            knowledge_version=getattr(self, "knowledge_version", "uninitialized"),
            knowledge_source=getattr(self, "knowledge_source", "uninitialized"),
            knowledge_updated_at=getattr(self, "knowledge_updated_at", None),
            backend_states=backend_states,
        ))

    def _capture_active_snapshot(self) -> KnowledgeSnapshot:
        # active snapshot 先完整构造再单次替换对象引用；查询捕获一次引用即可保持整次请求版本一致。
        return self._compatible_snapshot(getattr(self, "_active_snapshot", None))

    def _compatible_snapshot(
        self,
        snapshot: Optional[KnowledgeSnapshot],
    ) -> KnowledgeSnapshot:
        # 少量历史单测直接替换公开字段；检测到这种情况时为该次请求生成独立兼容快照。
        if snapshot is not None and (
            getattr(self, "documents", None) is getattr(self, "_published_documents_view", None)
            and getattr(self, "chunks", None) is getattr(self, "_published_chunks_view", None)
            and getattr(self, "vectorstore", None) is getattr(self, "_published_vectorstore_view", None)
            and getattr(self, "bm25_index", None) is getattr(self, "_published_bm25_view", None)
            and getattr(self, "knowledge_version", None) == snapshot.knowledge_version
            and getattr(self, "index_version", None) == snapshot.index_version
            and getattr(self, "knowledge_source", None) == snapshot.knowledge_source
        ):
            return snapshot
        documents = tuple(getattr(self, "documents", ()))
        chunks = tuple(getattr(self, "chunks", ()))
        fingerprints = getattr(self, "index_fingerprints", None)
        backend_states = {
            "bm25": "READY" if getattr(self, "bm25_index", None) is not None else "SKIPPED",
            "faiss": "READY" if getattr(self, "vectorstore", None) is not None else "SKIPPED",
            "milvus": "READY" if getattr(getattr(self, "milvus", None), "active_collection_name", None) else "SKIPPED",
        }
        return KnowledgeSnapshot(
            knowledge_version=getattr(self, "knowledge_version", "uninitialized"),
            index_version=getattr(self, "index_version", "uninitialized"),
            documents=documents,
            chunks=chunks,
            chunks_by_id=MappingProxyType({chunk.chunk_id: chunk for chunk in chunks}),
            faiss_store=getattr(self, "vectorstore", None),
            bm25_index=getattr(self, "bm25_index", None),
            milvus_physical_collection=(
                snapshot.milvus_physical_collection if snapshot is not None else None
            ),
            fingerprints=fingerprints,
            knowledge_source=getattr(self, "knowledge_source", "uninitialized"),
            knowledge_updated_at=getattr(self, "knowledge_updated_at", None),
            created_at=snapshot.created_at if snapshot else _utc_now(),
            backend_states=_immutable_backend_states(backend_states),
        )

    def _refresh_active_chunks(self) -> None:
        if not self.documents:
            self.chunks = []
            self.index_fingerprints = None
            self.index_version = "uninitialized"
            self.bm25_index = None
            return
        fingerprints, chunks = self._build_chunk_projection(
            self.documents,
            self.knowledge_version,
            self.knowledge_source,
        )
        self.chunks = chunks
        self.index_fingerprints = fingerprints
        self.index_version = fingerprints.index_version
        self.bm25_index = self._build_bm25_index(chunks, self.documents)

    def _restore_persisted_cache(self) -> bool:
        if not self.persist_dir:
            return False
        manifest_path = self._manifest_path()
        if not manifest_path or not manifest_path.exists():
            self.cache_validation_status = "missing"
            self.cache_validation_reason = "manifest_missing"
            return False
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            self._mark_cache_invalid("manifest_unreadable")
            return False
        if not isinstance(manifest, dict):
            self._mark_cache_invalid("manifest_schema_invalid")
            return False
        schema_version = manifest.get("manifestSchemaVersion")
        if schema_version == LEGACY_MANIFEST_SCHEMA_VERSION:
            return self._migrate_v2_cache(manifest)

        reason = self._validate_manifest_v3(manifest)
        if reason:
            self._mark_cache_invalid(reason)
            return False
        documents = self._read_cached_documents(manifest, allow_empty=True)
        if documents is None:
            return False
        try:
            fingerprints, chunks = self._build_chunk_projection(
                documents,
                manifest["knowledgeVersion"],
                "ai_knowledge",
            ) if documents else (
                build_index_fingerprints(
                    manifest["knowledgeVersion"],
                    self.embedding_descriptor,
                    self.chunk_config,
                    self.lexical_descriptor,
                ),
                [],
            )
        except KnowledgeIndexValidationError:
            self._mark_cache_invalid("documents_chunk_projection_invalid")
            return False
        if manifest["chunkCount"] != len(chunks):
            self._mark_cache_invalid("chunk_count_mismatch")
            return False
        if manifest["indexVersion"] != fingerprints.index_version:
            self._mark_cache_invalid("index_version_mismatch")
            return False
        if (
            manifest["embeddingFingerprint"] != fingerprints.embedding_fingerprint
            or manifest["chunkFingerprint"] != fingerprints.chunk_fingerprint
            or manifest["lexicalFingerprint"] != fingerprints.lexical_fingerprint
        ):
            self._mark_cache_invalid("index_fingerprint_mismatch")
            return False

        vectorstore = self._restore_v3_faiss(manifest, chunks)
        if manifest["faissIndexPresent"] and vectorstore is None:
            return False
        backend_states = dict(manifest["backendStates"])
        milvus_collection = self._restore_v3_milvus(manifest, len(chunks), backend_states)
        if any(backend_states.get(backend) != "READY" for backend in self.required_backends):
            self._mark_cache_invalid("required_backend_not_ready")
            return False

        bm25_index = self._build_bm25_index(chunks, documents)
        self._activate_projection(
            documents,
            chunks,
            fingerprints,
            vectorstore,
            bm25_index,
            manifest["knowledgeVersion"],
            "ai_knowledge",
            manifest["knowledgeUpdatedAt"],
            backend_states=backend_states,
            milvus_physical_collection=milvus_collection,
        )
        self.cache_validation_status = "valid"
        self.cache_validation_reason = "v3_restored"
        self.persisted_vector_compatible = vectorstore is not None
        self.reload_state = "DEGRADED" if "FAILED" in backend_states.values() else (
            "ACTIVE" if documents else "READY"
        )
        return True

    def _migrate_v2_cache(self, manifest: Mapping[str, object]) -> bool:
        reason = self._validate_manifest_v2(manifest)
        if reason:
            self._mark_cache_invalid(reason)
            return False
        documents = self._read_cached_documents(manifest, allow_empty=False)
        if documents is None:
            return False
        try:
            # v2 的 FAISS 和整文档 Milvus 没有统一 chunk 指纹，只允许规范文档重新走当前候选流水线。
            result = self.reload_documents(
                documents,
                knowledge_version=str(manifest["knowledgeVersion"]),
                knowledge_source="ai_knowledge",
            )
            committed = self._read_manifest().get("manifestSchemaVersion") == MANIFEST_SCHEMA_VERSION
            if not result.success or not committed:
                raise KnowledgeReloadError(
                    "CACHE_MIGRATION_FAILED",
                    "v2 documents could not be committed as manifest v3",
                )
            self.cache_validation_status = "valid"
            self.cache_validation_reason = "v2_migrated_to_v3"
            self.cache_migration_error_code = None
            return True
        except Exception:
            self.cache_migration_error_code = "CACHE_MIGRATION_FAILED"
            self._mark_cache_invalid("CACHE_MIGRATION_FAILED")
            self.reload_state = "DEGRADED"
            return False

    def _validate_manifest_v2(self, manifest: Mapping[str, object]) -> Optional[str]:
        required_fields = {
            "manifestSchemaVersion",
            "knowledgeSource",
            "knowledgeVersion",
            "knowledgeUpdatedAt",
            "documentCount",
            "contentHash",
            "embeddingProvider",
            "embeddingModel",
            "embeddingDimension",
            "vectorIndexPresent",
        }
        if not required_fields.issubset(manifest):
            return "manifest_fields_missing"
        if manifest.get("manifestSchemaVersion") != LEGACY_MANIFEST_SCHEMA_VERSION:
            return "manifest_schema_version_unsupported"
        if manifest.get("knowledgeSource") != "ai_knowledge":
            return "knowledge_source_invalid"
        if not isinstance(manifest.get("knowledgeVersion"), str) or not manifest["knowledgeVersion"].strip():
            return "knowledge_version_invalid"
        if not isinstance(manifest.get("knowledgeUpdatedAt"), str) or not manifest["knowledgeUpdatedAt"].strip():
            return "knowledge_updated_at_invalid"
        if type(manifest.get("documentCount")) is not int or manifest["documentCount"] <= 0:
            return "document_count_invalid"
        if not isinstance(manifest.get("contentHash"), str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", manifest["contentHash"]):
            return "content_hash_invalid"
        if not isinstance(manifest.get("embeddingProvider"), str):
            return "embedding_provider_invalid"
        if not isinstance(manifest.get("embeddingModel"), str):
            return "embedding_model_invalid"
        if type(manifest.get("embeddingDimension")) is not int or manifest["embeddingDimension"] <= 0:
            return "embedding_dimension_invalid"
        if type(manifest.get("vectorIndexPresent")) is not bool:
            return "vector_index_flag_invalid"
        return None

    def _validate_manifest_v3(self, manifest: Mapping[str, object]) -> Optional[str]:
        required_fields = {
            "manifestSchemaVersion", "knowledgeSource", "knowledgeVersion", "knowledgeUpdatedAt",
            "documentCount", "chunkCount", "contentHash", "indexVersion",
            "embeddingProvider", "embeddingModel", "embeddingDimension",
            "embeddingAlgorithmRevision", "embeddingDeploymentRevision", "embeddingFingerprint",
            "chunkSize", "chunkOverlap", "chunkUnit", "chunkAlgorithm", "chunkFingerprint",
            "lexicalImplementation", "lexicalRevision", "lexicalParameters", "lexicalFingerprint",
            "indexSchemaVersion", "vectorIndexPresent", "faissIndexPresent", "bm25IndexPresent",
            "faissIndexSha256", "faissMetadataSha256", "milvusCollectionName", "backendStates",
        }
        if not required_fields.issubset(manifest):
            return "manifest_fields_missing"
        if manifest.get("manifestSchemaVersion") != MANIFEST_SCHEMA_VERSION:
            return "manifest_schema_version_unsupported"
        if manifest.get("knowledgeSource") != "ai_knowledge":
            return "knowledge_source_invalid"
        if not isinstance(manifest.get("knowledgeVersion"), str) or not manifest["knowledgeVersion"].strip():
            return "knowledge_version_invalid"
        if not isinstance(manifest.get("knowledgeUpdatedAt"), str) or not manifest["knowledgeUpdatedAt"].strip():
            return "knowledge_updated_at_invalid"
        for field_name in ("documentCount", "chunkCount"):
            if type(manifest.get(field_name)) is not int or manifest[field_name] < 0:
                return field_name.replace("Count", "_count").lower() + "_invalid"
        if not isinstance(manifest.get("contentHash"), str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", manifest["contentHash"]):
            return "content_hash_invalid"
        expected_values = {
            "embeddingProvider": self.embedding_descriptor.provider,
            "embeddingModel": self.embedding_descriptor.model,
            "embeddingDimension": self.embedding_descriptor.dimension,
            "embeddingAlgorithmRevision": self.embedding_descriptor.algorithm_revision,
            "embeddingDeploymentRevision": self.embedding_descriptor.deployment_revision,
            "chunkSize": self.chunk_config.size,
            "chunkOverlap": self.chunk_config.overlap,
            "chunkUnit": self.chunk_config.unit,
            "chunkAlgorithm": self.chunk_config.algorithm,
            "lexicalImplementation": self.lexical_descriptor.implementation,
            "lexicalRevision": self.lexical_descriptor.tokenizer_revision,
            "lexicalParameters": self.lexical_descriptor.parameters,
        }
        for field_name, expected in expected_values.items():
            if manifest.get(field_name) != expected:
                return _camel_to_snake(field_name) + "_mismatch"
        if manifest.get("indexSchemaVersion") != build_index_fingerprints(
            str(manifest["knowledgeVersion"]),
            self.embedding_descriptor,
            self.chunk_config,
            self.lexical_descriptor,
        ).index_schema_version:
            return "index_schema_version_mismatch"
        for field_name in ("vectorIndexPresent", "faissIndexPresent", "bm25IndexPresent"):
            if type(manifest.get(field_name)) is not bool:
                return _camel_to_snake(field_name) + "_invalid"
        if manifest["vectorIndexPresent"] != manifest["faissIndexPresent"]:
            return "vector_index_flag_mismatch"
        if manifest["faissIndexPresent"]:
            for field_name in ("faissIndexSha256", "faissMetadataSha256"):
                value = manifest.get(field_name)
                if not isinstance(value, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", value):
                    return _camel_to_snake(field_name) + "_invalid"
        elif manifest.get("faissIndexSha256") is not None or manifest.get("faissMetadataSha256") is not None:
            return "faiss_index_hash_unexpected"
        if not manifest["bm25IndexPresent"]:
            return "bm25_index_missing"
        states = manifest.get("backendStates")
        if not isinstance(states, dict) or set(states) != set(SUPPORTED_RAG_BACKENDS):
            return "backend_states_invalid"
        if any(state not in {"READY", "FAILED", "SKIPPED"} for state in states.values()):
            return "backend_states_invalid"
        if states.get("bm25") != "READY":
            return "bm25_backend_state_invalid"
        if manifest["faissIndexPresent"] != (states.get("faiss") == "READY") and manifest["chunkCount"] > 0:
            return "faiss_backend_state_mismatch"
        collection_name = manifest.get("milvusCollectionName")
        if collection_name is not None and (
            not isinstance(collection_name, str)
            or len(collection_name) > 255
            or re.fullmatch(r"[A-Za-z0-9_]+", collection_name) is None
        ):
            return "milvus_collection_name_invalid"
        if (states.get("milvus") == "READY") != bool(manifest.get("milvusCollectionName")) and manifest["chunkCount"] > 0:
            return "milvus_backend_state_mismatch"
        return None

    def _read_cached_documents(
        self,
        manifest: Mapping[str, object],
        allow_empty: bool,
    ) -> Optional[List[KnowledgeDocument]]:
        documents_path = self.persist_dir / "documents.json" if self.persist_dir else None
        if not documents_path or not documents_path.is_file():
            self._mark_cache_invalid("documents_missing")
            return None
        try:
            raw_documents = documents_path.read_bytes()
            records = json.loads(raw_documents.decode("utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            self._mark_cache_invalid("documents_unreadable")
            return None
        documents = self._documents_from_records(records, allow_empty=allow_empty)
        if documents is None:
            self._mark_cache_invalid("documents_schema_invalid")
            return None
        canonical_documents = self._serialize_documents(documents)
        if raw_documents != canonical_documents:
            self._mark_cache_invalid("documents_not_canonical")
            return None
        if manifest["documentCount"] != len(documents):
            self._mark_cache_invalid("document_count_mismatch")
            return None
        if manifest["contentHash"] != content_hash(canonical_documents):
            self._mark_cache_invalid("content_hash_mismatch")
            return None
        return documents

    def _restore_v3_faiss(
        self,
        manifest: Mapping[str, object],
        chunks: Sequence[KnowledgeChunk],
    ):
        if not manifest["faissIndexPresent"]:
            self.persisted_vector_compatible = False
            return None
        if not self._index_exists():
            self._mark_cache_invalid("faiss_index_incomplete")
            return None
        index_path = self.persist_dir / "index.faiss"
        metadata_path = self.persist_dir / "index.pkl"
        if (
            manifest.get("faissIndexSha256") != _file_sha256(index_path)
            or manifest.get("faissMetadataSha256") != _file_sha256(metadata_path)
        ):
            self._mark_cache_invalid("faiss_index_hash_mismatch")
            return None
        if not vector_dependencies_enabled() or FAISS is None:
            self._mark_cache_invalid("faiss_runtime_unavailable")
            return None
        try:
            # index.pkl 只有在 SHA-256 与 manifest v3 完全一致后才允许反序列化。
            vectorstore = FAISS.load_local(
                str(self.persist_dir),
                self._embeddings(),
                allow_dangerous_deserialization=True,
            )
            expected_chunks = {chunk.chunk_id: chunk for chunk in chunks}
            restored_chunks: Dict[str, object] = {}
            for document_id in vectorstore.index_to_docstore_id.values():
                document = vectorstore.docstore.search(document_id)
                metadata = getattr(document, "metadata", {}) or {}
                chunk_id = metadata.get("chunkId")
                if not isinstance(chunk_id, str) or chunk_id not in expected_chunks:
                    raise KnowledgeIndexValidationError("FAISS metadata contains an unknown chunkId")
                if getattr(document, "page_content", None) != expected_chunks[chunk_id].chunk_content:
                    raise KnowledgeIndexValidationError("FAISS content does not match manifest chunks")
                restored_chunks[chunk_id] = document
            if set(restored_chunks) != set(expected_chunks):
                raise KnowledgeIndexValidationError("FAISS chunk set does not match manifest chunks")
            return vectorstore
        except Exception:
            self._mark_cache_invalid("faiss_index_load_failed")
            return None

    def _restore_v3_milvus(
        self,
        manifest: Mapping[str, object],
        expected_count: int,
        backend_states: Dict[str, str],
    ) -> Optional[str]:
        collection_name = manifest.get("milvusCollectionName")
        if not isinstance(collection_name, str) or not collection_name:
            return None
        try:
            if not self.milvus.available() or not self.milvus.connect():
                raise RuntimeError("MilvusUnavailable")
            if not utility.has_collection(collection_name, using=self.milvus.alias):
                raise RuntimeError("MilvusCollectionMissing")
            collection = Collection(collection_name, using=self.milvus.alias)
            collection.load()
            self.milvus._validate_ready_collection(collection, expected_count)
            self.milvus._loaded_collections[collection_name] = collection
            self.milvus.active_collection_name = collection_name
            self.milvus.document_count = expected_count
            return collection_name
        except Exception:
            backend_states["milvus"] = "FAILED"
            metrics.record_rag_degraded("milvus", "unavailable")
            return None

    def _documents_from_records(
        self,
        records: object,
        allow_empty: bool = False,
    ) -> Optional[List[KnowledgeDocument]]:
        if not isinstance(records, list) or (not records and not allow_empty):
            return None
        documents = []
        for record in records:
            if not isinstance(record, dict) or set(record) != set(DOCUMENT_FIELDS):
                return None
            document_id = record.get("id")
            if document_id is not None and (not isinstance(document_id, int) or isinstance(document_id, bool)):
                return None
            if not isinstance(record.get("title"), str) or not record["title"].strip():
                return None
            if not isinstance(record.get("content"), str) or not record["content"].strip():
                return None
            if not isinstance(record.get("keywords"), list) or not all(isinstance(item, str) for item in record["keywords"]):
                return None
            if not isinstance(record.get("category"), str) or not record["category"].strip():
                return None
            if not isinstance(record.get("source"), str) or not record["source"].strip():
                return None
            documents.append(KnowledgeDocument(
                id=document_id,
                title=record["title"],
                content=record["content"],
                keywords=record["keywords"],
                category=record["category"],
                source=record["source"],
            ))
        normalized = self._normalize_active_documents(documents)
        return normalized if len(normalized) == len(documents) else None

    def _serialize_documents(self, documents: List[KnowledgeDocument]) -> bytes:
        records = [document_record(document) for document in documents]
        return json.dumps(records, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    def _persist_formal_cache(self) -> None:
        snapshot = self._capture_active_snapshot()
        if not self.persist_dir or snapshot.knowledge_source != "ai_knowledge":
            return
        if snapshot.fingerprints is None:
            raise KnowledgeIndexValidationError("formal snapshot requires index fingerprints")
        staging_directory = self.persist_dir / (".cache-write-" + uuid.uuid4().hex)
        staging_directory.mkdir(parents=False, exist_ok=False)
        try:
            documents_bytes = self._serialize_documents(list(snapshot.documents))
            (staging_directory / "documents.json").write_bytes(documents_bytes)
            vector_index_present = snapshot.faiss_store is not None and bool(snapshot.chunks)
            if vector_index_present:
                snapshot.faiss_store.save_local(str(staging_directory))
                if not self._index_exists(staging_directory):
                    raise RuntimeError("vector cache files were not created")
            manifest = {
                "manifestSchemaVersion": MANIFEST_SCHEMA_VERSION,
                "knowledgeSource": "ai_knowledge",
                "knowledgeVersion": snapshot.knowledge_version,
                "knowledgeUpdatedAt": snapshot.knowledge_updated_at,
                "documentCount": len(snapshot.documents),
                "chunkCount": len(snapshot.chunks),
                "contentHash": content_hash(documents_bytes),
                "indexVersion": snapshot.index_version,
                "embeddingProvider": self.embedding_descriptor.provider,
                "embeddingModel": self.embedding_descriptor.model,
                "embeddingDimension": self.embedding_descriptor.dimension,
                "embeddingAlgorithmRevision": self.embedding_descriptor.algorithm_revision,
                "embeddingDeploymentRevision": self.embedding_descriptor.deployment_revision,
                "embeddingFingerprint": snapshot.fingerprints.embedding_fingerprint,
                "chunkSize": self.chunk_config.size,
                "chunkOverlap": self.chunk_config.overlap,
                "chunkUnit": self.chunk_config.unit,
                "chunkAlgorithm": self.chunk_config.algorithm,
                "chunkFingerprint": snapshot.fingerprints.chunk_fingerprint,
                "lexicalImplementation": self.lexical_descriptor.implementation,
                "lexicalRevision": self.lexical_descriptor.tokenizer_revision,
                "lexicalParameters": self.lexical_descriptor.parameters,
                "lexicalFingerprint": snapshot.fingerprints.lexical_fingerprint,
                "indexSchemaVersion": snapshot.fingerprints.index_schema_version,
                "vectorIndexPresent": vector_index_present,
                "faissIndexPresent": vector_index_present,
                "bm25IndexPresent": snapshot.bm25_index is not None,
                "faissIndexSha256": (
                    _file_sha256(staging_directory / "index.faiss") if vector_index_present else None
                ),
                "faissMetadataSha256": (
                    _file_sha256(staging_directory / "index.pkl") if vector_index_present else None
                ),
                "milvusCollectionName": snapshot.milvus_physical_collection,
                "backendStates": dict(snapshot.backend_states),
            }
            manifest_bytes = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            (staging_directory / "manifest.json").write_bytes(manifest_bytes)

            self._commit_cache_candidate(staging_directory)
            self.cache_validation_status = "valid"
            self.cache_validation_reason = "written"
            self.persisted_vector_compatible = vector_index_present
        except Exception:
            self.cache_validation_status = "invalid"
            self.cache_validation_reason = "cache_write_failed"
            self.persisted_vector_compatible = False
            raise
        finally:
            shutil.rmtree(staging_directory, ignore_errors=True)

    def _commit_cache_candidate(self, staging_directory: Path) -> None:
        backup_directory = self.persist_dir / (".cache-backup-" + uuid.uuid4().hex)
        backup_directory.mkdir(parents=False, exist_ok=False)
        try:
            # 先撤下旧 manifest，再替换数据，最后提交新 manifest；进程中断时读取方只能看到旧快照或无快照。
            commit_order = ("documents.json", "index.faiss", "index.pkl")
            manifest_path = self.persist_dir / "manifest.json"
            if manifest_path.exists():
                os.replace(manifest_path, backup_directory / "manifest.json")
            for file_name in commit_order:
                current = self.persist_dir / file_name
                if current.exists():
                    os.replace(current, backup_directory / file_name)
            for file_name in commit_order:
                candidate = staging_directory / file_name
                if candidate.exists():
                    os.replace(candidate, self.persist_dir / file_name)
            os.replace(staging_directory / "manifest.json", manifest_path)
        except Exception:
            # 提交异常时精确恢复此前快照，避免合法 v2 在迁移失败后被半个 v3 覆盖。
            for file_name in CACHE_FILE_NAMES:
                current = self.persist_dir / file_name
                if current.exists():
                    current.unlink()
            for file_name in ("documents.json", "index.faiss", "index.pkl", "manifest.json"):
                backup = backup_directory / file_name
                if backup.exists():
                    os.replace(backup, self.persist_dir / file_name)
            raise
        finally:
            shutil.rmtree(backup_directory, ignore_errors=True)

    def _clear_persisted_cache(self) -> None:
        if not self.persist_dir:
            self.cache_validation_status = "not_configured"
            self.cache_validation_reason = "persist_directory_not_configured"
            self.persisted_vector_compatible = False
            return
        for file_name in CACHE_FILE_NAMES:
            self._unlink_cache_file(file_name)
        for staging_directory in self.persist_dir.glob(".cache-write-*"):
            if staging_directory.is_dir():
                shutil.rmtree(staging_directory, ignore_errors=True)
        self.cache_validation_status = "missing"
        self.cache_validation_reason = "empty_sync"
        self.persisted_vector_compatible = False

    def _load_or_build_vectorstore(self) -> None:
        if not self.chunks or not vector_dependencies_enabled():
            return
        start = now()
        try:
            embeddings = self._embeddings()
            vectors = validate_embedding_vectors(
                embeddings.embed_documents([chunk.embedding_text for chunk in self.chunks]),
                expected_count=len(self.chunks),
                expected_dimension=self.embedding_descriptor.dimension,
            )
            self.vectorstore = self._build_vectorstore(self.chunks, vectors, embeddings)
            metrics.record("rag.vector_build", elapsed_ms(start), success=True)
        except Exception as exc:
            metrics.record("rag.vector_build", elapsed_ms(start), success=False, error=exc)
            raise

    def _build_vectorstore(self, chunks: List[KnowledgeChunk], vectors: List[List[float]], embeddings):
        if not chunks or not vector_dependencies_enabled():
            return None
        if embeddings is None:
            raise KnowledgeIndexValidationError("embedding client is required to build FAISS")
        if len(vectors) != len(chunks):
            raise KnowledgeIndexValidationError("validated vectors must match active chunks")
        return FAISS.from_embeddings(
            [(chunk.chunk_content, vector) for chunk, vector in zip(chunks, vectors)],
            embeddings,
            metadatas=[chunk.metadata() for chunk in chunks],
            ids=[chunk.chunk_id for chunk in chunks],
        )

    def _embeddings(self):
        # candidate、FAISS 恢复和查询共享同一配置身份下的 Embedding 客户端，但不缓存向量结果。
        if self._embedding_client is None:
            self._embedding_client = build_embeddings()
        return self._embedding_client

    def _index_exists(self, directory: Optional[Path] = None) -> bool:
        root = directory or self.persist_dir
        if not root:
            return False
        return (root / "index.faiss").is_file() and (root / "index.pkl").is_file()

    def _manifest_path(self) -> Optional[Path]:
        return self.persist_dir / "manifest.json" if self.persist_dir else None

    def _read_manifest(self) -> dict:
        manifest_path = self._manifest_path()
        if not manifest_path or not manifest_path.exists():
            return {}
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            return manifest if isinstance(manifest, dict) else {}
        except (OSError, UnicodeError, json.JSONDecodeError):
            return {}

    def _unlink_cache_file(self, file_name: str) -> None:
        if not self.persist_dir:
            return
        path = self.persist_dir / file_name
        if path.exists():
            path.unlink()

    def _mark_cache_invalid(self, reason: str) -> None:
        self.cache_validation_status = "invalid"
        self.cache_validation_reason = reason
        self.persisted_vector_compatible = False

    def _build_local_version(self, documents: Optional[List[KnowledgeDocument]] = None) -> str:
        texts = [document_to_text(document) for document in (documents or self.documents)]
        digest = hashlib.sha256("\n".join(texts).encode("utf-8")).hexdigest()[:16]
        return "local-" + digest


def _stable_rag_error_code(exc: BaseException) -> str:
    explicit = getattr(exc, "error_code", None)
    if isinstance(explicit, str) and explicit:
        return explicit
    if isinstance(exc, MilvusCandidateError):
        return "RAG_MILVUS_ACTIVATE_FAILED" if exc.stage == "alias" else "RAG_MILVUS_PREPARE_FAILED"
    if isinstance(exc, KnowledgeIndexConfigurationError):
        return "RAG_CONFIGURATION_INVALID"
    if isinstance(exc, KnowledgeIndexValidationError):
        return "RAG_DOCUMENTS_INVALID"
    return "RAG_RELOAD_FAILED"


def _safe_reload_message(error_code: str) -> str:
    messages = {
        "CACHE_MIGRATION_FAILED": "manifest v2 migration to v3 failed",
        "RAG_CONFIGURATION_INVALID": "RAG index configuration is invalid",
        "RAG_DOCUMENTS_INVALID": "knowledge documents failed validation",
        "RAG_MILVUS_ACTIVATE_FAILED": "Milvus candidate activation failed",
        "RAG_MILVUS_PREPARE_FAILED": "Milvus candidate preparation failed",
        "RAG_REQUIRED_BACKEND_FAILED": "required RAG backend is not ready",
    }
    return messages.get(error_code, "RAG knowledge reload failed")


def _camel_to_snake(value: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", value).lower()


def _complete_rag_trace(
    span,
    vector_backend: str = "none",
    vector_candidate_count: int = 0,
    lexical_candidate_count: int = 0,
    filtered_candidate_count: int = 0,
    final_hit_count: int = 0,
    degraded: bool = False,
    fallback_reason: Optional[str] = None,
) -> None:
    if span is None:
        return
    span.set_attribute("ai.rag.vector_backend", vector_backend)
    span.set_attribute("ai.rag.vector_candidate_count", max(0, vector_candidate_count))
    span.set_attribute("ai.rag.lexical_candidate_count", max(0, lexical_candidate_count))
    span.set_attribute("ai.rag.filtered_candidate_count", max(0, filtered_candidate_count))
    span.set_attribute("ai.rag.final_hit_count", max(0, final_hit_count))
    span.set_attribute("ai.rag.degraded", bool(degraded))
    span.set_attribute("ai.rag.fallback_reason", fallback_reason or "")


def document_sort_key(document: KnowledgeDocument) -> Tuple[object, ...]:
    return (
        document.id is None,
        document.id if document.id is not None else 0,
        document.title,
        document.content,
        tuple(document.keywords),
        document.category or "",
        document.source or "",
    )


def document_record(document: KnowledgeDocument) -> Dict[str, object]:
    return {
        "id": document.id,
        "title": document.title,
        "content": document.content,
        "keywords": list(document.keywords),
        "category": document.category or "general",
        "source": document.source or "ai_knowledge",
    }


def content_hash(serialized_documents: bytes) -> str:
    return "sha256:" + hashlib.sha256(serialized_documents).hexdigest()


def document_to_text(document: KnowledgeDocument) -> str:
    parts = [document.category or "general", document.title, document.content]
    if document.keywords:
        parts.append("Keywords: " + ", ".join(document.keywords))
    if document.source:
        parts.append("Source: " + document.source)
    return "\n".join(parts)


def document_metadata(document: KnowledgeDocument, knowledge_version: Optional[str] = None) -> Dict[str, object]:
    metadata = {
        "knowledgeId": document.id,
        "title": document.title,
        "category": document.category,
        "source": document.source,
    }
    if knowledge_version:
        metadata["knowledgeVersion"] = knowledge_version
    return metadata


def first_line(text: str, index: int) -> str:
    for line in text.splitlines():
        if line.strip():
            return limit_text(line.strip(), 80)
    return "knowledge-" + str(index + 1)


def limit_text(value: Optional[str], limit: int) -> str:
    text = value or ""
    return text[:limit]


def keyword_retrieve(
    query: str,
    documents: List[KnowledgeDocument],
    limit: int = 3,
    knowledge_version: Optional[str] = None,
) -> List[KnowledgeHit]:
    terms = query_terms(query)
    scored = []
    for document in documents:
        text = document_to_text(document)
        score = sum(1 for term in terms if term in text.lower())
        if score > 0:
            scored.append((score, document))
    scored.sort(key=lambda item: item[0], reverse=True)
    if not scored:
        return []
    return [
        KnowledgeHit(
            content=document.content,
            metadata=document_metadata(document, knowledge_version),
            score=float(score),
            retriever="keyword",
        )
        for score, document in scored[:limit]
    ]


def normalize_hits(hits: List[KnowledgeHit]) -> List[KnowledgeHit]:
    normalized = []
    for hit in hits:
        score = hit.score
        normalized_score = score
        if score is not None:
            if hit.retriever == "faiss":
                normalized_score = _faiss_similarity(score)
            elif hit.retriever == "bm25":
                normalized_score = max(0.0, float(score)) / (max(0.0, float(score)) + 1.0)
            elif hit.retriever == "keyword":
                normalized_score = min(1.0, float(score) / 3.0)
            else:
                normalized_score = max(0.0, min(1.0, float(score)))
        normalized.append(replace(hit, normalized_score=normalized_score))
    return normalized


def _faiss_similarity(score: Optional[float]) -> Optional[float]:
    if score is None:
        return None
    # FAISS 当前索引使用归一化向量的平方 L2 距离；1-d/2 才与余弦相似度同尺度。
    return max(0.0, min(1.0, 1.0 - max(0.0, float(score)) / 2.0))


def filter_usable_hits(
    hits: List[KnowledgeHit],
    topic_keywords: Tuple[str, ...] = (),
    thresholds: Optional[Mapping[str, float]] = None,
) -> List[KnowledgeHit]:
    minimum_scores = thresholds or retrieval_minimum_scores()
    filtered = []
    for hit in hits:
        threshold = minimum_scores.get(hit.retriever, 0.0)
        score = hit.normalized_score if hit.normalized_score is not None else hit.score
        # 最低质量和主题是两个独立门槛；命中宽泛主题词不能放行零分或低分候选。
        if score is None or not math.isfinite(float(score)) or score <= 0.0 or score < threshold:
            continue
        if topic_keywords:
            text = " ".join(str(value or "") for value in [
                hit.metadata.get("title"),
                hit.metadata.get("category"),
                hit.content,
            ]).lower()
            if not any(keyword in text for keyword in topic_keywords):
                continue
        filtered.append(hit)
    return filtered


def retrieval_minimum_scores() -> Mapping[str, float]:
    return MappingProxyType({
        "milvus": float(os.getenv("RAG_MIN_MILVUS_SCORE", "0.2")),
        "faiss": float(os.getenv("RAG_MIN_FAISS_SCORE", "0.2")),
        "bm25": float(os.getenv("RAG_MIN_BM25_SCORE", "0.2")),
        "keyword": float(os.getenv("RAG_MIN_KEYWORD_SCORE", "0.2")),
    })


def reciprocal_rank_fusion(
    rankings: Sequence[Tuple[str, List[KnowledgeHit]]],
    rrf_k: int,
) -> List[KnowledgeHit]:
    """只融合各后端的相对排名，同时完整保留每一路原始分数。"""

    fused: Dict[str, KnowledgeHit] = {}

    for ranking_name, hits in rankings:
        seen = set()
        for rank, hit in enumerate(hits, start=1):
            chunk_id = str(hit.metadata.get("chunkId") or "")
            if not chunk_id or chunk_id in seen:
                continue
            seen.add(chunk_id)
            if chunk_id not in fused:
                hit.metadata = dict(hit.metadata)
                hit.fusion_score = 0.0
                hit.retrievers = ()
                hit.retriever_scores = {}
                hit.normalized_retriever_scores = {}
                fused[chunk_id] = hit
            target = fused[chunk_id]
            target.fusion_score = float(target.fusion_score or 0.0) + 1.0 / (rrf_k + rank)
            if ranking_name not in target.retrievers:
                target.retrievers = target.retrievers + (ranking_name,)
            if hit.score is not None and math.isfinite(float(hit.score)):
                target.retriever_scores[ranking_name] = float(hit.score)
            if hit.normalized_score is not None and math.isfinite(float(hit.normalized_score)):
                target.normalized_retriever_scores[ranking_name] = float(hit.normalized_score)

    results = list(fused.values())
    for hit in results:
        hit.metadata["retrievers"] = list(hit.retrievers)
        hit.metadata["fusionScore"] = hit.fusion_score
    results.sort(key=_fused_hit_sort_key)
    return results


def finalize_fused_hits(
    hits: List[KnowledgeHit],
    limit: int,
    max_chunks_per_knowledge: int,
    context_character_budget: int,
) -> List[KnowledgeHit]:
    if limit <= 0:
        return []
    bounded: List[KnowledgeHit] = []
    chunk_ids = set()
    knowledge_counts: Dict[object, int] = {}
    for hit in hits:
        chunk_id = hit.metadata.get("chunkId")
        knowledge_id = hit.metadata.get("knowledgeId")
        if not chunk_id or chunk_id in chunk_ids:
            continue
        count = knowledge_counts.get(knowledge_id, 0)
        if count >= max_chunks_per_knowledge:
            continue
        chunk_ids.add(chunk_id)
        knowledge_counts[knowledge_id] = count + 1
        bounded.append(hit)

    merged = merge_adjacent_hits(bounded)
    selected: List[KnowledgeHit] = []
    used_characters = 0
    for hit in sorted(merged, key=_fused_hit_sort_key):
        if len(selected) >= limit:
            break
        if used_characters + len(hit.content) > context_character_budget:
            continue
        selected.append(hit)
        used_characters += len(hit.content)
    return selected


def merge_adjacent_hits(hits: List[KnowledgeHit]) -> List[KnowledgeHit]:
    grouped: Dict[object, List[KnowledgeHit]] = {}
    for hit in hits:
        grouped.setdefault(hit.metadata.get("knowledgeId"), []).append(hit)

    merged: List[KnowledgeHit] = []
    for knowledge_hits in grouped.values():
        ordered = sorted(
            knowledge_hits,
            key=lambda hit: int(hit.metadata.get("chunkIndex") or 0),
        )
        current: List[KnowledgeHit] = []
        previous_index: Optional[int] = None
        for hit in ordered:
            chunk_index = int(hit.metadata.get("chunkIndex") or 0)
            if current and previous_index is not None and chunk_index != previous_index + 1:
                merged.append(_merge_hit_group(current))
                current = []
            current.append(hit)
            previous_index = chunk_index
        if current:
            merged.append(_merge_hit_group(current))
    return merged


def retrieval_candidate_limit(name: str, default: int, final_limit: int) -> int:
    try:
        configured = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        configured = default
    return max(final_limit, min(max(1, configured), 100))


def retrieval_rrf_k() -> int:
    try:
        configured = int(os.getenv("RAG_RRF_K", "60"))
    except (TypeError, ValueError):
        configured = 60
    return min(max(1, configured), 1000)


def retrieval_max_chunks_per_knowledge() -> int:
    try:
        configured = int(os.getenv("RAG_MAX_CHUNKS_PER_KNOWLEDGE", "2"))
    except (TypeError, ValueError):
        configured = 2
    return min(max(1, configured), 10)


def retrieval_context_character_budget() -> int:
    try:
        configured = int(os.getenv("RAG_CONTEXT_MAX_CHARACTERS", "6000"))
    except (TypeError, ValueError):
        configured = 6000
    return min(max(300, configured), 20000)


def _merge_hit_group(hits: List[KnowledgeHit]) -> KnowledgeHit:
    if len(hits) == 1:
        hit = hits[0]
        chunk_id = str(hit.metadata.get("chunkId"))
        chunk_index = int(hit.metadata.get("chunkIndex") or 0)
        hit.metadata["chunkIds"] = [chunk_id]
        hit.metadata["chunkIndexes"] = [chunk_index]
        return hit
    primary = min(hits, key=_fused_hit_sort_key)
    content = hits[0].content
    for hit in hits[1:]:
        content = _merge_overlapping_text(content, hit.content)

    chunk_indexes = sorted({int(hit.metadata.get("chunkIndex") or 0) for hit in hits})
    chunk_ids = [str(hit.metadata.get("chunkId")) for hit in hits]
    contributors = []
    raw_scores: Dict[str, float] = {}
    normalized_scores: Dict[str, float] = {}
    for hit in hits:
        for retriever in hit.retrievers or (hit.retriever,):
            if retriever not in contributors:
                contributors.append(retriever)
        for retriever, score in hit.retriever_scores.items():
            raw_scores[retriever] = max(raw_scores.get(retriever, score), score)
        for retriever, score in hit.normalized_retriever_scores.items():
            normalized_scores[retriever] = max(normalized_scores.get(retriever, score), score)

    metadata = dict(primary.metadata)
    metadata.update({
        "chunkId": chunk_ids[0],
        "chunkIds": chunk_ids,
        "chunkIndex": chunk_indexes[0],
        "chunkIndexes": chunk_indexes,
        "retrievers": contributors,
        "fusionScore": max(float(hit.fusion_score or 0.0) for hit in hits),
    })
    return replace(
        primary,
        content=content,
        metadata=metadata,
        fusion_score=metadata["fusionScore"],
        retrievers=tuple(contributors),
        retriever_scores=raw_scores,
        normalized_retriever_scores=normalized_scores,
    )


def _merge_overlapping_text(left: str, right: str) -> str:
    # chunk overlap 只保留一次；若相邻片段恰好没有公共边界，则使用换行保持语义分隔。
    try:
        configured_overlap = int(os.getenv("RAG_CHUNK_OVERLAP", "40"))
    except (TypeError, ValueError):
        configured_overlap = 40
    maximum = min(len(left), len(right), max(0, configured_overlap))
    for size in range(maximum, 0, -1):
        if left[-size:] == right[:size]:
            return left + right[size:]
    return left + "\n" + right


def _fused_hit_sort_key(hit: KnowledgeHit) -> Tuple[object, ...]:
    return (
        -float(hit.fusion_score or 0.0),
        int(hit.metadata.get("knowledgeId") or 0),
        int(hit.metadata.get("chunkIndex") or 0),
        str(hit.metadata.get("chunkId") or ""),
    )


def rewrite_query(query: str) -> str:
    text = query or ""
    lower = text.lower()
    if any(word in lower for word in ["operation log", "admin log", "操作日志", "预约失败"]):
        return text
    terms = set(query_terms(text))
    business_terms = [word for word in ["工单", "预约", "服务点", "打印", "维修", "快递"] if word in text]
    parts = [text] + business_terms + sorted(term for term in terms if 2 <= len(term) <= 4)[:8]
    return " ".join(part for part in parts if part)


def query_terms(query: str) -> set:
    text = (query or "").lower()
    terms = {term for term in re.split(r"[^a-z0-9]+", text) if len(term) > 2}
    for chunk in cjk_chunks(text):
        max_size = min(4, len(chunk))
        for size in range(2, max_size + 1):
            for start in range(0, len(chunk) - size + 1):
                terms.add(chunk[start:start + size])
    return terms


def cjk_chunks(text: str) -> List[str]:
    chunks = []
    current = []
    for char in text:
        if 0x4E00 <= ord(char) <= 0x9FFF:
            current.append(char)
        elif current:
            chunks.append("".join(current))
            current = []
    if current:
        chunks.append("".join(current))
    return chunks
