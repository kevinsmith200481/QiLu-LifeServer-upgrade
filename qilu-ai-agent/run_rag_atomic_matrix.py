from __future__ import annotations

import argparse
import io
import json
import time
import unittest
from collections import OrderedDict
from pathlib import Path
from typing import Dict, List


# RC-U26 至 RC-U28 共享同一个参数化故障测试；一次执行会覆盖 insert/flush、建索引/加载/探针和 alias 三类失败。
CASE_TESTS = OrderedDict([
    ("RC-U25", "test_rc_u25_embedding_failure_keeps_v1_active"),
    ("RC-U26", "test_rc_u26_to_u28_candidate_and_alias_failures_keep_v1_active"),
    ("RC-U27", "test_rc_u26_to_u28_candidate_and_alias_failures_keep_v1_active"),
    ("RC-U28", "test_rc_u26_to_u28_candidate_and_alias_failures_keep_v1_active"),
    ("RC-U29", "test_rc_u29_required_faiss_or_bm25_failure_rejects_activation"),
    ("RC-U30", "test_rc_u30_optional_milvus_failure_activates_degraded_snapshot"),
    ("RC-U31", "test_rc_u31_same_index_version_is_idempotent"),
    ("RC-U32", "test_rc_u32_two_concurrent_reloads_are_single_writer"),
    ("RC-U33", "test_rc_u33_fifty_queries_see_complete_v1_or_v2_snapshot"),
    ("RC-U34", "test_rc_u34_cleanup_failure_does_not_roll_back_v2"),
    ("RC-U35", "test_rc_u35_empty_sync_activates_explicit_empty_snapshot"),
])


def main() -> int:
    parser = argparse.ArgumentParser(description="Run structured RC-U25 to RC-U35 atomic reload matrix.")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    test_results: Dict[str, Dict[str, object]] = {}
    for test_name in OrderedDict.fromkeys(CASE_TESTS.values()):
        test_results[test_name] = _run_test(test_name)

    cases: List[Dict[str, object]] = []
    for case_id, test_name in CASE_TESTS.items():
        evidence = test_results[test_name]
        cases.append({
            "id": case_id,
            "testId": evidence["testId"],
            "durationMs": evidence["durationMs"],
            "testsRun": evidence["testsRun"],
            "failureCount": evidence["failureCount"],
            "errorCount": evidence["errorCount"],
            "skippedCount": evidence["skippedCount"],
            "sharedEvidence": sum(1 for value in CASE_TESTS.values() if value == test_name) > 1,
            "passed": evidence["passed"],
        })

    result = {
        "schemaVersion": 1,
        "scope": "RC-U25-RC-U35",
        "caseCount": len(cases),
        "passedCount": sum(1 for case in cases if case["passed"]),
        "failedCount": sum(1 for case in cases if not case["passed"]),
        "uniqueTestCount": len(test_results),
        "cases": cases,
    }
    result["passed"] = result["caseCount"] == 11 and result["failedCount"] == 0
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 1


def _run_test(test_name: str) -> Dict[str, object]:
    test_id = f"test_rag_atomic_reload.RagAtomicReloadTest.{test_name}"
    suite = unittest.defaultTestLoader.loadTestsFromName(test_id)
    stream = io.StringIO()
    started = time.perf_counter()
    result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    return {
        "testId": test_id,
        "durationMs": round((time.perf_counter() - started) * 1000.0, 3),
        "testsRun": result.testsRun,
        "failureCount": len(result.failures),
        "errorCount": len(result.errors),
        "skippedCount": len(result.skipped),
        "passed": result.wasSuccessful() and result.testsRun == 1 and not result.skipped,
    }


if __name__ == "__main__":
    raise SystemExit(main())
