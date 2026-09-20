#!/usr/bin/env python3
"""Recompute the thesis summary from the compact production dataset."""

import csv
import hashlib
import json
from pathlib import Path


DATA = Path(__file__).with_name("production-revalidation-20260920.csv")
REPORT = Path(__file__).with_name("PRODUCTION-REVALIDATION.md")
DATA_SHA256 = "6bfd4a53c162086f38f3e280716be8b2f8ad1953091ddb343037c3790e691560"


def percentile_cont(values, percentile):
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def integer(row, name):
    return int(row[name])


def number(row, name):
    return float(row[name])


with DATA.open(newline="", encoding="utf-8") as handle:
    rows = list(csv.DictReader(handle))

assert len(rows) == 15
assert [integer(row, "sequence") for row in rows] == list(range(1, 16))
assert [integer(row, "review_id") for row in rows] == list(range(17, 32))
assert all(row["status"] == "COMPLETED" for row in rows)
assert all(row["decision"] == "PENDING" for row in rows)
assert all(row["all_ai_calls_success"] == "true" for row in rows)
for field in (
    "head_match",
    "fingerprint_match",
    "current_revision_match",
    "snapshot_head_match",
    "snapshot_fingerprint_match",
    "snapshot_revision_match",
):
    assert all(row[field] == "true" for row in rows), field
assert all(row["coverage_truncated"] == "false" for row in rows)
assert all(row["engine"] == "forgepilot-review" for row in rows)
assert all(row["prompt_version"] == "review-2" for row in rows)
assert all(row["model"] == "gpt-5.6-luna" for row in rows)

end_to_end = [number(row, "end_to_end_ms") for row in rows]
provider = [integer(row, "provider_latency_ms") for row in rows]

summary = {
    "reviews": len(rows),
    "projects": len({row["project_id"] for row in rows}),
    "completed": sum(row["status"] == "COMPLETED" for row in rows),
    "aiCalls": sum(integer(row, "embedding_calls") + integer(row, "review_calls") for row in rows),
    "embeddingCalls": sum(integer(row, "embedding_calls") for row in rows),
    "reviewCalls": sum(integer(row, "review_calls") for row in rows),
    "promptTokens": sum(integer(row, "prompt_tokens") for row in rows),
    "completionTokens": sum(integer(row, "completion_tokens") for row in rows),
    "totalTokens": sum(integer(row, "total_tokens") for row in rows),
    "coverageFiles": sum(integer(row, "coverage_files") for row in rows),
    "notReviewedFiles": sum(integer(row, "not_reviewed_files") for row in rows),
    "truncatedFiles": sum(integer(row, "truncated_files") for row in rows),
    "acCovered": sum(integer(row, "ac_covered") for row in rows),
    "acAtRisk": sum(integer(row, "ac_at_risk") for row in rows),
    "acNotFound": sum(integer(row, "ac_not_found") for row in rows),
    "findings": sum(integer(row, "findings") for row in rows),
    "requirementFindings": sum(integer(row, "requirement_findings") for row in rows),
    "codeQualityFindings": sum(integer(row, "code_quality_findings") for row in rows),
    "requirementGapFindings": sum(integer(row, "requirement_gap_findings") for row in rows),
    "securityFindings": sum(integer(row, "security_findings") for row in rows),
    "apiContractFindings": sum(integer(row, "api_contract_findings") for row in rows),
    "correctnessFindings": sum(integer(row, "correctness_findings") for row in rows),
    "highConfidenceFindings": sum(integer(row, "high_confidence_findings") for row in rows),
    "mediumConfidenceFindings": sum(integer(row, "medium_confidence_findings") for row in rows),
    "endToEndMs": round(sum(end_to_end), 3),
    "meanEndToEndSeconds": round(sum(end_to_end) / len(rows) / 1000, 3),
    "medianEndToEndSeconds": round(percentile_cont(end_to_end, 0.5) / 1000, 3),
    "p95EndToEndSeconds": round(percentile_cont(end_to_end, 0.95) / 1000, 3),
    "minEndToEndSeconds": round(min(end_to_end) / 1000, 3),
    "maxEndToEndSeconds": round(max(end_to_end) / 1000, 3),
    "providerLatencyMs": sum(provider),
    "meanProviderSeconds": round(sum(provider) / len(rows) / 1000, 3),
    "medianProviderSeconds": round(percentile_cont(provider, 0.5) / 1000, 3),
    "p95ProviderSeconds": round(percentile_cont(provider, 0.95) / 1000, 3),
    "providerSharePercent": round(100 * sum(provider) / sum(end_to_end), 2),
}

expected = {
    "reviews": 15,
    "projects": 3,
    "completed": 15,
    "aiCalls": 45,
    "embeddingCalls": 15,
    "reviewCalls": 30,
    "promptTokens": 103324,
    "completionTokens": 39677,
    "totalTokens": 143001,
    "coverageFiles": 30,
    "notReviewedFiles": 0,
    "truncatedFiles": 0,
    "acCovered": 3,
    "acAtRisk": 41,
    "acNotFound": 21,
    "findings": 55,
    "requirementFindings": 54,
    "codeQualityFindings": 1,
    "requirementGapFindings": 43,
    "securityFindings": 10,
    "apiContractFindings": 1,
    "correctnessFindings": 1,
    "highConfidenceFindings": 51,
    "mediumConfidenceFindings": 4,
    "endToEndMs": 1333478.677,
    "meanEndToEndSeconds": 88.899,
    "medianEndToEndSeconds": 79.193,
    "p95EndToEndSeconds": 131.249,
    "minEndToEndSeconds": 52.337,
    "maxEndToEndSeconds": 147.405,
    "providerLatencyMs": 1331107,
    "meanProviderSeconds": 88.740,
    "medianProviderSeconds": 79.080,
    "p95ProviderSeconds": 131.086,
    "providerSharePercent": 99.82,
}

assert summary == expected, json.dumps({"actual": summary, "expected": expected}, indent=2)
assert hashlib.sha256(DATA.read_bytes()).hexdigest() == DATA_SHA256
report = REPORT.read_text(encoding="utf-8")
for fragment in (
    "15 / 15",
    "45 / 45",
    "103,324",
    "39,677",
    "143,001",
    "88.899 s",
    "88.740 s",
    "79.193 s",
    "79.080 s",
    "131.249 s",
    "131.086 s",
    "52.337 s",
    "147.405 s",
    "1,333.479 秒",
    "1,331.107 秒",
    "99.82%",
    "55 条 Finding",
    "平均每个 PR 3.667 条",
    "需求缺口 43 条",
    "安全 10 条",
    DATA_SHA256,
):
    assert fragment in report, f"report is missing checked value: {fragment}"
print(json.dumps(summary, ensure_ascii=False, indent=2))
