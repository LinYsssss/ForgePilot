#!/usr/bin/env python3
"""Recompute the 2026-09-22 live-exercise summaries from the two compact datasets (standard library only)."""

import csv
import hashlib
from pathlib import Path

HERE = Path(__file__).parent
DEMO = HERE / "demo-rerun-20260922.csv"
HALO = HERE / "halo-replay-20260922.csv"
DEMO_SHA256 = "b9409497b9d544192e648f910b3cc6b5203342731dc5b0113847a56d76bc61fb"
HALO_SHA256 = "25ce560b1d34d15ebfaf6c914010c5afd10071e41dc9caed7ea9e149cc193681"


def load(path):
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def total(rows, name):
    return sum(int(row[name]) for row in rows)


def pct(numerator, denominator):
    return round(100 * numerator / denominator, 1)


# ---------------------------------------------------------------- demo rerun
demo = load(DEMO)
assert len(demo) == 15
assert [int(r["sequence"]) for r in demo] == list(range(1, 16))
assert all(r["status"] == "COMPLETED" for r in demo)
assert all(int(r["execution_attempt"]) == 1 for r in demo)
assert all(int(r["not_reviewed_files"]) == 0 for r in demo)
assert all(int(r["review_calls"]) == 2 for r in demo)
assert sum(1 for r in demo if r["decision"] == "APPROVE") == 1

demo_summary = {
    "findings": total(demo, "findings"),
    "findings_previous": total(demo, "findings_previous"),
    "same_key_as_previous": total(demo, "same_key_as_previous"),
    "continuity_new": total(demo, "continuity_new"),
    "continuity_persisting": total(demo, "continuity_persisting"),
    "continuity_suppressed": total(demo, "continuity_suppressed"),
    "warnings": total(demo, "warnings"),
    "warnings_line_corrected": total(demo, "warnings_line_corrected"),
    "warnings_dropped": total(demo, "warnings_dropped"),
    "embedding_failed": total(demo, "embedding_failed"),
    "ac_covered": total(demo, "ac_covered"),
    "ac_at_risk": total(demo, "ac_at_risk"),
    "ac_not_found": total(demo, "ac_not_found"),
}
assert demo_summary["findings"] == demo_summary["continuity_new"] + demo_summary["continuity_persisting"] + demo_summary["continuity_suppressed"]
assert demo_summary["warnings"] == demo_summary["warnings_line_corrected"] + demo_summary["warnings_dropped"]
print("== 演示仓重跑（锚定校验器）==")
for key, value in demo_summary.items():
    print(f"{key}: {value}")
print(f"findings_per_pr: {round(demo_summary['findings'] / len(demo), 2)}")
print(f"prs_with_line_corrections: {sum(1 for r in demo if int(r['warnings_line_corrected']) > 0)}")

# ---------------------------------------------------------------- halo replay
halo = load(HALO)
assert len(halo) == 24
assert [int(r["sequence"]) for r in halo] == list(range(1, 25))
assert [int(r["fork_pr"]) for r in halo] == list(range(1, 25))
completed = [r for r in halo if r["status"] == "COMPLETED"]
failed = [r for r in halo if r["status"] == "FAILED"]
assert len(completed) == 23 and len(failed) == 1
assert failed[0]["upstream_pr"] == "10289" and int(failed[0]["execution_attempt"]) == 3
assert int(failed[0]["review_timeouts"]) == 6
assert all(int(r["not_reviewed_files"]) == 0 for r in completed)
assert all(int(r["knowledge_excerpts"]) == 8 for r in completed)

adjudicated = [r for r in completed if int(r["findings"]) > 0]
assert all(int(r["findings"]) == 1 for r in adjudicated)
assert all(r["adjudication"] in ("true", "arguable", "false") for r in adjudicated)
assert all(r["adjudication"] == "" for r in completed if int(r["findings"]) == 0)

