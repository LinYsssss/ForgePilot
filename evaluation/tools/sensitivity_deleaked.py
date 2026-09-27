#!/usr/bin/env python3
"""去提示敏感性实验，以及对冻结结果的事后标签分析。两件事都不改任何冻结文件。

- ``run``：同一 38 例、同一冻结 runner / 模型 / 温度 / 超时，只把点名缺陷的提示换成中性文本，
  再跑一次三臂。这是新的实验身份 ``sensitivity-deleaked-38-v1``，不是 holdout 重跑：不写 formal
  结果目录与台账，也不据此调整任何配置；结果只作敏感性分析，不得与正式口径合并分母。
- ``export``：对冻结结果与去提示结果，用冻结评分器自己的 ``match_findings`` 按三种口径逐例重算
  ——原规则、去掉「类型」条件、去掉「类型 + 类别」条件——写出不含用例 ID 的逐例 CSV。

被换掉的提示（语料从旧仓迁移时带进来的模板文本）：

- 用例 ID 出现在每一臂的提示词里，且本身点名缺陷（如 ``java-sql-resource-leak``）；
- 需求标题与描述复述用例 ID，背景写出缺陷类别；
- 缺陷 AC 以 ``[类别]`` 开头、以「重点核验 <路径> 第 a-b 行。」结尾。

AC 里描述规则本身的那句话保留：那是需求该说的话，位置与类别标签不是。
"""

import argparse
import csv
import json
import os
import re
import sys
from pathlib import Path
from typing import Any

import formal_evaluation as formal
import run_development as runner
import score

EXPERIMENT = "sensitivity-deleaked-38-v1"
OUT_DIR = formal.EVALUATION_DIR / "results" / "sensitivity-deleaked"
FORMAL_DIR = formal.EVALUATION_DIR / "results" / "formal"
CORRECTION = (formal.ROOT_DIR / ".trellis" / "tasks" / "archive" / "2026-08"
              / "08-22-phase-8-gitlab-evaluation-defense" / "evidence" / "provider-endpoint-correction.json")
SPLITS = ("development", "holdout")
LEVELS = ("strict", "type_agnostic", "label_agnostic")
LOCATED = re.compile(r"^\[[A-Z_]+\]\s*(.*?)；重点核验 \S+ 第 \d+-\d+ 行。$")
NEUTRAL_REQUIREMENT = {
    "title": "审查本次变更并保持代码契约",
    "background": "该案例用于验证增量代码审查对缺陷及明确非目标行为的识别边界。",
    "description": "针对本案例的 diff，检查变更是否满足下列 AC；模型只能基于真实代码证据给出覆盖、未发现或存在风险的结论。",
}


def opaque_ids(manifest: dict[str, Any]) -> dict[str, str]:
    """按语料原有顺序编号；编号不含任何用例名称，因此可以入库。"""
    return {case["id"]: f"case-{number:02d}" for number, case in enumerate(manifest["cases"], start=1)}


def deleaked(manifest: dict[str, Any]) -> dict[str, Any]:
    ids = opaque_ids(manifest)
    cases = []
    stripped = 0
    for case in manifest["cases"]:
        criteria = []
        for item in case["acceptanceCriteria"]:
            located = LOCATED.match(item["text"])
            stripped += located is not None
            criteria.append({**item, "text": located.group(1) if located else item["text"]})
        cases.append({**case, "id": ids[case["id"]], "requirement": dict(NEUTRAL_REQUIREMENT),
                      "acceptanceCriteria": criteria})
    expected = sum(1 for case in manifest["cases"] if case["expectedFindings"])
    if stripped != expected:
        raise SystemExit(f"改写了 {stripped} 条定位 AC，而有真值的用例是 {expected} 个")
    return {**manifest, "cases": cases}


def load_corpus() -> tuple[dict[str, Any], dict[str, Any], Path]:
    config = formal.load_config()
    # 冻结的 runner、评分器与契约一个字节都没变，本实验才谈得上「只换了提示」。
    formal.verify_freeze(formal.rooted(config["freezeArtifact"]))
    corpus_root = formal.rooted(config["corpusWorkspace"])
    return config, formal.validate_formal_corpus(corpus_root), corpus_root


