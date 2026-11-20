#!/usr/bin/env python3
"""复算 2026-11-15/16 review-6 线上一轮的全部汇总值（只用标准库）。"""

import csv
import hashlib
import math
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).parent
REVIEWS = HERE / "review6-round-20261116.csv"
FINDINGS = HERE / "review6-findings-20261116.csv"
REVIEWS_SHA256 = "3e99d2f1f441c2121ee31739585391a906caf793626098700b2016e0f71ad530"
FINDINGS_SHA256 = "bd9c27524f7c9fe919f798c20f99b3713f74f6ab41fcef5df4b093d5bcc57cef"
SUMS = ("findings", "findings_requirement", "findings_code_quality", "confidence_high", "confidence_medium",
        "confidence_low", "continuity_new", "continuity_persisting", "continuity_suppressed", "findings_previous",
        "same_issue_as_previous", "warnings", "warnings_finding_line_corrected", "warnings_finding_dropped",
        "warnings_evidence_line_corrected", "warnings_evidence_dropped", "warnings_ambiguous_line", "ac_total",
        "ac_covered", "ac_at_risk", "ac_not_found", "ac_unknown", "files_reviewed", "files_not_reviewed",
        "embedding_calls", "review_calls", "calls_failed", "chat_prompt_tokens", "chat_completion_tokens",
        "adjudicated_confirmed", "adjudicated_rejected", "provider_latency_ms")


def pct(numerator, denominator):
    return "—" if denominator == 0 else f"{100 * numerator / denominator:.1f}%"


def wilson(successes, total, z=1.96):
    """95% Wilson 区间；样本彼此不独立（同一 PR、同一判定人），只作描述。"""
    if total == 0:
        return "—"
    p = successes / total
    centre = (p + z * z / (2 * total)) / (1 + z * z / total)
    half = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / (1 + z * z / total)
    return f"{100 * (centre - half):.1f}–{100 * (centre + half):.1f}%"


def median(values):
    ordered = sorted(values)
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / 2


with REVIEWS.open(newline="", encoding="utf-8") as handle:
    reviews = list(csv.DictReader(handle))
with FINDINGS.open(newline="", encoding="utf-8") as handle:
    findings = list(csv.DictReader(handle))

assert len(reviews) == 40 and len(findings) == 54
assert all(row["status"] == "COMPLETED" and row["execution_attempt"] == "1" for row in reviews)
assert all(row["adjudication"] in ("CONFIRMED", "REJECTED") for row in findings)

totals = defaultdict(lambda: defaultdict(int))
for row in reviews:
    # 每条审查的 Finding 明细必须与汇总行对得上。
    own = [item for item in findings if item["review_id"] == row["review_id"]]
    assert len(own) == int(row["findings"])
    assert sum(item["adjudication"] == "CONFIRMED" for item in own) == int(row["adjudicated_confirmed"])
    assert int(row["ac_covered"]) + int(row["ac_at_risk"]) + int(row["ac_not_found"]) + int(row["ac_unknown"]) \
        == int(row["ac_total"])
    for key in (row["dataset"], "all"):
        bucket = totals[key]
        bucket["reviews"] += 1
        bucket["zero_finding_reviews"] += row["findings"] == "0"
        bucket["diff_changed"] += int(row["diff_changed"])
        for field in SUMS:
            bucket[field] += int(row[field])

for dataset in ("demo", "halo", "all"):
    bucket = totals[dataset]
    rows = [row for row in reviews if dataset in (row["dataset"], "all")]
    judged = bucket["adjudicated_confirmed"] + bucket["adjudicated_rejected"]
    print(f"\n== {dataset}：{bucket['reviews']} 条审查，{len({(r['project_id'], r['pr_number']) for r in rows})} 个 PR ==")
    print(f"   Finding {bucket['findings']}（需求类 {bucket['findings_requirement']}、代码质量 "
          f"{bucket['findings_code_quality']}）；零 Finding 的审查 {bucket['zero_finding_reviews']}")
    print(f"   置信度 HIGH/MEDIUM/LOW：{bucket['confidence_high']}/{bucket['confidence_medium']}/"
          f"{bucket['confidence_low']}")
    print(f"   连续性 NEW/PERSISTING/SUPPRESSED：{bucket['continuity_new']}/{bucket['continuity_persisting']}/"
          f"{bucket['continuity_suppressed']}；与上一轮「同一问题」{bucket['same_issue_as_previous']}"
          f"（上一轮共 {bucket['findings_previous']} 条）")
    print(f"   判定：确认 {bucket['adjudicated_confirmed']}/{judged}（{pct(bucket['adjudicated_confirmed'], judged)}，"
          f"Wilson {wilson(bucket['adjudicated_confirmed'], judged)}）")
    print(f"   AC 裁定 {bucket['ac_total']} 条：COVERED {bucket['ac_covered']}（{pct(bucket['ac_covered'], bucket['ac_total'])}）、"
          f"AT_RISK {bucket['ac_at_risk']}、NOT_FOUND {bucket['ac_not_found']}、UNKNOWN {bucket['ac_unknown']}")
    print(f"   校验器警告 {bucket['warnings']}：Finding 纠行 {bucket['warnings_finding_line_corrected']}、"
          f"Finding 丢弃 {bucket['warnings_finding_dropped']}、AC 证据纠行 {bucket['warnings_evidence_line_corrected']}、"
          f"AC 证据丢弃 {bucket['warnings_evidence_dropped']}、去歧义 {bucket['warnings_ambiguous_line']}")
    print(f"   文件：已审 {bucket['files_reviewed']}、未审 {bucket['files_not_reviewed']}")
    print(f"   调用：embedding {bucket['embedding_calls']}、chat {bucket['review_calls']}、失败 {bucket['calls_failed']}；"
          f"chat token {bucket['chat_prompt_tokens']} + {bucket['chat_completion_tokens']}")
    seconds = [int(row["created_to_last_call_ms"]) / 1000 for row in rows]
    provider = [int(row["provider_latency_ms"]) / 1000 for row in rows]
    print(f"   创建到最后一次调用返回：中位 {median(seconds):.1f} s、最长 {max(seconds):.1f} s；"
          f"其中模型服务累计时延中位 {median(provider):.1f} s、最长 {max(provider):.1f} s")
    print(f"   决定：{dict(sorted(Counter(row['decision'] for row in rows).items()))}")

