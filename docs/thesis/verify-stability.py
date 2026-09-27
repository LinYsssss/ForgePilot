#!/usr/bin/env python3
"""复算 2026-09-25 稳定性复测的全部汇总值（只用标准库）：相邻两轮审查在同一批 PR 上的一致程度。"""

import csv
import hashlib
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).parent
DATA = HERE / "stability-20260925.csv"
DATA_SHA256 = "4ac969976f4bc783f853c7f40f3f80148d555382dcd2a56b020be82525078236"
PAIRS = {
    "0920-0922": "演示仓，review-2 → review-2，provider 默认温度，中间加了证据锚定校验器",
    "0922-0923": "review-2 → review-3（Prompt 加中文规则），provider 默认温度",
    "0925A-0925B": "review-4 → review-4，温度 0，两轮之间只有空提交",
}
COUNTS = ("findings_before", "findings_after", "same_key", "same_issue", "ac_compared", "ac_same_verdict",
          "dropped_before", "dropped_after", "corrected_before", "corrected_after", "recall_same",
          "adjudicated_0922", "adjudicated_in_before", "adjudicated_in_after")


def pct(numerator, denominator):
    return "—" if denominator == 0 else f"{100 * numerator / denominator:.1f}%"


def jaccard(shared, before, after):
    """两轮 Finding 集合的交并比：共同项 / 两轮合计去掉共同项。"""
    return pct(shared, before + after - shared)


with DATA.open(newline="", encoding="utf-8") as handle:
    rows = list(csv.DictReader(handle))
assert len(rows) == 14 + 37 + 37
assert {row["pair"] for row in rows} == set(PAIRS)

totals = defaultdict(lambda: defaultdict(int))
for row in rows:
    values = {field: int(row[field]) for field in COUNTS}
    assert values["same_key"] <= min(values["findings_before"], values["findings_after"])
    assert values["same_issue"] <= min(values["findings_before"], values["findings_after"])
    assert values["ac_same_verdict"] <= values["ac_compared"]
    identical = (values["same_key"] == values["findings_before"] == values["findings_after"]
                 and values["ac_same_verdict"] == values["ac_compared"])
    for key in ((row["pair"], row["dataset"]), (row["pair"], "all")):
        bucket = totals[key]
        bucket["prs"] += 1
        bucket["identical_reports"] += identical
        for field, value in values.items():
            bucket[field] += value

for pair, description in PAIRS.items():
    print(f"\n== {pair}：{description} ==")
    for dataset in ("demo", "halo", "all"):
        bucket = totals.get((pair, dataset))
        if bucket is None:
            continue
        print(f"-- {dataset}（{bucket['prs']} 个 PR）")
        print(f"   Finding 数：{bucket['findings_before']} → {bucket['findings_after']}")
        print(f"   同 finding_key：{bucket['same_key']}，交并比 "
              f"{jaccard(bucket['same_key'], bucket['findings_before'], bucket['findings_after'])}")
        print(f"   同一问题：{bucket['same_issue']}，交并比 "
              f"{jaccard(bucket['same_issue'], bucket['findings_before'], bucket['findings_after'])}")
        print(f"   AC 裁定一致：{bucket['ac_same_verdict']}/{bucket['ac_compared']} "
              f"({pct(bucket['ac_same_verdict'], bucket['ac_compared'])})")
        print(f"   报告完全相同的 PR：{bucket['identical_reports']}；两轮知识召回逐项相同的 PR：{bucket['recall_same']}")
        print(f"   校验器整条丢弃：{bucket['dropped_before']} → {bucket['dropped_after']}；"
              f"纠正行号：{bucket['corrected_before']} → {bucket['corrected_after']}")
        if bucket["adjudicated_0922"]:
            print(f"   09-22 人工判定的 {bucket['adjudicated_0922']} 条再次出现（同一问题）："
                  f"前一轮 {bucket['adjudicated_in_before']}，后一轮 {bucket['adjudicated_in_after']}")

# 论文正文引用的几个事实。
stable = totals["0925A-0925B", "all"]
assert (stable["prs"], stable["recall_same"], stable["identical_reports"]) == (37, 33, 6)
assert totals["0925A-0925B", "halo"]["dropped_before"] == totals["0925A-0925B", "halo"]["dropped_after"] == 0
assert (totals["0922-0923", "halo"]["dropped_before"], totals["0922-0923", "halo"]["dropped_after"]) == (9, 9)
assert (totals["0922-0923", "halo"]["adjudicated_in_after"], stable["adjudicated_in_before"],
        stable["adjudicated_in_after"]) == (3, 6, 5)

assert hashlib.sha256(DATA.read_bytes()).hexdigest() == DATA_SHA256, "dataset changed"
print("\nOK: dataset matches its pinned SHA-256")
