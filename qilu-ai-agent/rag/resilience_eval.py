from __future__ import annotations

import ctypes
import gc
import json
import math
import os
import subprocess
import threading
import time
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Dict, List, Mapping, Sequence
from unittest.mock import patch

from rag.quality_eval import _percentile
from rag.quality_fixture import build_quality_fixture
from rag.retriever import CampusKnowledgeRetriever, KnowledgeDocument, connections, utility


BASELINE_P95_MAXIMUM_MS = 0.126
LOCAL_SYNC_100_MAXIMUM_MS = 500.0
EXTERNAL_SYNC_100_MAXIMUM_MS = 45000.0
ACTIVE_POINTER_MAXIMUM_MS = 100.0


@dataclass(frozen=True)
class ProcessResources:
    handleCount: int
    threadCount: int
    tcpSocketCount: int
    workingSetBytes: int
    temporaryEntryCount: int


def run_local_performance(baseline_path: Path) -> Dict[str, object]:
    fixture = json.loads(baseline_path.read_text(encoding="utf-8"))
    documents = [_baseline_document(item) for item in fixture["fixtures"]]
    queries = [str(item["query"]) for item in fixture["fixtures"]]
    answers = [str(item["answer"]) for item in fixture["fixtures"]]

    retriever = CampusKnowledgeRetriever()
    retriever.reload_documents(documents, "rag-stage-f-baseline", "ai_knowledge")
    for index in range(20):
        retriever.retrieve_documents(queries[index % len(queries)], limit=3)
    # 预热会创建短生命周期候选对象；采样前统一回收，避免 GC 恰好落入亚毫秒 P95 窗口造成随机门禁结果。
    gc.collect()

    latencies: List[float] = []
    answer_hits = 0
    for index in range(200):
        fixture_index = index % len(queries)
        started = time.perf_counter()
        hits = retriever.retrieve_documents(queries[fixture_index], limit=3)
        latencies.append((time.perf_counter() - started) * 1000.0)
        if any(answers[fixture_index] in hit.content for hit in hits):
            answer_hits += 1

    result = {
        "sampleCount": len(latencies),
        "warmupCount": 20,
        "topK": 3,
        "p50LatencyMs": round(_percentile(latencies, 0.50), 3),
        "p95LatencyMs": round(_percentile(latencies, 0.95), 3),
        "averageLatencyMs": round(sum(latencies) / len(latencies), 3),
        "maximumLatencyMs": round(max(latencies), 3),
        "answerHitCount": answer_hits,
    }
    result["passed"] = (
        result["p95LatencyMs"] <= BASELINE_P95_MAXIMUM_MS
        and result["p95LatencyMs"] <= 500.0
        and answer_hits == len(latencies)
    )
    return result


def run_local_sync_and_pointer() -> Dict[str, object]:
    documents = _sync_documents(100, "local")
    retriever = CampusKnowledgeRetriever()
    pointer_durations: List[float] = []
    original_setter = retriever._set_snapshot_fields

    def timed_setter(snapshot) -> None:
        started = time.perf_counter()
        original_setter(snapshot)
        pointer_durations.append((time.perf_counter() - started) * 1000.0)

    started = time.perf_counter()
    with patch.object(retriever, "_set_snapshot_fields", side_effect=timed_setter):
        reload_result = retriever.reload_documents(
            documents,
            "rag-stage-f-local-sync-100",
            "ai_knowledge",
        )
    duration_ms = (time.perf_counter() - started) * 1000.0
    snapshot = retriever._capture_active_snapshot()
    pointer_maximum = max(pointer_durations, default=math.inf)
    result = {
        "documentCount": len(documents),
        "chunkCount": len(snapshot.chunks),
        "durationMs": round(duration_ms, 3),
        "activePointerMaximumMs": round(pointer_maximum, 3),
        "backendStates": dict(snapshot.backend_states),
    }
    result["passed"] = (
        reload_result.success
        and len(snapshot.documents) == 100
        and duration_ms <= LOCAL_SYNC_100_MAXIMUM_MS
        and pointer_maximum <= ACTIVE_POINTER_MAXIMUM_MS
    )
    return result


