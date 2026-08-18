#!/usr/bin/env python3
"""d3-v1 evaluator with P8 AC coverage, run metadata, and ai_call_log support.

The historical findings matcher and CLI remain the source of truth.  P8 adds
optional fields without creating a second evaluator or changing d3-v1.
"""

import argparse
import csv
import datetime
import json
import math
import re
import sys
from collections import defaultdict
from pathlib import Path

MATCH_RULE_VERSION = "d3-v1"
AC_VERDICTS = ("COVERED", "NOT_FOUND", "AT_RISK")
TOOLS_DIR = Path(__file__).resolve().parent
ROOT_DIR = TOOLS_DIR.parent.parent
_MISSING = object()


# ---------------------------------------------------------------- normalization


def norm_path(path):
    """Minimal path normalization used by d3-v1."""
    if path is None:
        return ""
    value = str(path).strip().replace("\\", "/")
    while value.startswith("./"):
        value = value[2:]
    return value.lstrip("/")


def norm_cat(category):
    return "" if category is None else str(category).strip().upper()


def norm_verdict(verdict):
    if verdict is None:
        return None
    value = str(verdict).strip().upper().replace("-", "_").replace(" ", "_")
    return value if value in AC_VERDICTS else None


def load_aliases(path):
    """Load {annotation category: accepted model categories}."""
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    table = data.get("aliases", data)
    return {
        norm_cat(key): {norm_cat(value) for value in values}
        for key, values in table.items()
        if not str(key).startswith("_")
    }


# ---------------------------------------------------------------- d3-v1 finding matching


def expected_range(expected):
    start = int(expected["line"])
    end = int(expected.get("lineEnd") or start)
    return start, max(start, end)


def model_range(finding):
    start = finding.get("lineStart")
    if start is None:
        return None
    end = finding.get("lineEnd")
    start = int(start)
    end = start if end is None else int(end)
    return start, max(start, end)


def allowed_categories(expected, aliases):
    category = norm_cat(expected.get("category"))
    return ({category} | aliases.get(category, set()) |
            {norm_cat(value) for value in (expected.get("categoryEquivalents") or [])})


def hits(finding, expected, aliases):
    if norm_path(finding.get("filePath")) != norm_path(expected.get("path")):
        return False
    if norm_cat(finding.get("category")) not in allowed_categories(expected, aliases):
        return False
    finding_range = model_range(finding)
    if finding_range is None:
        return False
    expected_range_value = expected_range(expected)
    return (finding_range[0] <= expected_range_value[1] and
            expected_range_value[0] <= finding_range[1])


def match_case(expected, findings, aliases):
    """Deterministic greedy 1:1 matching used by d3-v1."""
    expected_order = sorted(
        range(len(expected)),
        key=lambda index: (norm_path(expected[index].get("path")),
                           expected_range(expected[index]),
                           norm_cat(expected[index].get("category")), index),
    )
    finding_order = sorted(
        range(len(findings)),
        key=lambda index: (norm_path(findings[index].get("filePath")),
                           model_range(findings[index]) or (1 << 30, 1 << 30),
                           norm_cat(findings[index].get("category")), index),
    )
    taken = set()
    matches = []
    for expected_index in expected_order:
        for finding_index in finding_order:
            if finding_index in taken:
                continue
            if hits(findings[finding_index], expected[expected_index], aliases):
                taken.add(finding_index)
                matches.append((expected_index, finding_index))
                break
    matched_expected = {expected_index for expected_index, _ in matches}
    missed = [index for index in range(len(expected)) if index not in matched_expected]
    unmatched = [index for index in range(len(findings)) if index not in taken]
    return matches, missed, unmatched


# ---------------------------------------------------------------- envelope extraction


def rate(numerator, denominator):
    return None if denominator == 0 else round(numerator / denominator, 4)


def _parse_json_string(value):
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = json.loads(value)
    except (TypeError, json.JSONDecodeError):
        return None
    return parsed if isinstance(parsed, (dict, list)) else None


def _iter_envelopes(document):
    """Yield known response wrappers, including historical report.data forms."""
    if document is None:
        return
    queue = [document]
    seen = set()
    wrapper_keys = ("report", "data", "result", "response", "payload", "body",
                    "rawResponse", "raw_response")
    while queue:
        node = queue.pop(0)
        if isinstance(node, str):
            node = _parse_json_string(node)
        if not isinstance(node, (dict, list)):
            continue
        marker = id(node)
        if marker in seen:
            continue
        seen.add(marker)
        if isinstance(node, dict):
            yield node
            for key in wrapper_keys:
                value = node.get(key, _MISSING)
                if isinstance(value, (dict, list, str)):
                    queue.append(value)
        else:
            for value in node:
                if isinstance(value, (dict, list, str)):
                    queue.append(value)


def extract_issues(document):
    """Extract findings from current and legacy envelopes."""
    for envelope in _iter_envelopes(document):
        for key in ("issues", "findings"):
            value = envelope.get(key, _MISSING)
            if isinstance(value, list):
                return value
    return None


def _coverage_items(raw):
    if raw is None:
        return []
    if isinstance(raw, list):
        return raw
    if not isinstance(raw, dict):
        return []
    for key in ("coverage", "items", "predictions", "acceptanceCriteria", "acs"):
        value = raw.get(key, _MISSING)
        if value is not _MISSING:
            if isinstance(value, list):
                return value
            if isinstance(value, dict):
                return _coverage_items(value)
            return []
    if any(key in raw for key in ("acId", "acID", "criterionId", "acceptanceCriteriaId")):
        return [raw]
    # Compatibility with an early {"AC1":"COVERED"} exporter.
    if raw and all(isinstance(value, (str, dict)) for value in raw.values()):
        return [{"acId": key, "verdict": value} for key, value in raw.items()]
    return []


