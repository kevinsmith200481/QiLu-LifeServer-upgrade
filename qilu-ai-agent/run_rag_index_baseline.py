from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
BASELINE_PATH = ROOT / "rag_baseline_cases.json"
EXPECTED_FIXTURE_SHA256 = "ce7bdb8cb0709b2c9798c6f358c20fa402d8c38bd535f771e086ec56f78c6fcf"


def validate_archived_findings(path: Path) -> dict:
    if not path.is_file():
        raise ValueError("阶段 A 原始 baseline-findings.json 不存在，禁止用改造后的代码补采。")

    try:
        findings = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("阶段 A 原始 baseline-findings.json 无法读取。") from exc

    expected = {
        "schemaVersion": 1,
        "stage": "A",
        "fixtureSha256": EXPECTED_FIXTURE_SHA256,
        "passed": True,
    }
    mismatches = [key for key, value in expected.items() if findings.get(key) != value]
    retrieval = findings.get("retrievalOnly", {})
    sync = findings.get("sync", {})
    if retrieval.get("p50LatencyMs") != 0.082:
        mismatches.append("retrievalOnly.p50LatencyMs")
    if retrieval.get("p95LatencyMs") != 0.105:
        mismatches.append("retrievalOnly.p95LatencyMs")
    if sync.get("durationMs") != 79.82:
        mismatches.append("sync.durationMs")
    if mismatches:
        raise ValueError("阶段 A 原始证据字段已改变：" + ", ".join(mismatches))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="只读校验已封存的阶段 A RAG 索引基线")
    parser.add_argument("--output", required=True, help="历史兼容参数：必须指向已存在的原始证据文件")
    args = parser.parse_args()

    fixture_sha256 = hashlib.sha256(BASELINE_PATH.read_bytes()).hexdigest()
    if fixture_sha256 != EXPECTED_FIXTURE_SHA256:
        parser.error("阶段 A fixture SHA-256 已改变，拒绝验证。")

    try:
        findings = validate_archived_findings(Path(args.output))
    except ValueError as exc:
        parser.error(str(exc))

    # 这里只输出原证据摘要，整个过程不写文件，防止当前实现覆盖改造前测量。
    print(json.dumps({
        "archiveValid": True,
        "fixtureSha256": findings["fixtureSha256"],
        "retrievalP50Ms": findings["retrievalOnly"]["p50LatencyMs"],
        "retrievalP95Ms": findings["retrievalOnly"]["p95LatencyMs"],
        "sync100DocumentsMs": findings["sync"]["durationMs"],
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