def persist(out_dir: Path, manifest: dict[str, Any], config: dict[str, Any], split: str,
            arm: str, cases: list[dict[str, Any]]) -> None:
    document = {
        "contractVersion": score.RUN_VERSION,
        "corpusVersion": manifest["corpusVersion"],
        "caseSetVersion": EXPERIMENT + "-" + split,
        "runKind": "MODEL_EVALUATION",
        "arm": arm,
        "config": {"model": config["provider"]["model"], "temperature": config["provider"]["temperature"],
                   "promptVersion": config["promptVersion"]},
        "cases": cases,
    }
    arm_dir = out_dir / split / arm.lower()
    formal.atomic_json(arm_dir / "runs" / "run.json", document)
    report = score.score_corpus(
        manifest, {case["caseId"]: case for case in cases},
        {key: document[key] for key in ("contractVersion", "corpusVersion", "caseSetVersion", "runKind", "arm", "config")},
        score.load_aliases(runner.ALIASES_PATH),
    )
    score.validate_score_report(report)
    formal.atomic_json(arm_dir / "score.json", report)


def run() -> None:
    config, corpus, corpus_root = load_corpus()
    manifest = deleaked(corpus)
    provider = config["provider"]
    effective = json.loads(CORRECTION.read_text(encoding="utf-8"))["correctedEndpointIdentity"]
    base_url = os.environ.get("OPENAI_BASE_URL", "").rstrip("/")
    if runner.endpoint(base_url) != effective:
        raise SystemExit("OPENAI_BASE_URL 必须是正式评测订正记录里的同一端点")
    api_key = os.environ.get("OPENAI_API_KEY", "")
    if not api_key:
        raise SystemExit("OPENAI_API_KEY 未设置")
    if OUT_DIR.exists():
        raise SystemExit(f"只运行一次：{OUT_DIR} 已存在")
    for split in SPLITS:
        split_manifest = formal.subset_manifest(manifest, split)
        for arm in config["arms"]:
            cases = []
            for case in split_manifest["cases"]:
                print(f"[{split}][{arm}] {case['id']}", file=sys.stderr, flush=True)
                cases.append(runner.run_case(case, arm, base_url, api_key, provider["model"],
                                             provider["temperature"], provider["timeoutSeconds"], corpus_root))
            persist(OUT_DIR, split_manifest, config, split, arm, cases)


def relabel(findings: list[dict[str, Any]], level: str) -> list[dict[str, Any]]:
    """去掉的条件统一成同一个值，其余交给冻结评分器原样判断。"""
    if level == "strict":
        return findings
    blank: dict[str, Any] = {"findingType": "REQUIREMENT"}
    if level == "label_agnostic":
        blank["category"] = "ANY"
    return [{**finding, **blank} for finding in findings]


def rows_for(condition: str, results_dir: Path, manifest: dict[str, Any], ids: dict[str, str],
             arms: list[str], aliases: dict[str, set[str]]) -> list[dict[str, Any]]:
    rows = []
    for split in SPLITS:
        for arm in arms:
            run = score.read_json(results_dir / split / arm.lower() / "runs" / "run.json")
            results = {case["caseId"]: case for case in run["cases"]}
            for case in formal.subset_manifest(manifest, split)["cases"]:
                result = results[case["id"]]
                predicted = result["findings"] if result["status"] == "COMPLETED" else []
                expected = case["expectedFindings"]
                row = {"condition": condition, "split": split, "arm": arm, "case": ids.get(case["id"], case["id"]),
                       "status": result["status"], "expected": len(expected), "predicted": len(predicted),
                       "expected_requirement": sum(item["findingType"] == "REQUIREMENT" for item in expected)}
                for level in LEVELS:
                    matches, _, _ = score.match_findings(relabel(expected, level), relabel(predicted, level), aliases)
                    row[level] = len(matches)
                    row[level + "_requirement"] = sum(expected[index]["findingType"] == "REQUIREMENT"
                                                      for index, _ in matches)
                rows.append(row)
    return rows


def export(csv_path: Path) -> None:
    config, corpus, _ = load_corpus()
    aliases = score.load_aliases(runner.ALIASES_PATH)
    ids = opaque_ids(corpus)
    rows = rows_for("formal", FORMAL_DIR, corpus, ids, config["arms"], aliases)
    # 原规则那一列必须与冻结报告逐例相等，否则这份 CSV 描述的就不是那次正式运行。
    for row in rows:
        original = next(key for key, value in ids.items() if value == row["case"])
        report = score.read_json(FORMAL_DIR / "reports" / row["split"] / row["arm"].lower() / "score.json")
        frozen = next(case for case in report["cases"] if case["caseId"] == original)
        if frozen["matched"] != row["strict"]:
            raise SystemExit(f"{row['case']} {row['arm']} 与冻结报告不一致")
    if OUT_DIR.exists():
        rows += rows_for("deleaked", OUT_DIR, deleaked(corpus), {}, config["arms"], aliases)
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    print(f"wrote {len(rows)} rows to {csv_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("run")
    exporter = commands.add_parser("export")
    exporter.add_argument("--csv", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "run":
        run()
    else:
        export(args.csv)


if __name__ == "__main__":
    main()
