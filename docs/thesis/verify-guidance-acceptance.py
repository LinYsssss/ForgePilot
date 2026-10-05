#!/usr/bin/env python3
"""只读复算公开的建议验收摘要；不调用模型，也不读取私有原始回答。"""

import csv
import hashlib
import math
import re
from pathlib import Path

HERE = Path(__file__).parent
DATA = HERE / "guidance-acceptance-20261003.csv"
REPORT = HERE / "GUIDANCE-ACCEPTANCE.md"
DATA_SHA256 = "07c5815935d7715f3c58095e79971a0de75bbdc266d1c351f0b002b8852c5b29"
COUNTS = ("questions", "steps", "rules", "risks", "knowledge_chunks", "body_chars")
FIELDS = ("sample_id", "role", "guidance_version", "channel", "elapsed_seconds",
          *COUNTS, "context_sha256", "input_sha256", "response_sha256")
EXPECTED = {("baseline", "shipping"), ("acceptance", "shipping"),
            ("acceptance", "tenant"), ("acceptance", "csv")}
LABELS = {"shipping": "发货校验", "tenant": "租户查询", "csv": "CSV 导入草稿"}

assert hashlib.sha256(DATA.read_bytes()).hexdigest() == DATA_SHA256, "摘要数据发生变化"
with DATA.open(encoding="utf-8", newline="") as handle:
    reader = csv.DictReader(handle)
    assert tuple(reader.fieldnames) == FIELDS
    rows = list(reader)
assert len(rows) == 4
assert {(r["role"], r["sample_id"]) for r in rows} == EXPECTED
for row in rows:
    assert row["guidance_version"] == ("guidance-2" if row["role"] == "baseline" else "guidance-3")
    assert row["channel"] == ("browser" if row["sample_id"] == "csv" else "api")
    elapsed = float(row["elapsed_seconds"])
    assert math.isfinite(elapsed) and elapsed > 0
    for field in COUNTS:
        assert re.fullmatch(r"\d+", row[field])
    assert int(row["body_chars"]) > 0
    for field in ("context_sha256", "input_sha256", "response_sha256"):
        assert re.fullmatch(r"[0-9a-f]{64}", row[field])

by_id = {(r["role"], r["sample_id"]): r for r in rows}
current = [by_id["acceptance", key] for key in ("shipping", "tenant", "csv")]
before, after = by_id["baseline", "shipping"], by_id["acceptance", "shipping"]
assert before["context_sha256"] == after["context_sha256"]
delta = int(after["body_chars"]) - int(before["body_chars"])
percent = 100 * delta / int(before["body_chars"])

# 标记块是这份摘要的单一数值表；正文表漂移时明确失败，不自动改写论文。
lines = ["| 本轮样例 | 入口 | 耗时（秒） | 待确认 | 步骤 | 规则 | 风险 | 知识片段 | 正文字符 |",
         "|---|---|---:|---:|---:|---:|---:|---:|---:|"]
for row in current:
    values = " | ".join(row[field] for field in COUNTS)
    lines.append(f"| {LABELS[row['sample_id']]} | {row['channel']} | {float(row['elapsed_seconds']):.3f} | {values} |")
lines += ["", "| 发货校验前后对照 | guidance-2 基线 | guidance-3 本轮 |",
          "|---|---:|---:|"]
for title, field in (("待确认问题", "questions"), ("实施步骤", "steps"), ("正文字符", "body_chars")):
    lines.append(f"| {title} | {before[field]} | {after[field]} |")
lines += ["", f"正文字符差值：{delta:+d}（{percent:+.1f}%）。"]
expected_table = "\n".join(lines)
text = REPORT.read_text(encoding="utf-8")
start, end = "<!-- BEGIN VERIFIED SUMMARY -->", "<!-- END VERIFIED SUMMARY -->"
assert text.count(start) == text.count(end) == 1
actual_table = text.split(start, 1)[1].split(end, 1)[0].strip()
assert actual_table == expected_table, "论文摘要表与 CSV 不一致"
print(expected_table)
print(f"\nOK: {len(current)} 条本轮样例 + 1 条历史基线；CSV 哈希及论文表格一致。")
print("这只复核公开摘要及汇总，不证明模型语义正确或输出可重现。")
