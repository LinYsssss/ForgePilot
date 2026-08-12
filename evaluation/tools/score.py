#!/usr/bin/env python3
"""score.py — r7 评测判分器(python3 标准库 only)。

输入:
  - evaluation/manifest.json 的标注(expectedFindings,含可选 lineEnd/categoryEquivalents)
  - run-baseline.sh 落盘的 baseline-runs/<date>/<id>.json(原始 API 响应包,report.issues 为模型 findings)
  - evaluation/tools/category-aliases.json 全局类别别名表

命中规则(design.md D3,匹配规则版本 d3-v1):
  hit(f, e) := samePath(f.filePath, e.path)
            ∧ norm(f.category) ∈ {e.category} ∪ globalAlias(e.category) ∪ e.categoryEquivalents
            ∧ [f.lineStart, f.lineEnd] ∩ [e.line, e.lineEnd] ≠ ∅   (f.lineStart 为 null ⇒ 不命中)
  贪心 1:1:预期与模型 findings 各按(文件, 行)排序;每个预期至多配一个模型 finding,反之亦然。

两指标(独立呈报,禁止合成单一分数):
  漏报率  = 未命中的预期 findings / 预期 findings 总数        (= 1 - recall)
  误报率  = 无对应预期的模型 findings / 模型 findings 总数    (= 1 - precision)
  与后端 EvaluationMetrics.falsePositiveRate(FP/(FP+TN))是不同定义,勿混。

用法:
  python3 evaluation/tools/score.py --runs <baseline-runs/<date>> [--manifest ...] [--aliases ...]
                                    [--out-dir ...] [--label <date>]
  python3 evaluation/tools/score.py --selftest
"""

import argparse
import datetime
import json
import sys
from pathlib import Path

MATCH_RULE_VERSION = "d3-v1"
TOOLS_DIR = Path(__file__).resolve().parent
ROOT_DIR = TOOLS_DIR.parent.parent


# ---------------------------------------------------------------- 规范化

def norm_path(path):
    """路径相等前的最小规范化:反斜杠→斜杠,剥前导 './' 与 '/'。"""
    if path is None:
        return ""
    p = str(path).strip().replace("\\", "/")
    while p.startswith("./"):
        p = p[2:]
    return p.lstrip("/")


def norm_cat(category):
    return "" if category is None else str(category).strip().upper()


def load_aliases(path):
    """{标注类别: [可接受的模型类别, ...]};以 '_' 开头的键是注释,忽略。"""
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    table = data.get("aliases", data)
    return {
        norm_cat(k): {norm_cat(v) for v in vals}
        for k, vals in table.items()
        if not str(k).startswith("_")
    }


# ---------------------------------------------------------------- 命中判定

def expected_range(exp):
    start = int(exp["line"])
    end = int(exp.get("lineEnd") or start)  # lineEnd 缺省 = line(D2)
    return (start, max(start, end))


def model_range(finding):
    start = finding.get("lineStart")
    if start is None:
        return None  # 模型行号为 null ⇒ 不命中(D3)
    end = finding.get("lineEnd")
    start = int(start)
    end = start if end is None else int(end)
    return (start, max(start, end))


def allowed_categories(exp, aliases):
    cat = norm_cat(exp.get("category"))
    allowed = {cat} | aliases.get(cat, set())
    allowed |= {norm_cat(c) for c in (exp.get("categoryEquivalents") or [])}
    return allowed


def hits(finding, exp, aliases):
    if norm_path(finding.get("filePath")) != norm_path(exp.get("path")):
        return False
    if norm_cat(finding.get("category")) not in allowed_categories(exp, aliases):
        return False
    fr = model_range(finding)
    if fr is None:
        return False
    er = expected_range(exp)
    return fr[0] <= er[1] and er[0] <= fr[1]  # 区间交集非空


