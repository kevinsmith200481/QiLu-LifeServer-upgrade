from __future__ import annotations

import hashlib
import json
import math
import os
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Sequence

from langchain_text_splitters import RecursiveCharacterTextSplitter


DEFAULT_CHUNK_SIZE = 300
DEFAULT_CHUNK_OVERLAP = 40
DEFAULT_CHUNK_UNIT = "character"
DEFAULT_CHUNK_ALGORITHM = "recursive-v1"
DEFAULT_INDEX_SCHEMA_VERSION = "rag-chunk-index-v1"
CHUNK_SEPARATORS = ("\n\n", "\n", "。", "！", "？", "；", ". ", "! ", "? ", "; ", " ", "")


class KnowledgeIndexConfigurationError(ValueError):
    pass


class KnowledgeIndexValidationError(ValueError):
    pass


@dataclass(frozen=True)
class ChunkConfig:
    size: int
    overlap: int
    unit: str
    algorithm: str


@dataclass(frozen=True)
class EmbeddingDescriptor:
    provider: str
    model: str
    dimension: int
    algorithm_revision: str
    deployment_revision: str


@dataclass(frozen=True)
class LexicalDescriptor:
    implementation: str
    tokenizer_revision: str
    parameters: str


@dataclass(frozen=True)
class IndexFingerprints:
    embedding_fingerprint: str
    chunk_fingerprint: str
    lexical_fingerprint: str
    index_version: str
    index_schema_version: str


@dataclass(frozen=True)
class KnowledgeChunk:
    """三个检索后端共享的最小不可变检索单元。"""

    chunk_id: str
    knowledge_id: int
    chunk_index: int
    chunk_content: str
    embedding_text: str
    title: str
    category: str
    source: str
    knowledge_version: str
    index_version: str
    chunk_hash: str

    def metadata(self) -> Dict[str, object]:
        return {
            "chunkId": self.chunk_id,
            "knowledgeId": self.knowledge_id,
            "chunkIndex": self.chunk_index,
            "title": self.title,
            "category": self.category,
            "source": self.source,
            "knowledgeVersion": self.knowledge_version,
            "indexVersion": self.index_version,
            "chunkHash": self.chunk_hash,
        }


def chunk_config_from_env() -> ChunkConfig:
    config = ChunkConfig(
        size=_positive_integer("RAG_CHUNK_SIZE", DEFAULT_CHUNK_SIZE),
        overlap=_non_negative_integer("RAG_CHUNK_OVERLAP", DEFAULT_CHUNK_OVERLAP),
        unit=(os.getenv("RAG_CHUNK_UNIT") or DEFAULT_CHUNK_UNIT).strip(),
        algorithm=(os.getenv("RAG_CHUNK_ALGORITHM") or DEFAULT_CHUNK_ALGORITHM).strip(),
    )
    _validate_chunk_config(config)
    return config


def _validate_chunk_config(config: ChunkConfig) -> None:
    # 切分配置既可能来自环境变量，也可能由测试或内部调用直接构造，因此在指纹入口统一校验。
    if type(config.size) is not int or config.size <= 0:
        raise KnowledgeIndexConfigurationError("RAG_CHUNK_SIZE must be a positive integer")
    if type(config.overlap) is not int or config.overlap < 0:
        raise KnowledgeIndexConfigurationError("RAG_CHUNK_OVERLAP must be a non-negative integer")
    if config.overlap >= config.size:
        raise KnowledgeIndexConfigurationError("RAG_CHUNK_OVERLAP must be smaller than RAG_CHUNK_SIZE")
    if config.unit != DEFAULT_CHUNK_UNIT:
        raise KnowledgeIndexConfigurationError("RAG_CHUNK_UNIT must be character")
    if not config.algorithm:
        raise KnowledgeIndexConfigurationError("RAG_CHUNK_ALGORITHM must not be empty")


def lexical_descriptor_from_env() -> LexicalDescriptor:
    # 保留阶段 B 的公开入口；实际配置解析统一委托给 BM25 模块，避免指纹与运行参数分叉。
    from rag.lexical import lexical_config_from_env

    return lexical_config_from_env().descriptor()


