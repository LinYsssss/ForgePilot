#!/usr/bin/env python3
"""复算评测效度分析的全部汇总值（只用标准库）：冻结三臂结果的三种匹配口径，以及去提示敏感性实验。"""

import csv
import hashlib
import math
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).parent
DATA = HERE / "evaluation-sensitivity-20260925.csv"
DATA_SHA256 = "b131446d0efc76ae8c700c233a741cc9ceba331cacb299d0ba7fe6a0eba7478f"
ARMS = ("DIFF_ONLY", "DIFF_REQUIREMENT_AC", "DIFF_REQUIREMENT_AC_KNOWLEDGE")
LEVELS = ("strict", "type_agnostic", "label_agnostic")
# 冻结报告里的真阳性 / 预测数 / 期望数（formal-summary），原规则那一列必须与之逐一相等。
FROZEN = {
    ("development", "DIFF_ONLY"): (3, 21, 22),
    ("development", "DIFF_REQUIREMENT_AC"): (7, 22, 22),
    ("development", "DIFF_REQUIREMENT_AC_KNOWLEDGE"): (8, 21, 22),
    ("holdout", "DIFF_ONLY"): (1, 12, 9),
    ("holdout", "DIFF_REQUIREMENT_AC"): (1, 15, 9),
    ("holdout", "DIFF_REQUIREMENT_AC_KNOWLEDGE"): (2, 10, 9),
}


def wilson(successes, total, z=1.96):
    if total == 0:
        return None
    p = successes / total
    denominator = 1 + z * z / total
    centre = (p + z * z / (2 * total)) / denominator
    margin = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / denominator
    return max(0.0, centre - margin), min(1.0, centre + margin)


def pct(successes, total):
    interval = wilson(successes, total)
    if interval is None:
        return "—"
    return f"{100 * successes / total:5.1f}% [{100 * interval[0]:4.1f}, {100 * interval[1]:4.1f}]"


with DATA.open(newline="", encoding="utf-8") as handle:
    rows = list(csv.DictReader(handle))
assert len(rows) == 2 * 3 * 38
assert {row["condition"] for row in rows} == {"formal", "deleaked"}
assert all(row["status"] == "COMPLETED" for row in rows)

totals = defaultdict(lambda: defaultdict(int))
for row in rows:
    for split in (row["split"], "full"):
        bucket = totals[row["condition"], split, row["arm"]]
        for field, value in row.items():
            if field not in ("condition", "split", "arm", "case", "status"):
                bucket[field] += int(value)

for (split, arm), (tp, predicted, expected) in FROZEN.items():
    bucket = totals["formal", split, arm]
    assert (bucket["strict"], bucket["predicted"], bucket["expected"]) == (tp, predicted, expected), (split, arm)
for key, bucket in totals.items():
    # 放宽只会多匹配，不会少匹配。
    assert bucket["strict"] <= bucket["type_agnostic"] <= bucket["label_agnostic"] <= bucket["expected"], key

for condition, title in (("formal", "冻结三臂结果（AC 带类别、文件与行号提示）"),
                         ("deleaked", "去提示敏感性实验（sensitivity-deleaked-38-v1）")):
    print(f"\n== {title} ==")
    for split in ("development", "holdout", "full"):
        print(f"-- {split}")
        for arm in ARMS:
            bucket = totals[condition, split, arm]
            print(f"   {arm}")
            for level in LEVELS:
                print(f"      {level:15} 精确率 {pct(bucket[level], bucket['predicted'])}"
                      f"   召回率 {pct(bucket[level], bucket['expected'])}")
            print(f"      需求违规召回 原规则 {bucket['strict_requirement']}/{bucket['expected_requirement']}"
                  f"   只比位置 {bucket['label_agnostic_requirement']}/{bucket['expected_requirement']}")

print("\n== 全量、只比位置：去提示相对带提示的变化（召回率 / 精确率，百分点） ==")
for arm in ARMS:
    formal, deleaked = totals["formal", "full", arm], totals["deleaked", "full", arm]
    recall = 100 * (deleaked["label_agnostic"] / deleaked["expected"] - formal["label_agnostic"] / formal["expected"])
    precision = 100 * (deleaked["label_agnostic"] / deleaked["predicted"]
                       - formal["label_agnostic"] / formal["predicted"])
    print(f"   {arm:30} {recall:+5.1f} / {precision:+5.1f}")

assert hashlib.sha256(DATA.read_bytes()).hexdigest() == DATA_SHA256, "dataset changed"
print("\nOK: dataset matches its pinned SHA-256 and the frozen reports")