halo_summary = {
    "completed": len(completed),
    "findings": total(completed, "findings"),
    "findings_requirement": total(completed, "findings_requirement"),
    "findings_code_quality": total(completed, "findings_code_quality"),
    "prs_with_zero_findings": sum(1 for r in completed if int(r["findings"]) == 0),
    "adjudicated_true": sum(1 for r in adjudicated if r["adjudication"] == "true"),
    "adjudicated_arguable": sum(1 for r in adjudicated if r["adjudication"] == "arguable"),
    "adjudicated_false": sum(1 for r in adjudicated if r["adjudication"] == "false"),
    "confidence_high": total(completed, "confidence_high"),
    "confidence_medium": total(completed, "confidence_medium"),
    "ac_covered": total(completed, "ac_covered"),
    "ac_at_risk": total(completed, "ac_at_risk"),
    "ac_not_found": total(completed, "ac_not_found"),
    "warnings": total(completed, "warnings"),
    "warnings_line_corrected": total(completed, "warnings_line_corrected"),
    "warnings_dropped": total(completed, "warnings_dropped"),
    "review_calls_success": total(halo, "review_calls") - total(halo, "review_timeouts"),
    "review_timeouts": total(halo, "review_timeouts"),
    "embedding_failed": total(halo, "embedding_failed"),
    "prompt_tokens": total(completed, "prompt_tokens"),
    "completion_tokens": total(completed, "completion_tokens"),
    "prs_with_linked_issue": sum(1 for r in halo if r["linked_issues"]),
    "kind_bugfix": sum(1 for r in halo if r["kind"] == "bugfix"),
}
assert halo_summary["findings"] == halo_summary["findings_requirement"] + halo_summary["findings_code_quality"]
assert halo_summary["findings"] == halo_summary["adjudicated_true"] + halo_summary["adjudicated_arguable"] + halo_summary["adjudicated_false"]
ac_total = halo_summary["ac_covered"] + halo_summary["ac_at_risk"] + halo_summary["ac_not_found"]
print("\n== Halo 重放 ==")
for key, value in halo_summary.items():
    print(f"{key}: {value}")
print(f"findings_per_pr: {round(halo_summary['findings'] / len(completed), 2)}")
print(f"ac_covered_pct: {pct(halo_summary['ac_covered'], ac_total)}")
print(f"precision_true: {pct(halo_summary['adjudicated_true'], halo_summary['findings'])}")
print(f"precision_true_or_arguable: {pct(halo_summary['adjudicated_true'] + halo_summary['adjudicated_arguable'], halo_summary['findings'])}")
first = [r for r in completed if int(r["execution_attempt"]) == 1]
e2e = sorted(int(r["end_to_end_ms"]) / 60000 for r in first)
prov = [int(r["provider_latency_ms"]) / 60000 for r in first]
calls = sum(int(r["review_calls"]) + int(r["embedding_calls"]) for r in first)
print(f"first_attempt_reviews: {len(first)}")
print(f"end_to_end_min mean/median/max: {round(sum(e2e) / len(e2e), 1)} / {round(e2e[len(e2e) // 2], 1)} / {round(e2e[-1], 1)}")
print(f"provider_min mean: {round(sum(prov) / len(prov), 1)}   per_call_s: {round(sum(prov) * 60 / calls, 1)}")

# ---------------------------------------------------------------- comparison
print("\n== 对比 ==")
print(f"demo findings_per_pr {round(demo_summary['findings'] / len(demo), 2)} vs halo {round(halo_summary['findings'] / len(completed), 2)}")
print(f"demo ac_covered_pct {pct(demo_summary['ac_covered'], demo_summary['ac_covered'] + demo_summary['ac_at_risk'] + demo_summary['ac_not_found'])} vs halo {pct(halo_summary['ac_covered'], ac_total)}")

assert hashlib.sha256(DEMO.read_bytes()).hexdigest() == DEMO_SHA256, "demo dataset changed"
assert hashlib.sha256(HALO.read_bytes()).hexdigest() == HALO_SHA256, "halo dataset changed"
print("\nOK: both datasets match their pinned SHA-256")