def match_case(expected, findings, aliases):
    """贪心 1:1 匹配。返回 (matches, missed_expected_idx, unmatched_finding_idx)。

    确定性:两侧各按(文件, 行, 类别, 原序号)排序;按序为每个预期取第一个未被占用且命中的模型 finding。
    """
    exp_order = sorted(
        range(len(expected)),
        key=lambda i: (norm_path(expected[i].get("path")), expected_range(expected[i]),
                       norm_cat(expected[i].get("category")), i),
    )
    fnd_order = sorted(
        range(len(findings)),
        key=lambda i: (norm_path(findings[i].get("filePath")),
                       model_range(findings[i]) or (1 << 30, 1 << 30),
                       norm_cat(findings[i].get("category")), i),
    )
    taken = set()
    matches = []  # (expected_idx, finding_idx)
    for ei in exp_order:
        for fi in fnd_order:
            if fi in taken:
                continue
            if hits(findings[fi], expected[ei], aliases):
                taken.add(fi)
                matches.append((ei, fi))
                break
    matched_e = {ei for ei, _ in matches}
    missed = [i for i in range(len(expected)) if i not in matched_e]
    unmatched = [i for i in range(len(findings)) if i not in taken]
    return matches, missed, unmatched


# ---------------------------------------------------------------- 跑分聚合

def rate(numerator, denominator):
    return None if denominator == 0 else round(numerator / denominator, 4)


def extract_issues(doc):
    """兼容取模型 findings:标准包为 doc['report']['issues']。"""
    for probe in (
        lambda d: d["report"]["issues"],
        lambda d: d["report"]["data"]["issues"],
        lambda d: d["issues"],
    ):
        try:
            issues = probe(doc)
        except (KeyError, TypeError):
            continue
        if isinstance(issues, list):
            return issues
    return None


class Tally:
    """一组用例的两率累加器(全量 / 分 split / 分类别共用)。"""

    def __init__(self):
        self.expected_total = 0
        self.expected_missed = 0
        self.model_total = 0
        self.model_unmatched = 0

    def as_dict(self):
        return {
            "expectedFindings": self.expected_total,
            "missedExpected": self.expected_missed,
            "missRate": rate(self.expected_missed, self.expected_total),
            "modelFindings": self.model_total,
            "unmatchedModelFindings": self.model_unmatched,
            "falseReportRate": rate(self.model_unmatched, self.model_total),
        }


def finding_brief(finding):
    return {
        "severity": finding.get("severity"),
        "category": finding.get("category"),
        "filePath": finding.get("filePath"),
        "lineStart": finding.get("lineStart"),
        "lineEnd": finding.get("lineEnd"),
        "title": finding.get("title"),
    }


def score_corpus(manifest, runs, aliases):
    """manifest: dict;runs: {caseId: 响应包 dict 或 None};返回结果 dict。"""
    overall = Tally()
    by_split = {}
    miss_by_category = {}
    false_by_category = {}
    per_case = []
    not_run = []

    for case in manifest.get("cases", []):
        cid = case["id"]
        split = case.get("split") or "unknown"
        expected = case.get("expectedFindings") or []
        non_findings = case.get("nonFindings") or []
        doc = runs.get(cid)
        issues = extract_issues(doc) if doc is not None else None
        if issues is None:
            not_run.append({"caseId": cid, "split": split,
                            "reason": "无跑分文件或响应包缺 report.issues"})
            continue

        matches, missed, unmatched = match_case(expected, issues, aliases)

        overall.expected_total += len(expected)
        overall.expected_missed += len(missed)
        overall.model_total += len(issues)
        overall.model_unmatched += len(unmatched)

        st = by_split.setdefault(split, Tally())
        st.expected_total += len(expected)
        st.expected_missed += len(missed)
        st.model_total += len(issues)
        st.model_unmatched += len(unmatched)

        for i, exp in enumerate(expected):
            cat = norm_cat(exp.get("category"))
            ct = miss_by_category.setdefault(cat, Tally())
            ct.expected_total += 1
            if i in missed:
                ct.expected_missed += 1
        for i, finding in enumerate(issues):
            cat = norm_cat(finding.get("category")) or "(EMPTY)"
            ct = false_by_category.setdefault(cat, Tally())
            ct.model_total += 1
            if i in unmatched:
                ct.model_unmatched += 1

        # nonFindings 语义:该例所有未匹配的模型 findings 都是"不许报"红线的疑似违规,单列人工确认
        non_finding_alerts = [finding_brief(issues[i]) for i in unmatched] if non_findings else []

        per_case.append({
            "caseId": cid,
            "split": split,
            "expected": len(expected),
            "matched": len(matches),
            "missed": len(missed),
            "modelFindings": len(issues),
            "unmatchedModelFindings": len(unmatched),
            "missedExpected": [case["expectedFindings"][i] for i in missed],
            "unmatchedFindings": [finding_brief(issues[i]) for i in unmatched],
            "matchedPairs": [
                {"expected": expected[ei], "finding": finding_brief(issues[fi])}
                for ei, fi in sorted(matches)
            ],
            "nonFindings": non_findings,
            "nonFindingAlerts": non_finding_alerts,
        })

    return {
        "matchRuleVersion": MATCH_RULE_VERSION,
        "corpusVersion": manifest.get("corpusVersion"),
        "metricNote": ("漏报率=未命中预期/预期总数(1-recall);误报率=未匹配模型findings/模型findings总数"
                       "(1-precision);两指标独立呈报,禁止合成单一分数;"
                       "与 EvaluationMetrics.falsePositiveRate(FP/(FP+TN))是不同定义"),
        "overall": overall.as_dict(),
        "bySplit": {k: v.as_dict() for k, v in sorted(by_split.items())},
        "missRateByCategory": {k: v.as_dict() for k, v in sorted(miss_by_category.items())},
        "falseReportRateByCategory": {k: v.as_dict() for k, v in sorted(false_by_category.items())},
        "cases": per_case,
        "notRun": not_run,
        "scoredCases": len(per_case),
        "notRunCases": len(not_run),
    }