def build_index_fingerprints(
    knowledge_version: str,
    embedding: EmbeddingDescriptor,
    chunk: ChunkConfig,
    lexical: LexicalDescriptor,
    index_schema_version: str = DEFAULT_INDEX_SCHEMA_VERSION,
) -> IndexFingerprints:
    if not knowledge_version or not knowledge_version.strip():
        raise KnowledgeIndexValidationError("knowledgeVersion must not be empty")
    _validate_chunk_config(chunk)
    embedding_fields = (
        embedding.provider,
        embedding.model,
        embedding.algorithm_revision,
        embedding.deployment_revision,
    )
    if any(not isinstance(value, str) or not value.strip() for value in embedding_fields):
        raise KnowledgeIndexConfigurationError("embedding fingerprint fields must not be empty")
    if type(embedding.dimension) is not int or embedding.dimension <= 0 or embedding.dimension > 65536:
        raise KnowledgeIndexConfigurationError("embedding dimension must be between 1 and 65536")
    lexical_fields = (lexical.implementation, lexical.tokenizer_revision, lexical.parameters)
    if any(not isinstance(value, str) or not value.strip() for value in lexical_fields):
        raise KnowledgeIndexConfigurationError("lexical fingerprint fields must not be empty")
    if not isinstance(index_schema_version, str) or not index_schema_version.strip():
        raise KnowledgeIndexConfigurationError("index schema version must not be empty")

    # 每类实现身份先独立取指纹，再共同决定 indexVersion，避免同维度模型或切分参数被误判兼容。
    embedding_fingerprint = stable_fingerprint({
        "provider": embedding.provider,
        "model": embedding.model,
        "dimension": embedding.dimension,
        "algorithmRevision": embedding.algorithm_revision,
        "deploymentRevision": embedding.deployment_revision,
    })
    chunk_fingerprint = stable_fingerprint({
        "chunkSize": chunk.size,
        "chunkOverlap": chunk.overlap,
        "chunkUnit": chunk.unit,
        "chunkAlgorithm": chunk.algorithm,
        "separators": list(CHUNK_SEPARATORS),
    })
    lexical_fingerprint = stable_fingerprint({
        "tokenizerRevision": lexical.tokenizer_revision,
        "implementation": lexical.implementation,
        "parameters": lexical.parameters,
    })
    index_version = stable_fingerprint({
        "knowledgeVersion": knowledge_version,
        "embeddingFingerprint": embedding_fingerprint,
        "chunkFingerprint": chunk_fingerprint,
        "lexicalFingerprint": lexical_fingerprint,
        "indexSchemaVersion": index_schema_version,
    })
    return IndexFingerprints(
        embedding_fingerprint=embedding_fingerprint,
        chunk_fingerprint=chunk_fingerprint,
        lexical_fingerprint=lexical_fingerprint,
        index_version=index_version,
        index_schema_version=index_schema_version,
    )


def build_knowledge_chunks(
    documents: Sequence[Any],
    knowledge_version: str,
    index_version: str,
    config: ChunkConfig,
    require_formal_ids: bool,
) -> List[KnowledgeChunk]:
    knowledge_ids = _knowledge_ids(documents, require_formal_ids)
    # 只切正文；标题、分类、来源和版本作为元数据继承，不能改变正文的 300/40 边界。
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=config.size,
        chunk_overlap=config.overlap,
        separators=list(CHUNK_SEPARATORS),
        keep_separator="end",
        length_function=len,
    )
    chunks: List[KnowledgeChunk] = []
    for document_position, (document, knowledge_id) in enumerate(zip(documents, knowledge_ids)):
        title = _required_text(getattr(document, "title", None), "title", document_position)
        content = _required_text(getattr(document, "content", None), "content", document_position)
        category = _required_text(getattr(document, "category", None) or "general", "category", document_position)
        source = _required_text(getattr(document, "source", None) or "ai_knowledge", "source", document_position)
        seen_content = set()
        document_chunks = []
        for raw_content in splitter.split_text(content):
            chunk_content = raw_content.strip()
            if not chunk_content or chunk_content in seen_content:
                continue
            seen_content.add(chunk_content)
            document_chunks.append(chunk_content)
        if not document_chunks:
            raise KnowledgeIndexValidationError(f"document[{document_position}] produced no usable chunks")

        for chunk_index, chunk_content in enumerate(document_chunks):
            embedding_text = "\n".join((title, category, chunk_content))
            chunk_hash = sha256_text(chunk_content)
            chunk_id = sha256_text(f"{knowledge_id}\0{chunk_index}\0{chunk_content}")
            chunk = KnowledgeChunk(
                chunk_id=chunk_id,
                knowledge_id=knowledge_id,
                chunk_index=chunk_index,
                chunk_content=chunk_content,
                embedding_text=embedding_text,
                title=title,
                category=category,
                source=source,
                knowledge_version=knowledge_version,
                index_version=index_version,
                chunk_hash=chunk_hash,
            )
            validate_milvus_chunk(chunk)
            chunks.append(chunk)
    return chunks


