from __future__ import annotations

import json
import math
import os
import re
import threading
from collections import Counter, OrderedDict
from dataclasses import dataclass
from typing import Dict, List, Mapping, Sequence, Tuple

from rag.chunking import (
    KnowledgeChunk,
    KnowledgeIndexConfigurationError,
    LexicalDescriptor,
)


DEFAULT_LEXICAL_IMPLEMENTATION = "okapi-bm25-v1"
DEFAULT_TOKENIZER_REVISION = "alnum-cjk-2-4-v1"
DEFAULT_BM25_K1 = 1.5
DEFAULT_BM25_B = 0.75
DEFAULT_TITLE_WEIGHT = 2.0
DEFAULT_CATEGORY_WEIGHT = 1.5
DEFAULT_KEYWORD_WEIGHT = 2.0
_ALNUM_PATTERN = re.compile(r"[a-z0-9]+")


@dataclass(frozen=True)
class LexicalConfig:
    implementation: str
    tokenizer_revision: str
    k1: float
    b: float
    title_weight: float
    category_weight: float
    keyword_weight: float

    def descriptor(self) -> LexicalDescriptor:
        # 指纹参数直接由实际运行配置生成，禁止用任意字符串冒充 BM25 的真实参数。
        parameters = json.dumps(
            {
                "b": self.b,
                "categoryWeight": self.category_weight,
                "k1": self.k1,
                "keywordWeight": self.keyword_weight,
                "scoreNormalization": "query-idf-coverage-v1",
                "titleWeight": self.title_weight,
            },
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        return LexicalDescriptor(
            implementation=self.implementation,
            tokenizer_revision=self.tokenizer_revision,
            parameters=parameters,
        )


@dataclass(frozen=True, slots=True)
class LexicalMatch:
    chunk: KnowledgeChunk
    score: float
    normalized_score: float


class BM25LexicalIndex:
    """针对统一 KnowledgeChunk 的确定性 Okapi BM25 查询投影。"""

    def __init__(
        self,
        chunks: Sequence[KnowledgeChunk],
        document_keywords: Mapping[int, Sequence[str]],
        config: LexicalConfig,
    ) -> None:
        self.config = config
        self.chunks: Tuple[KnowledgeChunk, ...] = tuple(chunks)
        self._term_frequencies: List[Counter[str]] = []
        self._document_lengths: List[float] = []
        document_frequencies: Counter[str] = Counter()

        for chunk in self.chunks:
            frequencies = self._weighted_frequencies(
                chunk,
                document_keywords.get(chunk.knowledge_id, ()),
            )
            self._term_frequencies.append(frequencies)
            document_length = sum(frequencies.values())
            self._document_lengths.append(document_length)
            document_frequencies.update(frequencies.keys())

        count = len(self.chunks)
        self._average_document_length = (
            sum(self._document_lengths) / count if count else 0.0
        )
        # 使用 Robertson/Sparck Jones 的正值 IDF 变体，稀有精确词不会被常见词淹没。
        self._inverse_document_frequencies = {
            term: math.log(1.0 + (count - frequency + 0.5) / (frequency + 0.5))
            for term, frequency in document_frequencies.items()
        }
        # 索引发布后正文与参数均不可变，因此可安全复用相同问题的排序结果；
        # 有界 LRU 防止长期运行时由不同用户问题造成内存无上限增长。
        self._query_cache: OrderedDict[
            Tuple[str, int], Tuple[LexicalMatch, ...]
        ] = OrderedDict()
        self._query_cache_lock = threading.Lock()
        self._query_cache_capacity = 256

    def search(self, query: str, limit: int) -> List[LexicalMatch]:
        if limit <= 0 or not self.chunks:
            return []
        cache_key = (query, limit)
        with self._query_cache_lock:
            cached = self._query_cache.get(cache_key)
            if cached is not None:
                self._query_cache.move_to_end(cache_key)
                # 每次返回独立列表，调用方删除或排序结果时不会污染后续查询。
                return list(cached)

        query_tokens = sorted(set(tokenize_lexical(query)))
        if not query_tokens:
            return self._cache_result(cache_key, ())

        scored: List[Tuple[float, float, KnowledgeChunk]] = []
        average_length = self._average_document_length or 1.0
        # 未在正式语料出现的 query token 按最大 IDF 计入分母，使只命中一个常见片段的
        # 新奇问题获得低置信度；原始 BM25 分数仍只负责候选排序。
        unknown_idf = math.log(1.0 + (len(self.chunks) + 0.5) / 0.5)
        query_weight = sum(
            self._inverse_document_frequencies.get(token, unknown_idf)
            for token in query_tokens
        ) or 1.0
        for chunk, frequencies, document_length in zip(
            self.chunks,
            self._term_frequencies,
            self._document_lengths,
        ):
            score = 0.0
            matched_query_weight = 0.0
            length_ratio = document_length / average_length
            for token in query_tokens:
                term_frequency = frequencies.get(token, 0.0)
                if term_frequency <= 0.0:
                    continue
                matched_query_weight += self._inverse_document_frequencies[token]
                denominator = term_frequency + self.config.k1 * (
                    1.0 - self.config.b + self.config.b * length_ratio
                )
                score += self._inverse_document_frequencies[token] * (
                    term_frequency * (self.config.k1 + 1.0) / denominator
                )
            if score > 0.0 and math.isfinite(score):
                scored.append((score, matched_query_weight / query_weight, chunk))

        scored.sort(key=lambda item: (-item[0], item[2].knowledge_id, item[2].chunk_index))
        matches = tuple(
            LexicalMatch(
                chunk=chunk,
                score=score,
                normalized_score=normalized_score,
            )
            for score, normalized_score, chunk in scored[:limit]
        )
        return self._cache_result(cache_key, matches)

    def _cache_result(
        self,
        cache_key: Tuple[str, int],
        matches: Tuple[LexicalMatch, ...],
    ) -> List[LexicalMatch]:
        """原子写入有界 LRU，并返回不共享容器的查询结果。"""

        with self._query_cache_lock:
            self._query_cache[cache_key] = matches
            self._query_cache.move_to_end(cache_key)
            while len(self._query_cache) > self._query_cache_capacity:
                self._query_cache.popitem(last=False)
        return list(matches)

    def _weighted_frequencies(
        self,
        chunk: KnowledgeChunk,
        keywords: Sequence[str],
    ) -> Counter[str]:
        # 正文保持标准权重；标题、分类和正式关键词只增加词频，不改变 chunk 正文边界。
        frequencies: Counter[str] = Counter(tokenize_lexical(chunk.chunk_content))
        _add_weighted_tokens(frequencies, tokenize_lexical(chunk.title), self.config.title_weight)
        _add_weighted_tokens(frequencies, tokenize_lexical(chunk.category), self.config.category_weight)
        for keyword in keywords:
            _add_weighted_tokens(
                frequencies,
                tokenize_lexical(keyword),
                self.config.keyword_weight,
            )
        return frequencies


def lexical_config_from_env() -> LexicalConfig:
    implementation = (
        os.getenv("RAG_LEXICAL_IMPLEMENTATION") or DEFAULT_LEXICAL_IMPLEMENTATION
    ).strip()
    tokenizer_revision = (
        os.getenv("RAG_TOKENIZER_REVISION") or DEFAULT_TOKENIZER_REVISION
    ).strip()
    if not implementation or not tokenizer_revision:
        raise KnowledgeIndexConfigurationError("lexical implementation and tokenizer revision must not be empty")
    if implementation != DEFAULT_LEXICAL_IMPLEMENTATION:
        raise KnowledgeIndexConfigurationError(
            f"RAG_LEXICAL_IMPLEMENTATION must be {DEFAULT_LEXICAL_IMPLEMENTATION}"
        )
    if tokenizer_revision != DEFAULT_TOKENIZER_REVISION:
        raise KnowledgeIndexConfigurationError(
            f"RAG_TOKENIZER_REVISION must be {DEFAULT_TOKENIZER_REVISION}"
        )
    config = LexicalConfig(
        implementation=implementation,
        tokenizer_revision=tokenizer_revision,
        k1=_positive_float("RAG_BM25_K1", DEFAULT_BM25_K1),
        b=_bounded_float("RAG_BM25_B", DEFAULT_BM25_B, 0.0, 1.0),
        title_weight=_positive_float("RAG_BM25_TITLE_WEIGHT", DEFAULT_TITLE_WEIGHT),
        category_weight=_positive_float("RAG_BM25_CATEGORY_WEIGHT", DEFAULT_CATEGORY_WEIGHT),
        keyword_weight=_positive_float("RAG_BM25_KEYWORD_WEIGHT", DEFAULT_KEYWORD_WEIGHT),
    )
    return config


def tokenize_lexical(text: str) -> List[str]:
    """英文按字母数字词元切分，中文按稳定的 2-4 字滑动片段切分。"""

    normalized = (text or "").lower()
    tokens = _ALNUM_PATTERN.findall(normalized)
    for run in _cjk_runs(normalized):
        if len(run) == 1:
            tokens.append(run)
            continue
        for size in range(2, min(4, len(run)) + 1):
            tokens.extend(run[start:start + size] for start in range(len(run) - size + 1))
    return tokens


def _add_weighted_tokens(
    frequencies: Counter[str],
    tokens: Sequence[str],
    weight: float,
) -> None:
    for token, count in Counter(tokens).items():
        frequencies[token] += count * weight


def _cjk_runs(text: str) -> List[str]:
    runs: List[str] = []
    current: List[str] = []
    for character in text:
        if "\u4e00" <= character <= "\u9fff":
            current.append(character)
        elif current:
            runs.append("".join(current))
            current = []
    if current:
        runs.append("".join(current))
    return runs


def _positive_float(name: str, default: float) -> float:
    value = _float(name, default)
    if value <= 0.0:
        raise KnowledgeIndexConfigurationError(f"{name} must be greater than zero")
    return value


def _bounded_float(name: str, default: float, minimum: float, maximum: float) -> float:
    value = _float(name, default)
    if value < minimum or value > maximum:
        raise KnowledgeIndexConfigurationError(f"{name} must be between {minimum} and {maximum}")
    return value


def _float(name: str, default: float) -> float:
    raw_value = os.getenv(name)
    try:
        value = default if raw_value is None or not raw_value.strip() else float(raw_value)
    except (TypeError, ValueError) as exc:
        raise KnowledgeIndexConfigurationError(f"{name} must be a number") from exc
    if not math.isfinite(value):
        raise KnowledgeIndexConfigurationError(f"{name} must be finite")
    return value