def run_external_sync_100(
    collection_prefix: str,
    maximum_ms: float = EXTERNAL_SYNC_100_MAXIMUM_MS,
) -> Dict[str, object]:
    """使用当前外部 Embedding 配置和真实 Milvus 执行正式 100 文档同步门禁。"""

    documents = _sync_documents(100, "external")
    retriever = CampusKnowledgeRetriever()
    reload_result = None
    duration_ms = math.inf
    snapshot = None
    cleanup_passed = False
    try:
        started = time.perf_counter()
        reload_result = retriever.reload_documents(
            documents,
            "rag-stage-f-external-sync-100",
            "ai_knowledge",
        )
        duration_ms = (time.perf_counter() - started) * 1000.0
        snapshot = retriever._capture_active_snapshot()
    finally:
        # 性能验收使用独立前缀；无论同步成功与否都清理 candidate、alias 和物理 Collection。
        retriever.milvus.clear()
        retriever.milvus.cleanup_superseded(force=True)
        cleanup_passed = _drop_scoped_collections(retriever, collection_prefix)

    backend_states = dict(snapshot.backend_states) if snapshot is not None else {}
    result = {
        "documentCount": len(documents),
        "chunkCount": len(snapshot.chunks) if snapshot is not None else 0,
        "durationMs": round(duration_ms, 3),
        "maximumMs": float(maximum_ms),
        "embedding": asdict(retriever.embedding_descriptor),
        "backendStates": backend_states,
        "liveMilvusUsed": backend_states.get("milvus") == "READY",
        "cleanupPassed": cleanup_passed,
    }
    result["passed"] = (
        reload_result is not None
        and reload_result.success
        and reload_result.activated
        and snapshot is not None
        and len(snapshot.documents) == 100
        and duration_ms <= maximum_ms
        and result["liveMilvusUsed"]
        and cleanup_passed
    )
    return result


def run_twenty_live_reloads(runtime_directory: Path, collection_prefix: str) -> Dict[str, object]:
    runtime_directory.mkdir(parents=True, exist_ok=True)
    documents, _ = build_quality_fixture()
    v1 = [replace(document, content=document.content + " RESOURCE-V1") for document in documents]
    v2 = [replace(document, content=document.content + " RESOURCE-V2") for document in documents]
    retriever = CampusKnowledgeRetriever()
    retriever.reload_documents(v1, "resource-v1-seed", "ai_knowledge")
    before = capture_process_resources(runtime_directory)
    iterations: List[Dict[str, object]] = []
    cleanup_passed = False

    try:
        for index in range(20):
            version = "resource-v1" if index % 2 == 0 else "resource-v2"
            candidate = v1 if index % 2 == 0 else v2
            started = time.perf_counter()
            reload_result = retriever.reload_documents(candidate, version, "ai_knowledge")
            retriever.milvus.cleanup_superseded(force=True)
            iterations.append({
                "iteration": index + 1,
                "knowledgeVersion": version,
                "durationMs": round((time.perf_counter() - started) * 1000.0, 3),
                "activated": reload_result.activated,
                "degraded": reload_result.degraded,
                "collectionCount": len(_scoped_collections(retriever, collection_prefix)),
            })
        gc.collect()
        after = capture_process_resources(runtime_directory)
    finally:
        retriever.milvus.clear()
        retriever.milvus.cleanup_superseded(force=True)
        cleanup_passed = _drop_scoped_collections(retriever, collection_prefix)

    growth = {
        "handleCount": after.handleCount - before.handleCount,
        "threadCount": after.threadCount - before.threadCount,
        "tcpSocketCount": after.tcpSocketCount - before.tcpSocketCount,
        "workingSetBytes": after.workingSetBytes - before.workingSetBytes,
        "temporaryEntryCount": after.temporaryEntryCount - before.temporaryEntryCount,
    }
    gates = {
        "twentyReloadsCompleted": len(iterations) == 20,
        "allActivatedWithoutDegradation": all(
            item["activated"] and not item["degraded"] for item in iterations
        ),
        "handleCountStable": growth["handleCount"] <= 16,
        "threadCountStable": growth["threadCount"] <= 2,
        "tcpSocketCountStable": growth["tcpSocketCount"] <= 4,
        "temporaryEntriesStable": growth["temporaryEntryCount"] == 0,
        "collectionCountBounded": max(item["collectionCount"] for item in iterations) <= 2,
        "collectionCleanup": cleanup_passed,
    }
    return {
        "reloadCount": len(iterations),
        "before": asdict(before),
        "after": asdict(after),
        "growth": growth,
        "iterations": iterations,
        "gates": gates,
        "passed": all(gates.values()),
    }


def capture_process_resources(runtime_directory: Path) -> ProcessResources:
    return ProcessResources(
        handleCount=_windows_handle_count(),
        threadCount=_windows_thread_count(),
        tcpSocketCount=_tcp_socket_count(),
        workingSetBytes=_windows_working_set(),
        temporaryEntryCount=sum(1 for path in runtime_directory.rglob("*") if path.exists()),
    )


def _baseline_document(item: Mapping[str, object]) -> KnowledgeDocument:
    content = (
        str(item["prefix"]) * int(item["prefixRepeat"])
        + str(item["answer"])
        + str(item["suffix"]) * int(item["suffixRepeat"])
    )
    return KnowledgeDocument(
        id=int(item["knowledgeId"]),
        title=str(item["title"]),
        content=content,
        keywords=[str(item["query"])],
        category=str(item["category"]),
        source="ai_knowledge",
    )