# ---------------------------------------------------------------- 输出

def fmt_rate(value):
    return "n/a" if value is None else f"{value * 100:.2f}%"


def render_md(result, label):
    lines = []
    o = result["overall"]
    lines.append(f"# 评测判分 {label}")
    lines.append("")
    lines.append(f"- 匹配规则: {result['matchRuleVersion']}(design.md D3;贪心 1:1,按文件+行排序确定性)")
    lines.append(f"- 语料版本: {result['corpusVersion']};判分用例 {result['scoredCases']},"
                 f"未跑成 {result['notRunCases']}(未跑成用例不进两率分母)")
    lines.append(f"- 口径: {result['metricNote']}")
    lines.append("")
    lines.append("## 全量两率")
    lines.append("")
    lines.append("| 指标 | 分子/分母 | 数值 |")
    lines.append("| --- | --- | --- |")
    lines.append(f"| 漏报率 | {o['missedExpected']}/{o['expectedFindings']} | {fmt_rate(o['missRate'])} |")
    lines.append(f"| 误报率 | {o['unmatchedModelFindings']}/{o['modelFindings']} | {fmt_rate(o['falseReportRate'])} |")
    lines.append("")
    lines.append("## 分 split")
    lines.append("")
    lines.append("| split | 漏报率 | 误报率 | 预期数 | 模型 findings 数 |")
    lines.append("| --- | --- | --- | --- | --- |")
    for split, t in result["bySplit"].items():
        lines.append(f"| {split} | {fmt_rate(t['missRate'])} | {fmt_rate(t['falseReportRate'])} "
                     f"| {t['expectedFindings']} | {t['modelFindings']} |")
    lines.append("")
    lines.append("## 分类别")
    lines.append("")
    lines.append("漏报率按**标注类别**统计,误报率按**模型输出类别**统计(两侧词表不同,分开列):")
    lines.append("")
    lines.append("| 标注类别 | 漏报率 | 未命中/预期 |")
    lines.append("| --- | --- | --- |")
    for cat, t in result["missRateByCategory"].items():
        lines.append(f"| {cat} | {fmt_rate(t['missRate'])} | {t['missedExpected']}/{t['expectedFindings']} |")
    lines.append("")
    lines.append("| 模型类别 | 误报率 | 未匹配/总数 |")
    lines.append("| --- | --- | --- |")
    for cat, t in result["falseReportRateByCategory"].items():
        lines.append(f"| {cat} | {fmt_rate(t['falseReportRate'])} "
                     f"| {t['unmatchedModelFindings']}/{t['modelFindings']} |")
    lines.append("")
    lines.append("## 逐例明细")
    lines.append("")
    lines.append("| 用例 | split | 预期 | 命中 | 漏报 | 模型 findings | 误报 | nonFindings 违规提示 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for row in result["cases"]:
        if row["nonFindings"]:
            alert = (f"疑似违规 {len(row['nonFindingAlerts'])} 条,须人工比对 nonFindings"
                     if row["nonFindingAlerts"] else "无未匹配 findings")
        else:
            alert = "-"
        lines.append(f"| {row['caseId']} | {row['split']} | {row['expected']} | {row['matched']} "
                     f"| {row['missed']} | {row['modelFindings']} | {row['unmatchedModelFindings']} "
                     f"| {alert} |")
    if result["notRun"]:
        lines.append("")
        lines.append("## 未跑成用例(不进两率,须补跑或在档案里声明)")
        lines.append("")
        for row in result["notRun"]:
            lines.append(f"- {row['caseId']} ({row['split']}): {row['reason']}")
    lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------- 自测

def selftest():
    aliases = {
        "RESOURCE_LEAK": {"PERFORMANCE_RISK", "UNKNOWN"},
        "NULLABILITY": {"NULL_POINTER"},
        "PATH_TRAVERSAL": {"AUTH_RISK", "UNKNOWN"},
    }

    def case(cid, split, expected, non_findings=None):
        return {"id": cid, "split": split, "expectedFindings": expected,
                "nonFindings": non_findings or []}

    def run(cid, issues):
        return {"caseId": cid, "report": {"issues": issues}}

    def exp(cat, path, line, line_end=None, equivalents=None):
        e = {"category": cat, "severity": "HIGH", "path": path, "line": line}
        if line_end is not None:
            e["lineEnd"] = line_end
        if equivalents is not None:
            e["categoryEquivalents"] = equivalents
        return e

    def fnd(cat, path, start, end=None, title=""):
        return {"category": cat, "severity": "HIGH", "filePath": path,
                "lineStart": start, "lineEnd": end, "title": title}

    manifest = {"corpusVersion": "selftest", "cases": [
        # A 精确命中(含路径规范化 './'):
        case("a-exact", "development", [exp("NULL_POINTER", "src/a.java", 10, 12)]),
        # B 全局别名命中(RESOURCE_LEAK ← PERFORMANCE_RISK):
        case("b-alias", "development", [exp("RESOURCE_LEAK", "src/b.java", 7)]),
        # C 用例级 categoryEquivalents 命中:
        case("c-equiv", "development", [exp("AUTH_RISK", "src/c.ts", 5, equivalents=["SQL_INJECTION"])]),
        # D 行区间不相交 → 漏报 + 误报:
        case("d-disjoint", "development", [exp("NULL_POINTER", "src/d.ts", 10, 12)]),
        # E 模型行号 null → 不命中:
        case("e-nullline", "development", [exp("NULL_POINTER", "src/e.ts", 3)]),
        # F 多对一贪心:两条 finding 都命中同一预期,只配 1 条,另一条计误报:
        case("f-greedy", "development", [exp("SQL_INJECTION", "src/f.java", 30, 40)]),
        # G 干净例(holdout):预期为空,模型报了 → 误报 + nonFindings 违规提示:
        case("g-clean", "holdout", [], non_findings=["参数化查询不许报"]),
    ]}
    runs = {
        "a-exact": run("a-exact", [fnd("NULL_POINTER", "./src/a.java", 11, 11, "t-a")]),
        "b-alias": run("b-alias", [fnd("PERFORMANCE_RISK", "src/b.java", 7, None, "t-b")]),
        "c-equiv": run("c-equiv", [fnd("SQL_INJECTION", "src/c.ts", 5, 5, "t-c")]),
        "d-disjoint": run("d-disjoint", [fnd("NULL_POINTER", "src/d.ts", 20, 25, "t-d")]),
        "e-nullline": run("e-nullline", [fnd("NULL_POINTER", "src/e.ts", None, None, "t-e")]),
        "f-greedy": run("f-greedy", [fnd("SQL_INJECTION", "src/f.java", 35, 36, "t-f2"),
                                     fnd("SQL_INJECTION", "src/f.java", 30, 31, "t-f1")]),
        "g-clean": run("g-clean", [fnd("BUSINESS_RULE_RISK", "src/g.py", 4, 4, "t-g")]),
    }

    result = score_corpus(manifest, runs, aliases)
    checks = []

    def check(name, actual, wanted_value):
        ok = actual == wanted_value
        checks.append((name, ok, actual, wanted_value))
        return ok

    o = result["overall"]
    # 预期共 6 条:A/B/C/F 命中,D/E 漏 → 漏报率 2/6
    check("overall.expectedFindings", o["expectedFindings"], 6)
    check("overall.missedExpected", o["missedExpected"], 2)
    check("overall.missRate", o["missRate"], round(2 / 6, 4))
    # 模型共 8 条:4 条匹配 → 误报率 4/8
    check("overall.modelFindings", o["modelFindings"], 8)
    check("overall.unmatchedModelFindings", o["unmatchedModelFindings"], 4)
    check("overall.falseReportRate", o["falseReportRate"], 0.5)
    # holdout 只有干净例:预期 0 → 漏报率 n/a;误报率 1/1
    holdout = result["bySplit"]["holdout"]
    check("holdout.missRate", holdout["missRate"], None)
    check("holdout.falseReportRate", holdout["falseReportRate"], 1.0)
    # 贪心确定性:F 例按行排序,行 30 的 t-f1 被匹配,t-f2 计误报
    f_row = next(r for r in result["cases"] if r["caseId"] == "f-greedy")
    check("greedy.matchedTitle", f_row["matchedPairs"][0]["finding"]["title"], "t-f1")
    check("greedy.unmatchedTitle", f_row["unmatchedFindings"][0]["title"], "t-f2")
    # null 行号不命中
    e_row = next(r for r in result["cases"] if r["caseId"] == "e-nullline")
    check("nullline.missed", e_row["missed"], 1)
    # 干净例的 nonFindings 违规提示
    g_row = next(r for r in result["cases"] if r["caseId"] == "g-clean")
    check("clean.alerts", len(g_row["nonFindingAlerts"]), 1)
    # 分类别:RESOURCE_LEAK 漏报 0/1;NULL_POINTER 漏报 2/3
    check("cat.resourceLeak.missRate", result["missRateByCategory"]["RESOURCE_LEAK"]["missRate"], 0.0)
    check("cat.nullPointer.missRate", result["missRateByCategory"]["NULL_POINTER"]["missRate"], round(2 / 3, 4))

    failed = [c for c in checks if not c[1]]
    for name, ok, actual, wanted_value in checks:
        print(f"  [{'ok' if ok else 'FAIL'}] {name}: got={actual!r} want={wanted_value!r}")
    if failed:
        print(f"SELFTEST FAILED ({len(failed)}/{len(checks)})")
        return 1
    print(f"SELFTEST OK ({len(checks)} checks)")
    return 0


# ---------------------------------------------------------------- 入口

def main(argv=None):
    parser = argparse.ArgumentParser(description="r7 评测判分器(design.md D3 口径)")
    parser.add_argument("--manifest", default=str(ROOT_DIR / "evaluation" / "manifest.json"))
    parser.add_argument("--runs", help="baseline-runs/<date>/ 目录(含每例 <id>.json)")
    parser.add_argument("--aliases", default=str(TOOLS_DIR / "category-aliases.json"))
    parser.add_argument("--out-dir", help="scores 输出目录,默认 = runs 目录的父目录")
    parser.add_argument("--label", help="输出文件名日期标签,默认 = runs 目录名")
    parser.add_argument("--selftest", action="store_true", help="跑内置小矩阵自测并退出")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    if not args.runs:
        parser.error("非 --selftest 模式必须给 --runs")
    runs_dir = Path(args.runs)
    if not runs_dir.is_dir():
        print(f"runs 目录不存在: {runs_dir}", file=sys.stderr)
        return 1

    with open(args.manifest, encoding="utf-8") as fh:
        manifest = json.load(fh)
    aliases = load_aliases(args.aliases)

    runs = {}
    for case in manifest.get("cases", []):
        path = runs_dir / f"{case['id']}.json"
        if path.is_file():
            try:
                with open(path, encoding="utf-8") as fh:
                    runs[case["id"]] = json.load(fh)
            except json.JSONDecodeError:
                runs[case["id"]] = None

    result = score_corpus(manifest, runs, aliases)
    result["runsDir"] = str(runs_dir)
    result["aliasesFile"] = str(args.aliases)
    result["generatedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()

    label = args.label or runs_dir.name
    out_dir = Path(args.out_dir) if args.out_dir else runs_dir.parent
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / f"scores-{label}.json"
    md_path = out_dir / f"scores-{label}.md"
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write(render_md(result, label))

    o = result["overall"]
    print(f"判分完成: {result['scoredCases']} 例(未跑成 {result['notRunCases']})")
    print(f"  漏报率 {fmt_rate(o['missRate'])} ({o['missedExpected']}/{o['expectedFindings']})")
    print(f"  误报率 {fmt_rate(o['falseReportRate'])} ({o['unmatchedModelFindings']}/{o['modelFindings']})")
    print(f"  → {json_path}")
    print(f"  → {md_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