def extract_coverage(document):
    """Return normalized raw coverage records, or None when the field is absent."""
    for envelope in _iter_envelopes(document):
        if "coverage" not in envelope:
            continue
        value = envelope.get("coverage")
        if value is None:
            continue
        return _coverage_items(value)
    return None


def _coverage_ac_id(item):
    if isinstance(item, str):
        return None
    if not isinstance(item, dict):
        return None
    for key in ("acId", "acID", "criterionId", "acceptanceCriteriaId",
                "acceptanceCriterionId", "id"):
        value = item.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return None


def _coverage_verdict(item):
    if isinstance(item, str):
        return norm_verdict(item)
    if not isinstance(item, dict):
        return None
    for key in ("verdict", "predictedVerdict", "prediction", "status", "value"):
        value = item.get(key)
        if isinstance(value, dict):
            value = value.get("verdict", value.get("value"))
        verdict = norm_verdict(value)
        if verdict is not None:
            return verdict
        if value is not None and str(value).strip():
            return str(value).strip().upper()
    return None


def _normalize_coverage_records(records):
    return [{"acId": _coverage_ac_id(item), "verdict": _coverage_verdict(item)}
            for item in (records or [])]


class Tally:
    """Finding two-rate accumulator used for overall/split/category views."""

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


class AcTally:
    """AC exact-hit and one-vs-rest precision/recall accumulator."""

    def __init__(self):
        self.total_ac = 0
        self.scored_ac = 0
        self.exact_matched = 0
        self.missing_predictions = 0
        self.invalid_predictions = 0
        self.invalid_truth = 0
        self.by_verdict = {
            verdict: {"support": 0, "predicted": 0, "truePositive": 0,
                      "falsePositive": 0, "falseNegative": 0}
            for verdict in AC_VERDICTS
        }

    def add(self, row):
        self.total_ac += row.get("totalAC", 0)
        self.scored_ac += row.get("scoredAC", 0)
        self.exact_matched += row.get("exactMatchedAC", 0)
        self.missing_predictions += row.get("missingPredictions", 0)
        self.invalid_predictions += row.get("invalidPredictions", 0)
        self.invalid_truth += row.get("invalidTruth", 0)
        for verdict in AC_VERDICTS:
            source = row.get("byVerdict", {}).get(verdict, {})
            for field in self.by_verdict[verdict]:
                self.by_verdict[verdict][field] += source.get(field, 0)

    def as_dict(self):
        by_verdict = {}
        for verdict in AC_VERDICTS:
            counts = dict(self.by_verdict[verdict])
            counts["precision"] = rate(counts["truePositive"],
                                        counts["truePositive"] + counts["falsePositive"])
            counts["recall"] = rate(counts["truePositive"],
                                     counts["truePositive"] + counts["falseNegative"])
            by_verdict[verdict] = counts
        return {
            "totalAC": self.total_ac,
            "scoredAC": self.scored_ac,
            "exactMatchedAC": self.exact_matched,
            "acHitRate": rate(self.exact_matched, self.scored_ac),
            "missingPredictions": self.missing_predictions,
            "invalidPredictions": self.invalid_predictions,
            "invalidTruth": self.invalid_truth,
            "byVerdict": by_verdict,
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


# ---------------------------------------------------------------- AC scoring


def _criteria_map(case):
    result = {}
    invalid = 0
    for criterion in case.get("acceptanceCriteria") or []:
        if not isinstance(criterion, dict):
            invalid += 1
            continue
        value = criterion.get("id", criterion.get("acId"))
        if value is None or not str(value).strip():
            invalid += 1
            continue
        key = str(value).strip()
        if key in result:
            invalid += 1
            continue
        result[key] = criterion
    return result, invalid


def score_acceptance_criteria(case, coverage):
    """Score valid predicted verdicts without inventing missing NOT_FOUND values."""
    criteria, invalid_truth = _criteria_map(case)
    truth = {}
    for row in case.get("consistencyTruth") or []:
        if not isinstance(row, dict):
            invalid_truth += 1
            continue
        ac_id = row.get("acId", row.get("id"))
        verdict = norm_verdict(row.get("verdict"))
        if ac_id is None or not str(ac_id).strip() or verdict is None:
            invalid_truth += 1
            continue
        ac_id = str(ac_id).strip()
        if criteria and ac_id not in criteria:
            invalid_truth += 1
            continue
        if ac_id in truth:
            invalid_truth += 1
            continue
        truth[ac_id] = verdict

    predictions = {}
    invalid_predictions = 0
    invalid_prediction_rows = []
    normalized = _normalize_coverage_records(coverage or [])
    for row in normalized:
        ac_id = row.get("acId")
        verdict = row.get("verdict")
        if ac_id is None or ac_id not in truth:
            invalid_predictions += 1
            invalid_prediction_rows.append(row)
            continue
        if ac_id in predictions:
            invalid_predictions += 1
            invalid_prediction_rows.append(row)
            continue
        if verdict not in AC_VERDICTS:
            # An identified AC with an invalid verdict is still a model
            # prediction: count it in the denominator, but never as a hit.
            predictions[ac_id] = "__INVALID__"
            invalid_predictions += 1
            invalid_prediction_rows.append(row)
            continue
        predictions[ac_id] = verdict

    by_verdict = {
        verdict: {"support": 0, "predicted": 0, "truePositive": 0,
                  "falsePositive": 0, "falseNegative": 0}
        for verdict in AC_VERDICTS
    }
    exact = 0
    scored = 0
    missing = 0
    for ac_id, truth_verdict in truth.items():
        by_verdict[truth_verdict]["support"] += 1
        if ac_id not in predictions:
            missing += 1
            by_verdict[truth_verdict]["falseNegative"] += 1
            continue
        predicted = predictions[ac_id]
        scored += 1
        if predicted not in AC_VERDICTS:
            by_verdict[truth_verdict]["falseNegative"] += 1
            continue
        by_verdict[predicted]["predicted"] += 1
        if predicted == truth_verdict:
            exact += 1
            by_verdict[truth_verdict]["truePositive"] += 1
        else:
            by_verdict[truth_verdict]["falseNegative"] += 1
            by_verdict[predicted]["falsePositive"] += 1

    for counts in by_verdict.values():
        counts["precision"] = rate(counts["truePositive"],
                                    counts["truePositive"] + counts["falsePositive"])
        counts["recall"] = rate(counts["truePositive"],
                                 counts["truePositive"] + counts["falseNegative"])

    return {
        "totalAC": len(truth),
        "scoredAC": scored,
        "exactMatchedAC": exact,
        "acHitRate": rate(exact, scored),
        "missingPredictions": missing,
        "invalidPredictions": invalid_predictions,
        "invalidTruth": invalid_truth,
        "coveragePresent": coverage is not None,
        "predictions": normalized,
        "invalidPredictionRows": invalid_prediction_rows,
        "byVerdict": by_verdict,
        "exactHit": exact == scored and scored > 0,
    }


# ---------------------------------------------------------------- run metadata / ai_call_log


def _metadata_lookup(metadata, names):
    if not isinstance(metadata, dict):
        return _MISSING
    containers = [metadata]
    for key in ("fixedRun", "run", "manifest", "versions", "metadata"):
        value = metadata.get(key)
        if isinstance(value, dict):
            containers.append(value)
    for container in containers:
        for name in names:
            if name in container:
                return container[name]
    return _MISSING


def _same_value(actual, expected):
    if isinstance(actual, bool) or isinstance(expected, bool):
        return actual == expected
    if isinstance(actual, (int, float)) and isinstance(expected, (int, float)):
        return math.isclose(float(actual), float(expected), rel_tol=0, abs_tol=1e-9)
    if isinstance(expected, (int, float)) and isinstance(actual, str):
        try:
            return math.isclose(float(actual), float(expected), rel_tol=0, abs_tol=1e-9)
        except ValueError:
            pass
    return str(actual).strip() == str(expected).strip()


def validate_run_metadata(manifest, metadata):
    """Check fixedRun values while keeping old runs valid when metadata is absent."""
    if metadata is None:
        return {"provided": False, "status": "missing", "consistent": True,
                "complete": False, "checks": [], "missing": [], "mismatches": []}
    fixed = manifest.get("fixedRun") or {}
    runtime = manifest.get("runtimeMetadata") or {}
    expected = {
        "corpusVersion": manifest.get("corpusVersion"),
        "schemaVersion": manifest.get("schemaVersion"),
        "model": fixed.get("model"),
        "temperature": fixed.get("temperature"),
        "toolImage": fixed.get("toolImage"),
        "promptVersion": fixed.get("promptVersion") or runtime.get("promptVersion"),
        "findingSchemaVersion": fixed.get("findingSchemaVersion"),
    }
    aliases = {
        "corpusVersion": ("corpusVersion", "corpus", "manifestVersion"),
        "schemaVersion": ("schemaVersion", "manifestSchemaVersion"),
        "model": ("model", "modelName"),
        "temperature": ("temperature", "temp"),
        "toolImage": ("toolImage", "tool_image", "toolImageDigest"),
        "promptVersion": ("promptVersion", "prompt_version"),
        "findingSchemaVersion": ("findingSchemaVersion", "finding_schema_version"),
    }
    checks = []
    missing = []
    mismatches = []
    for field, wanted in expected.items():
        if wanted is None:
            continue
        actual = _metadata_lookup(metadata, aliases[field])
        check = {"field": field, "expected": wanted}
        if actual is _MISSING or actual is None or actual == "":
            check["status"] = "missing"
            missing.append(field)
        elif _same_value(actual, wanted):
            check.update(actual=actual, status="ok")
        else:
            check.update(actual=actual, status="mismatch")
            mismatches.append(field)
        checks.append(check)
    consistent = not mismatches
    complete = not missing
    status = "ok" if consistent and complete else ("mismatch" if mismatches else "warning")
    summary = {"provided": True, "status": status, "consistent": consistent,
               "complete": complete, "checks": checks, "missing": missing,
               "mismatches": mismatches}
    for key in ("runId", "arm", "startedAt", "finishedAt", "scoredCases", "notRunCases"):
        if key in metadata:
            summary[key] = metadata[key]
    return summary


def _normal_key(value):
    return re.sub(r"[^a-z0-9]", "", str(value or "").lower())


def _row_value(row, names):
    normalized = {_normal_key(key): value for key, value in row.items()}
    for name in names:
        value = normalized.get(_normal_key(name), _MISSING)
        if value is not _MISSING and value != "":
            return value
    return _MISSING


def _number(value):
    if value is _MISSING or value is None or value == "":
        return None
    try:
        numeric = float(str(value).strip())
    except (TypeError, ValueError):
        return None
    return int(numeric) if numeric.is_integer() else numeric


def _ai_bucket():
    return {"calls": 0, "successes": 0, "failures": 0,
            "promptTokens": None, "completionTokens": None,
            "totalTokens": None, "latencyMs": None}


def _add_metric(bucket, name, value):
    if value is None:
        return
    bucket[name] = (bucket[name] or 0) + value


def summarize_ai_call_log(path, case_by_task=None):
    """Summarize the existing ai_call_log summary/rows CSV or JSON export."""
    case_by_task = case_by_task or {}
    source = Path(path)
    try:
        if source.suffix.lower() == ".json":
            with open(source, encoding="utf-8") as fh:
                payload = json.load(fh)
            rows = payload.get("rows") or payload.get("calls") or [] if isinstance(payload, dict) else payload
        else:
            with open(source, encoding="utf-8", newline="") as fh:
                rows = list(csv.DictReader(fh))
    except (OSError, json.JSONDecodeError, csv.Error) as exc:
        return {"available": False, "source": str(source), "reason": str(exc)}
    if not isinstance(rows, list):
        return {"available": False, "source": str(source), "reason": "日志不是行列表"}

    overall = _ai_bucket()
    by_request_type = defaultdict(_ai_bucket)
    by_case = defaultdict(_ai_bucket)
    for row in rows:
        if not isinstance(row, dict):
            continue
        calls_value = _number(_row_value(row, ("calls", "count", "callCount")))
        calls = int(calls_value) if calls_value is not None else 1
        status = str(_row_value(row, ("status", "result")) or "").strip().upper()
        prompt = _number(_row_value(row, ("prompt_tokens", "promptTokens", "input_tokens", "inputTokens")))
        completion = _number(_row_value(row, ("completion_tokens", "completionTokens", "output_tokens", "outputTokens")))
        total_tokens = _number(_row_value(row, ("total_tokens", "totalTokens", "tokens")))
        latency = _number(_row_value(row, ("latency_ms", "latencyMs", "duration_ms", "durationMs")))
        request_type = str(_row_value(row, ("request_type", "requestType", "type")) or "unknown")
        task_id = _row_value(row, ("task_id", "taskId"))
        case_id = _row_value(row, ("case_id", "caseId"))
        if case_id is _MISSING and task_id is not _MISSING:
            case_id = case_by_task.get(str(task_id))
        buckets = [overall, by_request_type[request_type]]
        if case_id is not _MISSING and case_id not in (None, ""):
            buckets.append(by_case[str(case_id)])
        for bucket in buckets:
            bucket["calls"] += calls
            if status in ("SUCCESS", "SUCCEEDED", "OK", "COMPLETED"):
                bucket["successes"] += calls
            elif status:
                bucket["failures"] += calls
            _add_metric(bucket, "promptTokens", prompt * calls if prompt is not None else None)
            _add_metric(bucket, "completionTokens", completion * calls if completion is not None else None)
            _add_metric(bucket, "totalTokens", total_tokens * calls if total_tokens is not None else None)
            _add_metric(bucket, "latencyMs", latency * calls if latency is not None else None)

    return {"available": True, "source": str(source), "rows": len(rows),
            "overall": overall,
            "byRequestType": {key: value for key, value in sorted(by_request_type.items())},
            "byCase": {key: value for key, value in sorted(by_case.items())}}


# ---------------------------------------------------------------- corpus aggregation


def score_corpus(manifest, runs, aliases, run_metadata=None, ai_call_log=None):
    """Score one manifest against {caseId: raw response} envelopes."""
    overall = Tally()
    by_split = {}
    miss_by_category = {}
    false_by_category = {}
    per_case = []
    not_run = []
    ac_overall = AcTally()
    ac_by_split = {}
    ac_cases = []
    task_to_case = {}

    for case in manifest.get("cases", []):
        cid = case["id"]
        document = runs.get(cid)
        if isinstance(document, dict):
            task_id = document.get("taskId")
            if task_id is None and isinstance(document.get("task"), dict):
                task_id = document["task"].get("taskId")
            if task_id is not None:
                task_to_case[str(task_id)] = cid

    for case in manifest.get("cases", []):
        cid = case["id"]
        split = case.get("split") or "unknown"
        expected = case.get("expectedFindings") or []
        non_findings = case.get("nonFindings") or []
        document = runs.get(cid)
        issues = extract_issues(document) if document is not None else None
        if issues is None:
            not_run.append({"caseId": cid, "split": split,
                            "reason": "无跑分文件或响应包缺 report.issues"})
            continue

        matches, missed, unmatched = match_case(expected, issues, aliases)
        coverage = extract_coverage(document)
        ac_row = score_acceptance_criteria(case, coverage)
        ac_overall.add(ac_row)
        ac_by_split.setdefault(split, AcTally()).add(ac_row)
        ac_cases.append({"caseId": cid, "split": split, **ac_row})

        overall.expected_total += len(expected)
        overall.expected_missed += len(missed)
        overall.model_total += len(issues)
        overall.model_unmatched += len(unmatched)

        split_tally = by_split.setdefault(split, Tally())
        split_tally.expected_total += len(expected)
        split_tally.expected_missed += len(missed)
        split_tally.model_total += len(issues)
        split_tally.model_unmatched += len(unmatched)

        for index, finding in enumerate(expected):
            category = norm_cat(finding.get("category"))
            tally = miss_by_category.setdefault(category, Tally())
            tally.expected_total += 1
            if index in missed:
                tally.expected_missed += 1
        for index, finding in enumerate(issues):
            category = norm_cat(finding.get("category")) or "(EMPTY)"
            tally = false_by_category.setdefault(category, Tally())
            tally.model_total += 1
            if index in unmatched:
                tally.model_unmatched += 1

        non_finding_alerts = [finding_brief(issues[index]) for index in unmatched] if non_findings else []
        per_case.append({
            "caseId": cid,
            "split": split,
            "expected": len(expected),
            "matched": len(matches),
            "missed": len(missed),
            "modelFindings": len(issues),
            "unmatchedModelFindings": len(unmatched),
            "missedExpected": [case["expectedFindings"][index] for index in missed],
            "unmatchedFindings": [finding_brief(issues[index]) for index in unmatched],
            "matchedPairs": [
                {"expected": expected[expected_index], "finding": finding_brief(issues[finding_index])}
                for expected_index, finding_index in sorted(matches)
            ],
            "nonFindings": non_findings,
            "nonFindingAlerts": non_finding_alerts,
            "acceptanceCriteria": ac_row,
        })

    ac_result = {
        "metricNote": ("AC 一致性命中率=模型 predicted verdict 与 consistencyTruth 的 exact-hit/有效比较数;"
                       "按 verdict 的 precision/recall 为 one-vs-rest;缺失 prediction 不猜测 verdict,"
                       "其对应 truth 只作为 recall 的 false negative;与 findings 两率及后端 falsePositiveRate 独立"),
        "overall": ac_overall.as_dict(),
        "bySplit": {key: value.as_dict() for key, value in sorted(ac_by_split.items())},
        "cases": ac_cases,
    }
    overall_dict = overall.as_dict()
    ac_overall_dict = ac_overall.as_dict()
    for key in ("totalAC", "scoredAC", "exactMatchedAC", "acHitRate",
                "missingPredictions", "invalidPredictions", "invalidTruth"):
        overall_dict[key] = ac_overall_dict[key]

    result = {
        "matchRuleVersion": MATCH_RULE_VERSION,
        "corpusVersion": manifest.get("corpusVersion"),
        "metricNote": ("漏报率=未命中预期/预期总数(1-recall);误报率=未匹配模型findings/模型findings总数"
                       "(1-precision);两指标独立呈报,禁止合成单一分数;"
                       "与 EvaluationMetrics.falsePositiveRate(FP/(FP+TN))是不同定义"),
        "overall": overall_dict,
        "bySplit": {key: value.as_dict() for key, value in sorted(by_split.items())},
        "missRateByCategory": {key: value.as_dict() for key, value in sorted(miss_by_category.items())},
        "falseReportRateByCategory": {key: value.as_dict() for key, value in sorted(false_by_category.items())},
        "cases": per_case,
        "notRun": not_run,
        "scoredCases": len(per_case),
        "notRunCases": len(not_run),
        # Both names are intentional: coverage is the P8 artifact vocabulary;
        # acceptanceCriteria mirrors the manifest field vocabulary.
        "coverage": ac_result,
        "acceptanceCriteria": ac_result,
        "runMetadata": validate_run_metadata(manifest, run_metadata),
    }
    if ai_call_log is False:
        result["aiCallLog"] = {"available": False, "reason": "未提供 ai_call_log 导出"}
    elif ai_call_log is not None:
        result["aiCallLog"] = summarize_ai_call_log(ai_call_log, task_to_case)
    return result


# ---------------------------------------------------------------- markdown output


def fmt_rate(value):
    return "n/a" if value is None else f"{value * 100:.2f}%"


def _metadata_lines(result):
    metadata = result.get("runMetadata") or {}
    if not metadata.get("provided"):
        return ["- 运行 metadata: 未提供（兼容历史 v1 跑分；未执行版本一致性断言）"]
    lines = [f"- 运行 metadata: {metadata.get('status')}"
             f"（consistent={metadata.get('consistent')}, complete={metadata.get('complete')}）"]
    if metadata.get("runId"):
        lines.append(f"- runId: {metadata['runId']}")
    if metadata.get("arm"):
        lines.append(f"- arm: {metadata['arm']}")
    if metadata.get("mismatches"):
        lines.append(f"- metadata 不一致: {', '.join(metadata['mismatches'])}")
    if metadata.get("missing"):
        lines.append(f"- metadata 缺失: {', '.join(metadata['missing'])}")
    return lines


def render_md(result, label):
    lines = []
    overall = result["overall"]
    lines.append(f"# 评测判分 {label}")
    lines.append("")
    lines.append(f"- 匹配规则: {result['matchRuleVersion']}(design.md D3;贪心 1:1,按文件+行排序确定性)")
    lines.append(f"- 语料版本: {result['corpusVersion']};判分用例 {result['scoredCases']},"
                 f"未跑成 {result['notRunCases']}(未跑成用例不进两率分母)")
    lines.extend(_metadata_lines(result))
    lines.append(f"- 口径: {result['metricNote']}")
    lines.append("")
    lines.append("## 全量两率")
    lines.append("")
    lines.append("| 指标 | 分子/分母 | 数值 |")
    lines.append("| --- | --- | --- |")
    lines.append(f"| 漏报率 | {overall['missedExpected']}/{overall['expectedFindings']} | {fmt_rate(overall['missRate'])} |")
    lines.append(f"| 误报率 | {overall['unmatchedModelFindings']}/{overall['modelFindings']} | {fmt_rate(overall['falseReportRate'])} |")
    lines.append("")
    lines.append("## AC 一致性")
    lines.append("")
    ac = result["coverage"]
    ac_overall = ac["overall"]
    lines.append("这是 predicted verdict 与 manifest consistencyTruth 的独立指标，不是 findings 漏报/误报率。")
    lines.append("")
    lines.append("| 指标 | 数量 | 数值 |")
    lines.append("| --- | --- | --- |")
    lines.append(f"| exact-hit | {ac_overall['exactMatchedAC']}/{ac_overall['scoredAC']} | {fmt_rate(ac_overall['acHitRate'])} |")
    lines.append(f"| 缺失 prediction | {ac_overall['missingPredictions']} | - |")
    lines.append(f"| 非法 prediction | {ac_overall['invalidPredictions']} | - |")
    lines.append("")
    lines.append("| verdict | support | predicted | precision | recall |")
    lines.append("| --- | ---: | ---: | ---: | ---: |")
    for verdict, row in ac_overall["byVerdict"].items():
        lines.append(f"| {verdict} | {row['support']} | {row['predicted']} "
                     f"| {fmt_rate(row['precision'])} | {fmt_rate(row['recall'])} |")
    lines.append("")
    lines.append("## 分 split")
    lines.append("")
    lines.append("| split | 漏报率 | 误报率 | 预期数 | 模型 findings 数 | AC 命中率 |")
    lines.append("| --- | --- | --- | --- | --- | --- |")
    for split, tally in result["bySplit"].items():
        split_ac = ac["bySplit"].get(split, {})
        lines.append(f"| {split} | {fmt_rate(tally['missRate'])} | {fmt_rate(tally['falseReportRate'])} "
                     f"| {tally['expectedFindings']} | {tally['modelFindings']} "
                     f"| {fmt_rate(split_ac.get('acHitRate'))} |")
    lines.append("")
    lines.append("## 分类别")
    lines.append("")
    lines.append("漏报率按**标注类别**统计,误报率按**模型输出类别**统计(两侧词表不同,分开列):")
    lines.append("")
    lines.append("| 标注类别 | 漏报率 | 未命中/预期 |")
    lines.append("| --- | --- | --- |")
    for category, tally in result["missRateByCategory"].items():
        lines.append(f"| {category} | {fmt_rate(tally['missRate'])} | {tally['missedExpected']}/{tally['expectedFindings']} |")
    lines.append("")
    lines.append("| 模型类别 | 误报率 | 未匹配/总数 |")
    lines.append("| --- | --- | --- |")
    for category, tally in result["falseReportRateByCategory"].items():
        lines.append(f"| {category} | {fmt_rate(tally['falseReportRate'])} "
                     f"| {tally['unmatchedModelFindings']}/{tally['modelFindings']} |")
    lines.append("")
    lines.append("## 逐例明细")
    lines.append("")
    lines.append("| 用例 | split | 预期 | 命中 | 漏报 | 模型 findings | 误报 | AC 命中率 | nonFindings 违规提示 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for row in result["cases"]:
        if row["nonFindings"]:
            alert = (f"疑似违规 {len(row['nonFindingAlerts'])} 条,须人工比对 nonFindings"
                     if row["nonFindingAlerts"] else "无未匹配 findings")
        else:
            alert = "-"
        ac_row = row.get("acceptanceCriteria", {})
        lines.append(f"| {row['caseId']} | {row['split']} | {row['expected']} | {row['matched']} "
                     f"| {row['missed']} | {row['modelFindings']} | {row['unmatchedModelFindings']} "
                     f"| {fmt_rate(ac_row.get('acHitRate'))} | {alert} |")
    if result["notRun"]:
        lines.append("")
        lines.append("## 未跑成用例(不进两率,须补跑或在档案里声明)")
        lines.append("")
        for row in result["notRun"]:
            lines.append(f"- {row['caseId']} ({row['split']}): {row['reason']}")
    ai_log = result.get("aiCallLog")
    if ai_log:
        lines.append("")
        lines.append("## ai_call_log")
        lines.append("")
        if not ai_log.get("available"):
            lines.append(f"- 未取得日志: {ai_log.get('reason', 'unknown')}")
        else:
            log_overall = ai_log.get("overall", {})
            lines.append(f"- 来源: {ai_log.get('source')}; rows={ai_log.get('rows')}")
            lines.append(f"- calls={log_overall.get('calls')}; successes={log_overall.get('successes')}; "
                         f"failures={log_overall.get('failures')}; totalTokens={log_overall.get('totalTokens')}; "
                         f"latencyMs={log_overall.get('latencyMs')}")
    lines.append("")
    return "\n".join(lines)


# ---------------------------------------------------------------- selftest


def selftest():
    aliases = {
        "RESOURCE_LEAK": {"PERFORMANCE_RISK", "UNKNOWN"},
        "NULLABILITY": {"NULL_POINTER"},
        "PATH_TRAVERSAL": {"AUTH_RISK", "UNKNOWN"},
    }

    def case(cid, split, expected, non_findings=None, acceptance=None, truth=None):
        value = {"id": cid, "split": split, "expectedFindings": expected,
                 "nonFindings": non_findings or []}
        if acceptance is not None:
            value["acceptanceCriteria"] = acceptance
        if truth is not None:
            value["consistencyTruth"] = truth
        return value

    def run(cid, issues, coverage=_MISSING, legacy=False):
        report = {"issues": issues}
        if coverage is not _MISSING:
            report["coverage"] = coverage
        if legacy:
            return {"caseId": cid, "report": {"data": report}}
        return {"caseId": cid, "report": report}

    def exp(category, path, line, line_end=None, equivalents=None):
        value = {"category": category, "severity": "HIGH", "path": path, "line": line}
        if line_end is not None:
            value["lineEnd"] = line_end
        if equivalents is not None:
            value["categoryEquivalents"] = equivalents
        return value

    def finding(category, path, start, end=None, title=""):
        return {"category": category, "severity": "HIGH", "filePath": path,
                "lineStart": start, "lineEnd": end, "title": title}

    ac_all = [{"id": "AC1", "text": "first"}, {"id": "AC2", "text": "second"}]
    truth_all = [{"acId": "AC1", "verdict": "COVERED"},
                 {"acId": "AC2", "verdict": "NOT_FOUND"}]
    ac_partial = [{"id": "AC1", "text": "first"}, {"id": "AC2", "text": "second"},
                  {"id": "AC3", "text": "third"}]
    truth_partial = [{"acId": "AC1", "verdict": "COVERED"},
                     {"acId": "AC2", "verdict": "AT_RISK"},
                     {"acId": "AC3", "verdict": "NOT_FOUND"}]
    manifest = {
        "corpusVersion": "selftest",
        "schemaVersion": "evaluation-manifest-v1",
        "fixedRun": {"model": "self-model", "temperature": 0,
                     "toolImage": "self-tools@sha256:test", "promptVersion": "self-prompt",
                     "findingSchemaVersion": "finding-v1"},
        "cases": [
            case("a-exact", "development", [exp("NULL_POINTER", "src/a.java", 10, 12)]),
            case("b-alias", "development", [exp("RESOURCE_LEAK", "src/b.java", 7)]),
            case("c-equiv", "development", [exp("AUTH_RISK", "src/c.ts", 5, equivalents=["SQL_INJECTION"])]),
            case("d-disjoint", "development", [exp("NULL_POINTER", "src/d.ts", 10, 12)]),
            case("e-nullline", "development", [exp("NULL_POINTER", "src/e.ts", 3)]),
            case("f-greedy", "development", [exp("SQL_INJECTION", "src/f.java", 30, 40)]),
            case("g-clean", "holdout", [], non_findings=["parameterized query must not be reported"]),
            case("h-ac-all", "development", [], acceptance=ac_all, truth=truth_all),
            case("i-ac-partial", "development", [], acceptance=ac_partial, truth=truth_partial),
            case("j-legacy-envelope", "holdout", [], acceptance=[{"id": "AC1", "text": "legacy"}],
                 truth=[{"acId": "AC1", "verdict": "COVERED"}]),
            case("k-ac-missing", "development", [], acceptance=[{"id": "AC1", "text": "missing"}],
                 truth=[{"acId": "AC1", "verdict": "COVERED"}]),
        ],
    }
    runs = {
        "a-exact": run("a-exact", [finding("NULL_POINTER", "./src/a.java", 11, 11, "t-a")]),
        "b-alias": run("b-alias", [finding("PERFORMANCE_RISK", "src/b.java", 7, None, "t-b")]),
        "c-equiv": run("c-equiv", [finding("SQL_INJECTION", "src/c.ts", 5, 5, "t-c")]),
        "d-disjoint": run("d-disjoint", [finding("NULL_POINTER", "src/d.ts", 20, 25, "t-d")]),
        "e-nullline": run("e-nullline", [finding("NULL_POINTER", "src/e.ts", None, None, "t-e")]),
        "f-greedy": run("f-greedy", [finding("SQL_INJECTION", "src/f.java", 35, 36, "t-f2"),
                                     finding("SQL_INJECTION", "src/f.java", 30, 31, "t-f1")]),
        "g-clean": run("g-clean", [finding("BUSINESS_RULE_RISK", "src/g.py", 4, 4, "t-g")]),
        "h-ac-all": run("h-ac-all", [], {"coverage": [
            {"acId": "AC1", "verdict": "COVERED"},
            {"acId": "AC2", "verdict": "NOT_FOUND"}]}),
        "i-ac-partial": run("i-ac-partial", [], {"coverage": [
            {"acId": "AC1", "verdict": "COVERED"},
            {"acId": "AC2", "verdict": "NOT_FOUND"},
            {"acId": "AC3", "verdict": "BROKEN"}]}),
        "j-legacy-envelope": run("j-legacy-envelope", [], {"coverage": [
            {"acId": "AC1", "verdict": "COVERED"}]}, legacy=True),
        "k-ac-missing": run("k-ac-missing", []),
    }

    result = score_corpus(manifest, runs, aliases)
    checks = []

    def check(name, actual, expected):
        checks.append((name, actual == expected, actual, expected))

    overall = result["overall"]
    check("overall.expectedFindings", overall["expectedFindings"], 6)
    check("overall.missedExpected", overall["missedExpected"], 2)
    check("overall.missRate", overall["missRate"], round(2 / 6, 4))
    check("overall.modelFindings", overall["modelFindings"], 8)
    check("overall.unmatchedModelFindings", overall["unmatchedModelFindings"], 4)
    check("overall.falseReportRate", overall["falseReportRate"], 0.5)
    check("holdout.missRate", result["bySplit"]["holdout"]["missRate"], None)
    check("holdout.falseReportRate", result["bySplit"]["holdout"]["falseReportRate"], 1.0)
    greedy = next(row for row in result["cases"] if row["caseId"] == "f-greedy")
    check("greedy.matchedTitle", greedy["matchedPairs"][0]["finding"]["title"], "t-f1")
    check("greedy.unmatchedTitle", greedy["unmatchedFindings"][0]["title"], "t-f2")
    null_line = next(row for row in result["cases"] if row["caseId"] == "e-nullline")
    check("nullline.missed", null_line["missed"], 1)
    clean = next(row for row in result["cases"] if row["caseId"] == "g-clean")
    check("clean.alerts", len(clean["nonFindingAlerts"]), 1)
    check("cat.resourceLeak.missRate", result["missRateByCategory"]["RESOURCE_LEAK"]["missRate"], 0.0)
    check("cat.nullPointer.missRate", result["missRateByCategory"]["NULL_POINTER"]["missRate"], round(2 / 3, 4))

    ac = result["coverage"]["overall"]
    check("ac.total", ac["totalAC"], 7)
    check("ac.scored", ac["scoredAC"], 6)
    check("ac.exact", ac["exactMatchedAC"], 4)
    check("ac.hitRate", ac["acHitRate"], round(4 / 6, 4))
    check("ac.missing", ac["missingPredictions"], 1)
    check("ac.invalid", ac["invalidPredictions"], 1)
    all_ac = next(row for row in result["cases"] if row["caseId"] == "h-ac-all")["acceptanceCriteria"]
    check("ac.all.rate", all_ac["acHitRate"], 1.0)
    partial_ac = next(row for row in result["cases"] if row["caseId"] == "i-ac-partial")["acceptanceCriteria"]
    check("ac.partial.rate", partial_ac["acHitRate"], round(1 / 3, 4))
    check("ac.partial.invalid", partial_ac["invalidPredictions"], 1)
    legacy_ac = next(row for row in result["cases"] if row["caseId"] == "j-legacy-envelope")["acceptanceCriteria"]
    check("ac.legacy.exact", legacy_ac["exactMatchedAC"], 1)
    missing_ac = next(row for row in result["cases"] if row["caseId"] == "k-ac-missing")["acceptanceCriteria"]
    check("ac.missing.rate", missing_ac["acHitRate"], None)
    check("ac.missing.recall", missing_ac["byVerdict"]["COVERED"]["recall"], 0.0)
    check("ac.zero.denominator", clean["acceptanceCriteria"]["acHitRate"], None)
    check("norm.single.backslash", norm_path(r".\src\a.java"), "src/a.java")

    metadata = {"runId": "selftest-run", "arm": "Baseline", "corpusVersion": "selftest",
                "schemaVersion": "evaluation-manifest-v1", "model": "self-model", "temperature": 0,
                "toolImage": "self-tools@sha256:test", "promptVersion": "self-prompt",
                "findingSchemaVersion": "finding-v1"}
    check("metadata.ok", validate_run_metadata(manifest, metadata)["status"], "ok")
    metadata["model"] = "other-model"
    check("metadata.mismatch", validate_run_metadata(manifest, metadata)["status"], "mismatch")

    failed = [item for item in checks if not item[1]]
    for name, ok, actual, expected in checks:
        print(f"  [{'ok' if ok else 'FAIL'}] {name}: got={actual!r} want={expected!r}")
    if failed:
        print(f"SELFTEST FAILED ({len(failed)}/{len(checks)})")
        return 1
    print(f"SELFTEST OK ({len(checks)} checks)")
    return 0


# ---------------------------------------------------------------- CLI


def _load_json_file(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def _auto_metadata_path(runs_dir):
    for name in ("run-metadata.json", "run_metadata.json", "metadata.json"):
        candidate = runs_dir / name
        if candidate.is_file():
            return candidate
    return None


def _auto_ai_log_path(runs_dir):
    for name in ("ai-call-log-summary.csv", "ai-call-log-rows.csv", "ai_call_log.csv", "ai-call-log.json"):
        candidate = runs_dir / name
        if candidate.is_file():
            return candidate
    return None


def main(argv=None):
    parser = argparse.ArgumentParser(description="d3-v1 evaluator with P8 AC coverage/metadata")
    parser.add_argument("--manifest", default=str(ROOT_DIR / "evaluation" / "manifest.json"))
    parser.add_argument("--runs", help="baseline-runs/<date>/ directory containing <case-id>.json")
    parser.add_argument("--aliases", default=str(TOOLS_DIR / "category-aliases.json"))
    parser.add_argument("--out-dir", help="scores output directory; default is the runs parent")
    parser.add_argument("--label", help="output label; default is the runs directory name")
    parser.add_argument("--metadata", "--run-metadata", dest="metadata",
                        help="run-metadata.json; auto-detected in runs directory when omitted")
    parser.add_argument("--ai-call-log", dest="ai_call_log",
                        help="ai_call_log CSV/JSON export; auto-detected when omitted")
    parser.add_argument("--strict-metadata", action="store_true",
                        help="return non-zero when metadata is missing/incomplete/mismatched")
    parser.add_argument("--selftest", action="store_true", help="run built-in matrix selftest and exit")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()
    if not args.runs:
        parser.error("non-selftest mode requires --runs")

    runs_dir = Path(args.runs)
    if not runs_dir.is_dir():
        print(f"runs directory does not exist: {runs_dir}", file=sys.stderr)
        return 1
    try:
        manifest = _load_json_file(args.manifest)
        aliases = load_aliases(args.aliases)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"failed to read manifest/aliases: {exc}", file=sys.stderr)
        return 1

    runs = {}
    for case in manifest.get("cases", []):
        path = runs_dir / f"{case['id']}.json"
        if path.is_file():
            try:
                runs[case["id"]] = _load_json_file(path)
            except (OSError, json.JSONDecodeError):
                runs[case["id"]] = None

    metadata_path = Path(args.metadata) if args.metadata else _auto_metadata_path(runs_dir)
    metadata = None
    if metadata_path:
        try:
            metadata = _load_json_file(metadata_path)
        except (OSError, json.JSONDecodeError) as exc:
            print(f"failed to read run metadata: {exc}", file=sys.stderr)
            return 1

    ai_log_path = Path(args.ai_call_log) if args.ai_call_log else _auto_ai_log_path(runs_dir)
    result = score_corpus(manifest, runs, aliases, metadata, ai_log_path if ai_log_path else False)
    result["runsDir"] = str(runs_dir)
    result["aliasesFile"] = str(args.aliases)
    result["metadataFile"] = str(metadata_path) if metadata_path else None
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

    overall = result["overall"]
    ac = result["coverage"]["overall"]
    print(f"判分完成: {result['scoredCases']} 例(未跑成 {result['notRunCases']})")
    print(f"  漏报率 {fmt_rate(overall['missRate'])} ({overall['missedExpected']}/{overall['expectedFindings']})")
    print(f"  误报率 {fmt_rate(overall['falseReportRate'])} ({overall['unmatchedModelFindings']}/{overall['modelFindings']})")
    print(f"  AC 一致性命中率 {fmt_rate(ac['acHitRate'])} ({ac['exactMatchedAC']}/{ac['scoredAC']})")
    print(f"  → {json_path}")
    print(f"  → {md_path}")
    if args.strict_metadata and result["runMetadata"].get("status") != "ok":
        print("strict metadata check failed", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