def _sync_documents(count: int, marker: str) -> List[KnowledgeDocument]:
    return [
        KnowledgeDocument(
            id=880000 + index,
            title=f"同步性能知识 {index:03d}",
            content=f"这是第 {index:03d} 条隔离同步知识，唯一标记 SYNC-{marker}-{index:03d}。",
            keywords=[f"SYNC-{marker}-{index:03d}"],
            category="stage-f-sync",
            source="ai_knowledge",
        )
        for index in range(count)
    ]


def _scoped_collections(retriever: CampusKnowledgeRetriever, prefix: str) -> List[str]:
    if not retriever.milvus.connect():
        return []
    return [
        name for name in utility.list_collections(using=retriever.milvus.alias)
        if name == prefix or name.startswith(prefix + "__")
    ]


def _drop_scoped_collections(retriever: CampusKnowledgeRetriever, prefix: str) -> bool:
    try:
        for name in _scoped_collections(retriever, prefix):
            utility.drop_collection(name, using=retriever.milvus.alias)
        remaining = _scoped_collections(retriever, prefix)
        if connections is not None:
            connections.disconnect(retriever.milvus.alias)
        return not remaining
    except Exception:
        return False


def _windows_handle_count() -> int:
    kernel32 = ctypes.windll.kernel32
    kernel32.GetCurrentProcess.restype = ctypes.c_void_p
    kernel32.GetProcessHandleCount.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
    kernel32.GetProcessHandleCount.restype = ctypes.c_int
    count = ctypes.c_ulong()
    process = kernel32.GetCurrentProcess()
    if not kernel32.GetProcessHandleCount(process, ctypes.byref(count)):
        raise OSError("GetProcessHandleCount failed")
    return int(count.value)


def _windows_working_set() -> int:
    class ProcessMemoryCounters(ctypes.Structure):
        _fields_ = [
            ("cb", ctypes.c_ulong),
            ("PageFaultCount", ctypes.c_ulong),
            ("PeakWorkingSetSize", ctypes.c_size_t),
            ("WorkingSetSize", ctypes.c_size_t),
            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
            ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
            ("PagefileUsage", ctypes.c_size_t),
            ("PeakPagefileUsage", ctypes.c_size_t),
        ]

    counters = ProcessMemoryCounters()
    counters.cb = ctypes.sizeof(counters)
    kernel32 = ctypes.windll.kernel32
    psapi = ctypes.windll.psapi
    kernel32.GetCurrentProcess.restype = ctypes.c_void_p
    psapi.GetProcessMemoryInfo.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_ulong]
    psapi.GetProcessMemoryInfo.restype = ctypes.c_int
    process = kernel32.GetCurrentProcess()
    if not psapi.GetProcessMemoryInfo(process, ctypes.byref(counters), counters.cb):
        raise OSError("GetProcessMemoryInfo failed")
    return int(counters.WorkingSetSize)


def _windows_thread_count() -> int:
    # Toolhelp 快照统计进程拥有的操作系统线程，避免只观察 Python threading 对象。
    class ThreadEntry32(ctypes.Structure):
        _fields_ = [
            ("dwSize", ctypes.c_ulong),
            ("cntUsage", ctypes.c_ulong),
            ("th32ThreadID", ctypes.c_ulong),
            ("th32OwnerProcessID", ctypes.c_ulong),
            ("tpBasePri", ctypes.c_long),
            ("tpDeltaPri", ctypes.c_long),
            ("dwFlags", ctypes.c_ulong),
        ]

    kernel32 = ctypes.windll.kernel32
    kernel32.CreateToolhelp32Snapshot.argtypes = [ctypes.c_ulong, ctypes.c_ulong]
    kernel32.CreateToolhelp32Snapshot.restype = ctypes.c_void_p
    kernel32.Thread32First.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    kernel32.Thread32Next.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
    snapshot = kernel32.CreateToolhelp32Snapshot(0x00000004, 0)
    if snapshot == ctypes.c_void_p(-1).value:
        raise OSError("CreateToolhelp32Snapshot failed")
    entry = ThreadEntry32()
    entry.dwSize = ctypes.sizeof(entry)
    process_id = os.getpid()
    count = 0
    try:
        success = kernel32.Thread32First(snapshot, ctypes.byref(entry))
        while success:
            if int(entry.th32OwnerProcessID) == process_id:
                count += 1
            success = kernel32.Thread32Next(snapshot, ctypes.byref(entry))
    finally:
        kernel32.CloseHandle(snapshot)
    return count or threading.active_count()


def _tcp_socket_count() -> int:
    completed = subprocess.run(
        ["netstat", "-ano", "-p", "tcp"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    process_id = str(os.getpid())
    return sum(
        1 for line in completed.stdout.splitlines()
        if line.strip().startswith("TCP") and line.split()[-1:] == [process_id]
    )