print("\n== 判定按置信度与类型 ==")
for field in ("confidence", "finding_type", "dataset"):
    for value in sorted({row[field] for row in findings}):
        group = [row for row in findings if row[field] == value]
        confirmed = sum(row["adjudication"] == "CONFIRMED" for row in group)
        print(f"   {field}={value}：{confirmed}/{len(group)}（{pct(confirmed, len(group))}，Wilson {wilson(confirmed, len(group))}）")

# 同一问题在此前轮次里已有人工判定时，本轮的判定是否与之一致。
agreement = Counter()
for row in findings:
    earlier = (int(row["earlier_same_issue_confirmed"]), int(row["earlier_same_issue_rejected"]))
    if earlier == (0, 0):
        agreement["此前无判定"] += 1
    elif earlier[0] and earlier[1]:
        agreement["此前自身不一致"] += 1
    elif (row["adjudication"] == "CONFIRMED") == bool(earlier[0]):
        agreement["一致"] += 1
    else:
        agreement["相反"] += 1
print(f"\n== 与此前轮次同一问题的判定相比：{dict(agreement)} ==")
contested = [row for row in findings if row["dataset"] == "halo" and row["adjudication"] == "REJECTED"
             and int(row["earlier_same_issue_confirmed"]) > 0]
print(f"   Halo 上本轮驳回、此前确认过的：fork PR {sorted(int(row['pr_number']) for row in contested)}；"
      f"若按此前判定计，Halo 为 {3 + len(contested)}/12，全部为 {44 + len(contested)}/54")
assert agreement == {"此前无判定": 14, "一致": 35, "相反": 3, "此前自身不一致": 2}
assert sorted(int(row["pr_number"]) for row in contested) == [8, 17, 20]

print(f"   判定后的当前状态：{dict(sorted(Counter(row['status_at_export'] for row in findings).items()))}")

# 论文正文引用的几个事实。
everything, demo, halo = totals["all"], totals["demo"], totals["halo"]
assert (everything["reviews"], everything["findings"], everything["calls_failed"], everything["files_not_reviewed"]) \
    == (40, 54, 0, 0)
assert (demo["findings"], demo["adjudicated_confirmed"], halo["findings"], halo["adjudicated_confirmed"]) == (42, 41, 12, 3)
assert (everything["adjudicated_confirmed"], everything["adjudicated_rejected"]) == (44, 10)
assert Counter(row["decision"] for row in reviews) == {"REQUEST_CHANGES": 16, "PENDING": 22, "APPROVE": 1, "REJECT": 1}
assert (everything["continuity_persisting"], everything["continuity_suppressed"]) == (4, 0)
assert halo["zero_finding_reviews"] == 14 and everything["diff_changed"] == 2
# 09-22 判定为真的七条分布在 fork PR #3、#5、#7、#12、#16、#19、#22；本轮其中四个 PR 没有任何 Finding。
assert not [row for row in findings if row["dataset"] == "halo" and row["pr_number"] in ("5", "7", "12", "19")]
assert sorted({int(row["pr_number"]) for row in findings if row["dataset"] == "halo"}) == [3, 8, 13, 16, 17, 18, 20, 22, 23, 24]

assert hashlib.sha256(REVIEWS.read_bytes()).hexdigest() == REVIEWS_SHA256, "review dataset changed"
assert hashlib.sha256(FINDINGS.read_bytes()).hexdigest() == FINDINGS_SHA256, "finding dataset changed"
print("\nOK: both datasets match their pinned SHA-256")
