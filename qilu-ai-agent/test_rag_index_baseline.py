from __future__ import annotations

import hashlib
import json
import os
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
BASELINE_PATH = ROOT / "rag_baseline_cases.json"
REPORT_PATH = ROOT.parent / "plan" / "AI智能体-RAG统一切分混合检索与原子索引阶段A基线报告.md"
EXPECTED_FIXTURE_SHA256 = "ce7bdb8cb0709b2c9798c6f358c20fa402d8c38bd535f771e086ec56f78c6fcf"


def load_baseline() -> dict:
    return json.loads(BASELINE_PATH.read_text(encoding="utf-8"))


def build_content(case: dict) -> str:
    # 归档 fixture 只含合成填充文本和答案标记，不读取任何当前知识或用户数据。
    return (
        case["prefix"] * case["prefixRepeat"]
        + case["answer"]
        + case["suffix"] * case["suffixRepeat"]
    )


def archived_test_result_is_valid(path: Path) -> bool:
    try:
        result = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    return (
        result.get("stage") == "A"
        and result.get("testCount") == 8
        and result.get("failureCount") == 0
        and result.get("errorCount") == 0
        and result.get("skippedCount") == 0
        and result.get("baselineValid") is True
    )


class RagIndexBaselineArchiveTest(unittest.TestCase):
    """校验阶段 A 封存口径，不再要求当前代码继续保留阶段 A 缺陷。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.baseline_bytes = BASELINE_PATH.read_bytes()
        cls.baseline = json.loads(cls.baseline_bytes.decode("utf-8"))
        cls.report = REPORT_PATH.read_text(encoding="utf-8")

    def test_fixture_hash_remains_sealed(self) -> None:
        self.assertEqual(EXPECTED_FIXTURE_SHA256, hashlib.sha256(self.baseline_bytes).hexdigest())

    def test_fixture_schema_and_stage_remain_sealed(self) -> None:
        self.assertEqual(1, self.baseline["schemaVersion"])
        self.assertEqual("A", self.baseline["stage"])

    def test_measurement_configuration_remains_sealed(self) -> None:
        config = self.baseline["measurementConfig"]
        self.assertEqual((200, 20, 100, 3), (
            config["retrievalSamples"],
            config["retrievalWarmups"],
            config["syncDocumentCount"],
            config["topK"],
        ))
        self.assertEqual(("local", "local-hash-v1", 384), (
            config["embeddingProvider"], config["embeddingModel"], config["embeddingDimension"],
        ))

    def test_fixture_ids_are_positive_and_unique(self) -> None:
        knowledge_ids = [case["knowledgeId"] for case in self.baseline["fixtures"]]
        self.assertTrue(all(type(value) is int and value > 0 for value in knowledge_ids))
        self.assertEqual(len(knowledge_ids), len(set(knowledge_ids)))

    def test_middle_answer_offset_remains_in_original_window(self) -> None:
        case = self.baseline["fixtures"][0]
        offset = build_content(case).index(case["answer"])
        self.assertGreater(offset, 300)
        self.assertLess(offset, 4096)

    def test_tail_answer_offset_remains_after_milvus_limit(self) -> None:
        case = self.baseline["fixtures"][1]
        self.assertGreater(build_content(case).index(case["answer"]), 4096)

    def test_report_keeps_original_status_and_metrics(self) -> None:
        self.assertIn("阶段状态：`BASELINE_CAPTURED`", self.report)
        self.assertIn("证据属性：`NON_FORMAL_DIRTY_WORKTREE`", self.report)
        self.assertIn("`0.082ms`", self.report)
        self.assertIn("`0.105ms`", self.report)
        self.assertIn("`79.820ms`", self.report)

    def test_report_keeps_original_evidence_inventory(self) -> None:
        self.assertIn("8/8 通过", self.report)
        self.assertIn("baseline-test-results.json", self.report)
        self.assertIn("baseline-findings.json", self.report)
        self.assertIn("不表示 `KnowledgeChunk`、BM25/RRF", self.report)


def main() -> int:
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(RagIndexBaselineArchiveTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    archive_valid = result.testsRun == 8 and not result.failures and not result.errors

    # 旧阶段脚本若传入原证据路径，只做只读校验；绝不覆盖阶段 A 原始结果。
    archived_result_path = os.getenv("AI_RAG_BASELINE_TEST_RESULT_PATH", "").strip()
    if archived_result_path:
        archive_valid = archive_valid and archived_test_result_is_valid(Path(archived_result_path))
        if not archive_valid:
            print("阶段 A 测试证据缺失或已改变，禁止用当前实现重新生成基线。", file=sys.stderr)

    return 0 if archive_valid else 1


if __name__ == "__main__":
    sys.exit(main())