def validate_embedding_vectors(
    vectors: Iterable[Iterable[float]],
    expected_count: int,
    expected_dimension: int,
) -> List[List[float]]:
    # 在候选激活或查询前统一验证返回形状与数值，任何异常都不能进入 FAISS/Milvus。
    try:
        normalized = [list(vector) for vector in vectors]
    except TypeError as exc:
        raise KnowledgeIndexValidationError("embedding response must be a batch of vectors") from exc
    if len(normalized) != expected_count:
        raise KnowledgeIndexValidationError(
            f"embedding batch count mismatch: expected={expected_count}, actual={len(normalized)}"
        )
    for vector_index, vector in enumerate(normalized):
        if len(vector) != expected_dimension:
            raise KnowledgeIndexValidationError(
                f"embedding dimension mismatch at index {vector_index}: "
                f"expected={expected_dimension}, actual={len(vector)}"
            )
        for value in vector:
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
                raise KnowledgeIndexValidationError(
                    f"embedding contains a non-finite numeric value at index {vector_index}"
                )
    return [[float(value) for value in vector] for vector in normalized]


def validate_milvus_chunk(chunk: KnowledgeChunk) -> None:
    # 所有字符串在调用 Milvus 前显式校验，禁止向量使用完整文本而返回正文被静默截断。
    limits = {
        "chunkId": (chunk.chunk_id, 80),
        "title": (chunk.title, 512),
        "category": (chunk.category, 128),
        "source": (chunk.source, 512),
        "chunkContent": (chunk.chunk_content, 4096),
        "knowledgeVersion": (chunk.knowledge_version, 128),
        "indexVersion": (chunk.index_version, 128),
        "chunkHash": (chunk.chunk_hash, 80),
    }
    for field_name, (value, maximum) in limits.items():
        if not value or len(value) > maximum:
            raise KnowledgeIndexValidationError(
                f"{field_name} length must be between 1 and {maximum}, actual={len(value)}"
            )


def stable_fingerprint(payload: Dict[str, object]) -> str:
    serialized = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return "sha256:" + sha256_text(serialized)


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _knowledge_ids(documents: Sequence[Any], require_formal_ids: bool) -> List[int]:
    ids = []
    seen = set()
    for position, document in enumerate(documents):
        value = getattr(document, "id", None)
        valid = isinstance(value, int) and not isinstance(value, bool) and value > 0
        if require_formal_ids and not valid:
            raise KnowledgeIndexValidationError(f"document[{position}] requires a positive integer knowledgeId")
        knowledge_id = value if valid else position + 1
        if require_formal_ids and knowledge_id in seen:
            raise KnowledgeIndexValidationError(f"duplicate knowledgeId: {knowledge_id}")
        seen.add(knowledge_id)
        ids.append(knowledge_id)
    return ids


def _required_text(value: object, field_name: str, position: int) -> str:
    if not isinstance(value, str) or not value.strip():
        raise KnowledgeIndexValidationError(f"document[{position}].{field_name} must not be empty")
    return value.strip()


def _positive_integer(name: str, default: int) -> int:
    value = _integer(name, default)
    if value <= 0:
        raise KnowledgeIndexConfigurationError(f"{name} must be a positive integer")
    return value


def _non_negative_integer(name: str, default: int) -> int:
    value = _integer(name, default)
    if value < 0:
        raise KnowledgeIndexConfigurationError(f"{name} must be a non-negative integer")
    return value


def _integer(name: str, default: int) -> int:
    raw_value = os.getenv(name)
    try:
        return default if raw_value is None or not raw_value.strip() else int(raw_value)
    except (TypeError, ValueError) as exc:
        raise KnowledgeIndexConfigurationError(f"{name} must be an integer") from exc
