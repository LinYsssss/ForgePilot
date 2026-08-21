#!/usr/bin/env python3
"""Deterministic ForgePilot V2 Phase 1 evaluation scorer.

The tool is deliberately self-contained and uses only the Python standard
library.  It validates the small development corpus, scores normalized run
envelopes, creates a deterministic reference report, and refuses corpus/run
inputs that cross the Phase 1 boundary.
"""

from __future__ import annotations

import argparse
import difflib
import json
import re
import sys
from collections import Counter, defaultdict
from copy import deepcopy
from pathlib import Path
from typing import Any


TOOL_DIR = Path(__file__).resolve().parent
EVALUATION_DIR = TOOL_DIR.parent
ROOT_DIR = EVALUATION_DIR.parent
DEFAULT_MANIFEST = EVALUATION_DIR / "manifest.quick.json"
DEFAULT_CASE_SET = EVALUATION_DIR / "case-sets" / "phase1-quick.json"
DEFAULT_ALIASES = TOOL_DIR / "category-aliases.json"
SOURCE_COMMIT = "96137dd3b43e14c5e8881c99688663afd979cf4e"
MANIFEST_VERSION = "forgepilot-evaluation-manifest-v1"
RUN_VERSION = "forgepilot-evaluation-run-v1"
REPORT_VERSION = "forgepilot-evaluation-score-report-v1"
CASE_SET_VERSION = "phase1-quick-v1"
MATCH_RULE_VERSION = "forgepilot-deterministic-match-v1"
ALLOWED_LANGUAGES = {"JAVA", "TYPESCRIPT", "PYTHON"}
ALLOWED_SPLIT = "development"
ALLOWED_STATUSES = {"COMPLETED", "FAILED", "NOT_RUN"}
ALLOWED_FAILURES = {"STRUCTURE", "PROVIDER", "TIMEOUT", "OTHER"}
ALLOWED_VERDICTS = {"COVERED", "NOT_FOUND", "AT_RISK"}
ALLOWED_FINDING_TYPES = {"REQUIREMENT", "CODE_QUALITY"}


class ContractError(ValueError):
    """Raised when a manifest, case set, or run violates its contract."""


def read_json(path: Path) -> Any:
    try:
        with path.open(encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError as exc:
        raise ContractError(f"missing JSON file: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ContractError(f"invalid JSON in {path}: {exc}") from exc


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")


def norm_path(value: Any) -> str:
    if value is None:
        return ""
    path = str(value).strip().replace("\\", "/")
    while path.startswith("./"):
        path = path[2:]
    return path.lstrip("/")


def norm_category(value: Any) -> str:
    return "" if value is None else str(value).strip().upper()


def ratio(numerator: int, denominator: int) -> float | None:
    if denominator == 0:
        return None
    return round(numerator / denominator, 6)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def reject_extra_keys(value: dict[str, Any], allowed: set[str], label: str) -> None:
    extra = set(value) - allowed
    require(not extra, f"{label} has unexpected fields: {sorted(extra)}")


def require_string(value: Any, label: str) -> str:
    require(isinstance(value, str) and bool(value.strip()), f"{label} must be a non-empty string")
    return value


def require_int(value: Any, label: str, *, nullable: bool = False) -> int | None:
    if nullable and value is None:
        return None
    require(isinstance(value, int) and not isinstance(value, bool), f"{label} must be an integer")
    require(value >= 1, f"{label} must be >= 1")
    return value


def load_aliases(path: Path) -> dict[str, set[str]]:
    data = read_json(path)
    require(isinstance(data, dict), "category aliases must be an object")
    table = data.get("aliases", data)
    require(isinstance(table, dict), "category aliases.aliases must be an object")
    aliases: dict[str, set[str]] = {}
    for key, values in table.items():
        if str(key).startswith("_"):
            continue
        require(isinstance(values, list), f"aliases for {key} must be an array")
        aliases[norm_category(key)] = {norm_category(item) for item in values}
    return aliases


def case_ids(manifest: dict[str, Any]) -> list[str]:
    return [case["id"] for case in manifest["cases"]]


def validate_expected_finding(finding: Any, label: str) -> None:
    require(isinstance(finding, dict), f"{label} must be an object")
    reject_extra_keys(
        finding,
        {"findingType", "category", "severity", "filePath", "lineStart", "lineEnd", "categoryAliases"},
        label,
    )
    required = {"findingType", "category", "severity", "filePath", "lineStart", "lineEnd"}
    require(required <= set(finding), f"{label} missing fields: {sorted(required - set(finding))}")
    require(finding["findingType"] in ALLOWED_FINDING_TYPES, f"{label}.findingType invalid")
    category = norm_category(finding["category"])
    require(bool(re.fullmatch(r"[A-Z][A-Z0-9_]*", category)), f"{label}.category invalid")
    require(finding["severity"] in {"LOW", "MEDIUM", "HIGH"}, f"{label}.severity invalid")
    require_string(finding["filePath"], f"{label}.filePath")
    start = require_int(finding["lineStart"], f"{label}.lineStart")
    end = require_int(finding["lineEnd"], f"{label}.lineEnd")
    require(end >= start, f"{label}.lineEnd precedes lineStart")
    aliases = finding.get("categoryAliases", [])
    require(isinstance(aliases, list), f"{label}.categoryAliases must be an array")
    require(len(aliases) == len(set(aliases)), f"{label}.categoryAliases contains duplicates")
    for alias in aliases:
        require(bool(re.fullmatch(r"[A-Z][A-Z0-9_]*", norm_category(alias))), f"{label} alias invalid")


def validate_ac_list(values: Any, label: str, value_field: str) -> list[dict[str, str]]:
    require(isinstance(values, list) and values, f"{label} must be a non-empty array")
    seen: set[str] = set()
    result: list[dict[str, str]] = []
    for index, value in enumerate(values):
        require(isinstance(value, dict), f"{label}[{index}] must be an object")
        reject_extra_keys(value, {"acKey", value_field}, f"{label}[{index}]")
        key = require_string(value.get("acKey"), f"{label}[{index}].acKey")
        require(bool(re.fullmatch(r"AC-[0-9]{4}", key)), f"{label}[{index}].acKey invalid")
        require(key not in seen, f"duplicate AC key {key} in {label}")
        seen.add(key)
        if value_field == "text":
            text = require_string(value.get("text"), f"{label}[{index}].text")
            result.append({"acKey": key, "text": text})
        else:
            verdict = value.get("verdict")
            require(verdict in ALLOWED_VERDICTS, f"{label}[{index}].verdict invalid")
            result.append({"acKey": key, "verdict": verdict})
    return result


def validate_manifest(manifest: Any, manifest_path: Path | None = None) -> dict[str, Any]:
    require(isinstance(manifest, dict), "manifest must be an object")
    reject_extra_keys(manifest, {"schemaVersion", "corpusVersion", "source", "cases"}, "manifest")
    require(manifest.get("schemaVersion") == MANIFEST_VERSION, "manifest schemaVersion is not V2")
    require_string(manifest.get("corpusVersion"), "manifest.corpusVersion")
    source = manifest.get("source")
    require(isinstance(source, dict), "manifest.source must be an object")
    reject_extra_keys(source, {"repository", "commit", "migrationPolicy"}, "manifest.source")
    require_string(source.get("repository"), "manifest.source.repository")
    require(source.get("commit") == SOURCE_COMMIT, "manifest source commit does not match the frozen Legacy commit")
    require(source.get("migrationPolicy") == "REWRITE_KEEP_DATA", "manifest migration policy is invalid")
    cases = manifest.get("cases")
    require(isinstance(cases, list) and len(cases) == 12, "quick manifest must contain exactly 12 cases")
    ids: set[str] = set()
    for index, case in enumerate(cases):
        label = f"manifest.cases[{index}]"
        require(isinstance(case, dict), f"{label} must be an object")
        reject_extra_keys(
            case,
            {
                "id", "split", "language", "fixture", "fixtureLayout", "selectionReason",
                "requirement", "acceptanceCriteria", "expectedAcVerdicts", "expectedFindings", "nonFindings",
            },
            label,
        )
        cid = require_string(case.get("id"), f"{label}.id")
        require(cid not in ids, f"duplicate case id: {cid}")
        ids.add(cid)
        require(case.get("split") == ALLOWED_SPLIT, f"{cid} is not a development case")
        require(case.get("language") in ALLOWED_LANGUAGES, f"{cid} language invalid")
        fixture = require_string(case.get("fixture"), f"{cid}.fixture")
        require(fixture == f"cases/{cid}", f"{cid}.fixture must point to its own case directory")
        require(case.get("fixtureLayout") in {"single", "base-head"}, f"{cid}.fixtureLayout invalid")
        require_string(case.get("selectionReason"), f"{cid}.selectionReason")
        requirement = case.get("requirement")
        require(isinstance(requirement, dict), f"{cid}.requirement must be an object")
        reject_extra_keys(requirement, {"title", "background", "description"}, f"{cid}.requirement")
        for field in ("title", "background", "description"):
            require_string(requirement.get(field), f"{cid}.requirement.{field}")
        acs = validate_ac_list(case.get("acceptanceCriteria"), f"{cid}.acceptanceCriteria", "text")
        truths = validate_ac_list(case.get("expectedAcVerdicts"), f"{cid}.expectedAcVerdicts", "verdict")
        ac_keys = {item["acKey"] for item in acs}
        truth_keys = {item["acKey"] for item in truths}
        require(ac_keys == truth_keys, f"{cid} AC truth keys do not match acceptance criteria")
        findings = case.get("expectedFindings")
        require(isinstance(findings, list), f"{cid}.expectedFindings must be an array")
        for f_index, finding in enumerate(findings):
            validate_expected_finding(finding, f"{cid}.expectedFindings[{f_index}]")
        non_findings = case.get("nonFindings")
        require(isinstance(non_findings, list), f"{cid}.nonFindings must be an array")
        for item in non_findings:
            require_string(item, f"{cid}.nonFindings item")
        forbidden_fields = {"expectedPatch", "consistencyTruth", "runtimeMetadata", "fixedRun"}
        require(not forbidden_fields.intersection(case), f"{cid} contains Legacy-only fields")
    if manifest_path is not None:
        for case in cases:
            fixture_dir = manifest_path.parent / case["fixture"]
            require(fixture_dir.is_dir(), f"missing fixture directory: {fixture_dir}")
            if case["fixtureLayout"] == "base-head":
                require((fixture_dir / "base").is_dir(), f"{case['id']} missing base fixture")
                require((fixture_dir / "head").is_dir(), f"{case['id']} missing head fixture")
            else:
                require(any(fixture_dir.iterdir()), f"{case['id']} fixture is empty")
    return manifest


def validate_case_set(case_set: Any, manifest: dict[str, Any]) -> dict[str, Any]:
    require(isinstance(case_set, dict), "case set must be an object")
    require(case_set.get("schemaVersion") == "forgepilot-evaluation-case-set-v1", "case set schemaVersion invalid")
    require(case_set.get("caseSetVersion") == CASE_SET_VERSION, "case set version invalid")
    require(case_set.get("manifestSchemaVersion") == MANIFEST_VERSION, "case set manifest schema mismatch")
    require(case_set.get("sourceCommit") == SOURCE_COMMIT, "case set source commit mismatch")
    require(case_set.get("allowedSplit") == ALLOWED_SPLIT, "case set may only allow development")
    ids = case_set.get("caseIds")
    require(isinstance(ids, list) and len(ids) == 12, "case set must contain exactly 12 IDs")
    require(len(ids) == len(set(ids)), "case set contains duplicate IDs")
    require(set(ids) == set(case_ids(manifest)), "case set IDs differ from manifest IDs")
    return case_set


def validate_corpus(manifest_path: Path, case_set_path: Path) -> dict[str, Any]:
    manifest = validate_manifest(read_json(manifest_path), manifest_path)
    validate_case_set(read_json(case_set_path), manifest)
    # The selected corpus is intentionally closed: an extra case directory is
    # a likely accidental import of a non-Phase-1 fixture.
    cases_dir = manifest_path.parent / "cases"
    expected_dirs = {case["id"] for case in manifest["cases"]}
    actual_dirs = {entry.name for entry in cases_dir.iterdir() if entry.is_dir()} if cases_dir.is_dir() else set()
    require(actual_dirs == expected_dirs, f"case directory set differs from quick manifest: extra={sorted(actual_dirs - expected_dirs)}, missing={sorted(expected_dirs - actual_dirs)}")
    return manifest


def expected_range(finding: dict[str, Any]) -> tuple[int, int]:
    start = int(finding["lineStart"])
    end = int(finding.get("lineEnd") or start)
    return start, max(start, end)


def predicted_range(finding: dict[str, Any]) -> tuple[int, int] | None:
    start = finding.get("lineStart")
    if start is None:
        return None
    start = int(start)
    end = finding.get("lineEnd")
    end = start if end is None else int(end)
    return start, max(start, end)


def allowed_categories(expected: dict[str, Any], aliases: dict[str, set[str]]) -> set[str]:
    category = norm_category(expected["category"])
    values = {category} | aliases.get(category, set())
    values |= {norm_category(item) for item in expected.get("categoryAliases", [])}
    return values


def finding_hits(predicted: dict[str, Any], expected: dict[str, Any], aliases: dict[str, set[str]]) -> bool:
    if predicted.get("findingType") != expected.get("findingType"):
        return False
    if norm_path(predicted.get("filePath")) != norm_path(expected.get("filePath")):
        return False
    if norm_category(predicted.get("category")) not in allowed_categories(expected, aliases):
        return False
    predicted_lines = predicted_range(predicted)
    if predicted_lines is None:
        return False
    expected_lines = expected_range(expected)
    return predicted_lines[0] <= expected_lines[1] and expected_lines[0] <= predicted_lines[1]


def match_findings(expected: list[dict[str, Any]], predicted: list[dict[str, Any]], aliases: dict[str, set[str]]) -> tuple[list[tuple[int, int]], list[int], list[int]]:
    expected_order = sorted(
        range(len(expected)),
        key=lambda index: (
            norm_path(expected[index].get("filePath")),
            expected_range(expected[index]),
            norm_category(expected[index].get("category")),
            expected[index].get("findingType", ""),
            index,
        ),
    )
    predicted_order = sorted(
        range(len(predicted)),
        key=lambda index: (
            norm_path(predicted[index].get("filePath")),
            predicted_range(predicted[index]) or (1 << 30, 1 << 30),
            norm_category(predicted[index].get("category")),
            predicted[index].get("findingType", ""),
            index,
        ),
    )
    taken: set[int] = set()
    matches: list[tuple[int, int]] = []
    for expected_index in expected_order:
        for predicted_index in predicted_order:
            if predicted_index in taken:
                continue
            if finding_hits(predicted[predicted_index], expected[expected_index], aliases):
                taken.add(predicted_index)
                matches.append((expected_index, predicted_index))
                break
    matched_expected = {item[0] for item in matches}
    missed = [index for index in range(len(expected)) if index not in matched_expected]
    unmatched = [index for index in range(len(predicted)) if index not in taken]
    return matches, missed, unmatched


def validate_run_case(result: Any, case: dict[str, Any]) -> dict[str, Any]:
    cid = case["id"]
    require(isinstance(result, dict), f"run case {cid} must be an object")
    reject_extra_keys(
        result,
        {"caseId", "status", "failureKind", "failureReason", "findings", "acVerdicts", "usage"},
        f"run case {cid}",
    )
    require(result.get("caseId") == cid, f"run case ID mismatch: expected {cid}")
    status = result.get("status")
    require(status in ALLOWED_STATUSES, f"{cid}.status invalid")
    failure_kind = result.get("failureKind")
    require(failure_kind is None or failure_kind in ALLOWED_FAILURES, f"{cid}.failureKind invalid")
    if status == "COMPLETED":
        require(failure_kind is None, f"completed case {cid} cannot have failureKind")
    elif status == "FAILED":
        require(failure_kind is not None, f"failed case {cid} must have failureKind")
    else:
        require(failure_kind is None, f"not-run case {cid} must not have failureKind")
    reason = result.get("failureReason")
    require(reason is None or isinstance(reason, str), f"{cid}.failureReason invalid")
    findings = result.get("findings")
    require(isinstance(findings, list), f"{cid}.findings must be an array")
    for index, finding in enumerate(findings):
        require(isinstance(finding, dict), f"{cid}.findings[{index}] must be an object")
        reject_extra_keys(
            finding,
            {"findingType", "category", "filePath", "lineStart", "lineEnd"},
            f"{cid}.findings[{index}]",
        )
        require(finding.get("findingType") in ALLOWED_FINDING_TYPES, f"{cid}.findings[{index}].findingType invalid")
        require(bool(re.fullmatch(r"[A-Z][A-Z0-9_]*", norm_category(finding.get("category")))), f"{cid}.findings[{index}].category invalid")
        require_string(finding.get("filePath"), f"{cid}.findings[{index}].filePath")
        require_int(finding.get("lineStart"), f"{cid}.findings[{index}].lineStart", nullable=True)
        require_int(finding.get("lineEnd"), f"{cid}.findings[{index}].lineEnd", nullable=True)
        if finding.get("lineStart") is not None and finding.get("lineEnd") is not None:
            require(finding["lineEnd"] >= finding["lineStart"], f"{cid}.finding line range invalid")
    ac_verdicts = result.get("acVerdicts")
    require(isinstance(ac_verdicts, list), f"{cid}.acVerdicts must be an array")
    expected_keys = {item["acKey"] for item in case["acceptanceCriteria"]}
    seen_keys: set[str] = set()
    for index, item in enumerate(ac_verdicts):
        require(isinstance(item, dict), f"{cid}.acVerdicts[{index}] must be an object")
        reject_extra_keys(item, {"acKey", "verdict"}, f"{cid}.acVerdicts[{index}]")
        key = item.get("acKey")
        require(key in expected_keys, f"{cid}.acVerdicts contains unknown AC {key}")
        require(key not in seen_keys, f"{cid}.acVerdicts contains duplicate AC {key}")
        seen_keys.add(key)
        require(item.get("verdict") in ALLOWED_VERDICTS, f"{cid}.acVerdicts[{index}].verdict invalid")
    usage = result.get("usage")
    if usage is not None:
        require(isinstance(usage, dict), f"{cid}.usage must be an object or null")
        reject_extra_keys(usage, {"inputTokens", "outputTokens", "latencyMs"}, f"{cid}.usage")
        for key in ("inputTokens", "outputTokens", "latencyMs"):
            value = usage.get(key)
            require(isinstance(value, int) and not isinstance(value, bool) and value >= 0, f"{cid}.usage.{key} invalid")
    return result


def validate_run_envelope(document: Any, manifest: dict[str, Any]) -> list[dict[str, Any]]:
    require(isinstance(document, dict), "run envelope must be an object")
    reject_extra_keys(
        document,
        {"contractVersion", "corpusVersion", "caseSetVersion", "runKind", "arm", "config", "cases"},
        "run envelope",
    )
    require(document.get("contractVersion") == RUN_VERSION, "run contractVersion invalid")
    require(document.get("corpusVersion") == manifest["corpusVersion"], "run corpusVersion mismatch")
    require(document.get("caseSetVersion") == CASE_SET_VERSION, "run caseSetVersion mismatch")
    require(document.get("runKind") in {"SYNTHETIC_REFERENCE", "MODEL_EVALUATION"}, "runKind invalid")
    require(document.get("arm") in {"DIFF_ONLY", "DIFF_REQUIREMENT_AC", "DIFF_REQUIREMENT_AC_KNOWLEDGE"}, "run arm invalid")
    config = document.get("config")
    require(isinstance(config, dict), "run config must be an object")
    reject_extra_keys(config, {"model", "temperature", "promptVersion"}, "run config")
    require_string(config.get("model"), "run config.model")
    require(isinstance(config.get("temperature"), (int, float)) and not isinstance(config.get("temperature"), bool), "run config.temperature invalid")
    require_string(config.get("promptVersion"), "run config.promptVersion")
    values = document.get("cases")
    require(isinstance(values, list), "run cases must be an array")
    by_id = {case["id"]: case for case in manifest["cases"]}
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for value in values:
        require(isinstance(value, dict), "run case must be an object")
        cid = value.get("caseId")
        require(cid in by_id, f"run contains case outside quick set: {cid}")
        require(cid not in seen, f"run contains duplicate case: {cid}")
        seen.add(cid)
        result.append(validate_run_case(value, by_id[cid]))
    return result


def load_runs(runs_path: Path, manifest: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    require(runs_path.exists(), f"runs path does not exist: {runs_path}")
    files = [runs_path] if runs_path.is_file() else sorted(runs_path.glob("*.json"))
    require(files, f"runs path contains no JSON files: {runs_path}")
    merged: dict[str, Any] = {}
    metadata: dict[str, Any] | None = None
    for file in files:
        document = read_json(file)
        if isinstance(document, dict) and "cases" in document and "contractVersion" in document:
            values = validate_run_envelope(document, manifest)
            if metadata is None:
                metadata = {key: document[key] for key in ("contractVersion", "corpusVersion", "caseSetVersion", "runKind", "arm", "config")}
            else:
                current = {key: document[key] for key in ("contractVersion", "corpusVersion", "caseSetVersion", "runKind", "arm", "config")}
                require(current == metadata, f"run metadata differs in {file}")
            for value in values:
                cid = value["caseId"]
                require(cid not in merged, f"duplicate run output for {cid}")
                merged[cid] = value
        else:
            # A directory may also contain one normalized case per file.  This
            # is useful for later experiments, but old report envelopes are
            # intentionally rejected rather than silently adapted.
            require(isinstance(document, dict) and "caseId" in document, f"unsupported run document: {file}")
            cid = document.get("caseId")
            by_id = {case["id"]: case for case in manifest["cases"]}
            require(cid in by_id, f"run contains case outside quick set: {cid}")
            require(cid not in merged, f"duplicate run output for {cid}")
            merged[cid] = validate_run_case(document, by_id[cid])
    if metadata is None:
        metadata = {
            "contractVersion": RUN_VERSION,
            "corpusVersion": manifest["corpusVersion"],
            "caseSetVersion": CASE_SET_VERSION,
            "runKind": "MODEL_EVALUATION",
            "arm": "DIFF_ONLY",
            "config": {"model": "unknown", "temperature": 0, "promptVersion": "unknown"},
        }
    return merged, metadata


def finding_brief(finding: dict[str, Any]) -> dict[str, Any]:
    return {
        "findingType": finding.get("findingType"),
        "category": finding.get("category"),
        "filePath": finding.get("filePath"),
        "lineStart": finding.get("lineStart"),
        "lineEnd": finding.get("lineEnd"),
    }


def score_ac_verdicts(case: dict[str, Any], result: dict[str, Any]) -> dict[str, Any]:
    expected = {item["acKey"]: item["verdict"] for item in case["expectedAcVerdicts"]}
    predicted = {item["acKey"]: item["verdict"] for item in result.get("acVerdicts", [])}
    exact = sum(1 for key, verdict in expected.items() if predicted.get(key) == verdict)
    by_verdict: dict[str, dict[str, int | float | None]] = {}
    for verdict in sorted(ALLOWED_VERDICTS):
        expected_positive = sum(value == verdict for value in expected.values())
        predicted_positive = sum(
            predicted.get(key) == verdict
            for key in expected
        )
        true_positive = sum(expected.get(key) == verdict and predicted.get(key) == verdict for key in expected)
        false_positive = predicted_positive - true_positive
        false_negative = expected_positive - true_positive
        by_verdict[verdict] = {
            "truePositive": true_positive,
            "falsePositive": false_positive,
            "falseNegative": false_negative,
            "precision": ratio(true_positive, true_positive + false_positive),
            "recall": ratio(true_positive, true_positive + false_negative),
        }
    return {
        "expected": len(expected),
        "predicted": len(predicted),
        "exactHits": exact,
        "accuracy": ratio(exact, len(expected)),
        "byVerdict": by_verdict,
        "missing": sorted(set(expected) - set(predicted)),
        "unexpected": sorted(set(predicted) - set(expected)),
    }


def score_corpus(manifest: dict[str, Any], runs: dict[str, Any], metadata: dict[str, Any], aliases: dict[str, set[str]]) -> dict[str, Any]:
    true_positive = false_positive = false_negative = 0
    requirement_expected = requirement_matched = 0
    category_expected: dict[str, dict[str, int]] = defaultdict(lambda: {"expected": 0, "missed": 0})
    category_predicted: dict[str, dict[str, int]] = defaultdict(lambda: {"predicted": 0, "unmatched": 0})
    cases: list[dict[str, Any]] = []
    not_run: list[dict[str, str]] = []
    completed = failed = structure_failures = 0
    usage_cases = 0
    input_tokens = output_tokens = latency_ms = 0
    ac_expected = ac_predicted = ac_exact = 0
    ac_by_verdict: dict[str, dict[str, int]] = defaultdict(lambda: {"expected": 0, "predicted": 0, "exact": 0})

    for case in manifest["cases"]:
        cid = case["id"]
        result = runs.get(cid)
        if result is None or result.get("status") == "NOT_RUN":
            reason = "no run output" if result is None else (result.get("failureReason") or "explicit NOT_RUN")
            not_run.append({"caseId": cid, "reason": reason})
            cases.append({"caseId": cid, "status": "NOT_RUN", "reason": reason})
            continue
        status = result["status"]
        if status == "FAILED":
            failed += 1
            if result.get("failureKind") == "STRUCTURE":
                structure_failures += 1
            cases.append({
                "caseId": cid,
                "status": "FAILED",
                "failureKind": result.get("failureKind"),
                "failureReason": result.get("failureReason"),
            })
            continue

        completed += 1
        expected = case.get("expectedFindings", [])
        predicted = result.get("findings", [])
        matches, missed, unmatched = match_findings(expected, predicted, aliases)
        true_positive += len(matches)
        false_negative += len(missed)
        false_positive += len(unmatched)
        for index, item in enumerate(expected):
            category = norm_category(item["category"])
            category_expected[category]["expected"] += 1
            if index in missed:
                category_expected[category]["missed"] += 1
            if item["findingType"] == "REQUIREMENT":
                requirement_expected += 1
                if index not in missed:
                    requirement_matched += 1
        for index, item in enumerate(predicted):
            category = norm_category(item.get("category")) or "(EMPTY)"
            category_predicted[category]["predicted"] += 1
            if index in unmatched:
                category_predicted[category]["unmatched"] += 1

        ac_result = score_ac_verdicts(case, result)
        ac_expected += ac_result["expected"]
        ac_predicted += ac_result["predicted"]
        ac_exact += ac_result["exactHits"]
        predicted_ac = {item["acKey"]: item["verdict"] for item in result.get("acVerdicts", [])}
        for key, verdict in {item["acKey"]: item["verdict"] for item in case["expectedAcVerdicts"]}.items():
            ac_by_verdict[verdict]["expected"] += 1
            if key in predicted_ac:
                predicted_verdict = predicted_ac[key]
                ac_by_verdict[predicted_verdict]["predicted"] += 1
            if predicted_ac.get(key) == verdict:
                ac_by_verdict[verdict]["exact"] += 1
        usage = result.get("usage")
        if usage is not None:
            usage_cases += 1
            input_tokens += usage["inputTokens"]
            output_tokens += usage["outputTokens"]
            latency_ms += usage["latencyMs"]
        cases.append({
            "caseId": cid,
            "status": "COMPLETED",
            "expected": len(expected),
            "matched": len(matches),
            "missed": len(missed),
            "predicted": len(predicted),
            "unmatched": len(unmatched),
            "missedExpected": [expected[index] for index in missed],
            "unmatchedFindings": [finding_brief(predicted[index]) for index in unmatched],
            "matchedPairs": [
                {"expected": expected[expected_index], "predicted": finding_brief(predicted[predicted_index])}
                for expected_index, predicted_index in sorted(matches)
            ],
            "acVerdict": ac_result,
            "nonFindings": case.get("nonFindings", []),
            "nonFindingAlerts": [finding_brief(predicted[index]) for index in unmatched] if case.get("nonFindings") else [],
        })

    finding_total_predicted = true_positive + false_positive
    finding_total_expected = true_positive + false_negative
    finding_metrics = {
        "truePositive": true_positive,
        "falsePositive": false_positive,
        "falseNegative": false_negative,
        "precision": ratio(true_positive, finding_total_predicted),
        "recall": ratio(true_positive, finding_total_expected),
        "falseReportRate": ratio(false_positive, finding_total_predicted),
        "missRate": ratio(false_negative, finding_total_expected),
    }

    def category_metrics() -> dict[str, Any]:
        result: dict[str, Any] = {}
        for category in sorted(set(category_expected) | set(category_predicted)):
            expected_count = category_expected[category]["expected"]
            missed_count = category_expected[category]["missed"]
            predicted_count = category_predicted[category]["predicted"]
            unmatched_count = category_predicted[category]["unmatched"]
            result[category] = {
                "expected": expected_count,
                "missed": missed_count,
                "missRate": ratio(missed_count, expected_count),
                "predicted": predicted_count,
                "unmatched": unmatched_count,
                "falseReportRate": ratio(unmatched_count, predicted_count),
            }
        return result

    ac_metrics = {
        "expected": ac_expected,
        "predicted": ac_predicted,
        "exactHits": ac_exact,
        "accuracy": ratio(ac_exact, ac_expected),
        "byVerdict": {},
    }
    for verdict in sorted(ALLOWED_VERDICTS):
        counts = ac_by_verdict[verdict]
        tp = counts["exact"]
        fp = counts["predicted"] - tp
        fn = counts["expected"] - tp
        ac_metrics["byVerdict"][verdict] = {
            "expected": counts["expected"],
            "predicted": counts["predicted"],
            "truePositive": tp,
            "falsePositive": fp,
            "falseNegative": fn,
            "precision": ratio(tp, tp + fp),
            "recall": ratio(tp, tp + fn),
        }

    attempted = completed + failed
    report = {
        "contractVersion": REPORT_VERSION,
        "matchRuleVersion": MATCH_RULE_VERSION,
        "corpusVersion": manifest["corpusVersion"],
        "caseSetVersion": CASE_SET_VERSION,
        "runKind": metadata["runKind"],
        "arm": metadata["arm"],
        "config": metadata["config"],
        "findingMetrics": finding_metrics,
        "requirementViolationRecall": ratio(requirement_matched, requirement_expected),
        "acVerdict": ac_metrics,
        "execution": {
            "attempted": attempted,
            "completed": completed,
            "failed": failed,
            "structureFailures": structure_failures,
            "structureFailureRate": ratio(structure_failures, attempted),
            "notRun": len(not_run),
            "notRunCases": not_run,
        },
        "usage": {
            "casesWithUsage": usage_cases,
            "completedCases": completed,
            "inputTokensTotal": input_tokens if usage_cases == completed else None,
            "outputTokensTotal": output_tokens if usage_cases == completed else None,
            "latencyMsTotal": latency_ms if usage_cases == completed else None,
            "meanInputTokens": ratio(input_tokens, completed) if completed and usage_cases == completed else None,
            "meanOutputTokens": ratio(output_tokens, completed) if completed and usage_cases == completed else None,
            "meanLatencyMs": ratio(latency_ms, completed) if completed and usage_cases == completed else None,
        },
        "byCategory": category_metrics(),
        "cases": cases,
    }
    return report


def validate_score_report(report: Any, label: str = "score report") -> dict[str, Any]:
    require(isinstance(report, dict), f"{label} must be an object")
    reject_extra_keys(
        report,
        {
            "contractVersion", "matchRuleVersion", "corpusVersion", "caseSetVersion", "runKind", "arm", "config",
            "findingMetrics", "requirementViolationRecall", "acVerdict", "execution", "usage", "byCategory", "cases",
        },
        label,
    )
    require(report.get("contractVersion") == REPORT_VERSION, f"{label}.contractVersion invalid")
    require(report.get("matchRuleVersion") == MATCH_RULE_VERSION, f"{label}.matchRuleVersion invalid")
    for key in ("corpusVersion", "caseSetVersion", "runKind", "arm"):
        require_string(report.get(key), f"{label}.{key}")
    config = report.get("config")
    require(isinstance(config, dict), f"{label}.config must be an object")
    reject_extra_keys(config, {"model", "temperature", "promptVersion"}, f"{label}.config")
    require_string(config.get("model"), f"{label}.config.model")
    require(
        isinstance(config.get("temperature"), (int, float)) and not isinstance(config.get("temperature"), bool),
        f"{label}.config.temperature invalid",
    )
    require_string(config.get("promptVersion"), f"{label}.config.promptVersion")
    finding = report.get("findingMetrics")
    require(isinstance(finding, dict), f"{label}.findingMetrics must be an object")
    reject_extra_keys(
        finding,
        {"truePositive", "falsePositive", "falseNegative", "precision", "recall", "falseReportRate", "missRate"},
        f"{label}.findingMetrics",
    )
    for key in ("truePositive", "falsePositive", "falseNegative"):
        require(isinstance(finding.get(key), int) and finding[key] >= 0, f"{label}.findingMetrics.{key} invalid")
    for key in ("precision", "recall", "falseReportRate", "missRate"):
        value = finding.get(key)
        require(value is None or isinstance(value, (int, float)) and 0 <= value <= 1, f"{label}.findingMetrics.{key} invalid")
    ac = report.get("acVerdict")
    require(isinstance(ac, dict), f"{label}.acVerdict must be an object")
    reject_extra_keys(ac, {"expected", "predicted", "exactHits", "accuracy", "byVerdict"}, f"{label}.acVerdict")
    for key in ("expected", "predicted", "exactHits"):
        require(isinstance(ac.get(key), int) and ac[key] >= 0, f"{label}.acVerdict.{key} invalid")
    require(isinstance(ac.get("byVerdict"), dict), f"{label}.acVerdict.byVerdict invalid")
    execution = report.get("execution")
    require(isinstance(execution, dict), f"{label}.execution must be an object")
    for key in ("attempted", "completed", "failed", "structureFailures", "notRun"):
        require(isinstance(execution.get(key), int) and execution[key] >= 0, f"{label}.execution.{key} invalid")
    require(execution["attempted"] == execution["completed"] + execution["failed"], f"{label}.execution attempted count inconsistent")
    require(isinstance(execution.get("notRunCases"), list), f"{label}.execution.notRunCases invalid")
    require(len(execution["notRunCases"]) == execution["notRun"], f"{label}.execution notRun count inconsistent")
    require(isinstance(report.get("usage"), dict), f"{label}.usage must be an object")
    require(isinstance(report.get("byCategory"), dict), f"{label}.byCategory must be an object")
    cases = report.get("cases")
    require(isinstance(cases, list), f"{label}.cases must be an array")
    statuses = Counter(item.get("status") for item in cases if isinstance(item, dict))
    require(statuses["COMPLETED"] == execution["completed"], f"{label}.cases completed count inconsistent")
    require(statuses["FAILED"] == execution["failed"], f"{label}.cases failed count inconsistent")
    require(statuses["NOT_RUN"] == execution["notRun"], f"{label}.cases notRun count inconsistent")
    return report


def render_markdown(report: dict[str, Any], label: str) -> str:
    finding = report["findingMetrics"]
    execution = report["execution"]
    usage = report["usage"]
    lines = [
        f"# ForgePilot evaluation score — {label}",
        "",
        f"- Contract: `{report['contractVersion']}`; matching: `{report['matchRuleVersion']}`",
        f"- Corpus: `{report['corpusVersion']}`; case set: `{report['caseSetVersion']}`",
        f"- Run kind: `{report['runKind']}`; arm: `{report['arm']}`",
        "",
        "## Finding metrics",
        "",
        "| Metric | Value |",
        "| --- | ---: |",
        f"| Precision | {format_ratio(finding['precision'])} |",
        f"| Recall | {format_ratio(finding['recall'])} |",
        f"| False-report rate | {format_ratio(finding['falseReportRate'])} |",
        f"| Miss rate | {format_ratio(finding['missRate'])} |",
        f"| TP / FP / FN | {finding['truePositive']} / {finding['falsePositive']} / {finding['falseNegative']} |",
        "",
        "## Execution and usage",
        "",
        f"- Attempted {execution['attempted']}; completed {execution['completed']}; failed {execution['failed']}; structure failures {execution['structureFailures']}; not run {execution['notRun']}.",
        f"- Requirement-violation recall: {format_ratio(report['requirementViolationRecall'])}.",
        f"- Tokens (input/output): {usage['inputTokensTotal']!r} / {usage['outputTokensTotal']!r}; mean latency: {usage['meanLatencyMs']!r} ms.",
        "",
        "Synthetic/reference outputs validate the scorer contract only; they are not model-quality measurements.",
        "",
    ]
    return "\n".join(lines)


def format_ratio(value: float | None) -> str:
    return "n/a" if value is None else f"{value * 100:.2f}%"


def guard_no_holdout(root: Path, manifest: dict[str, Any]) -> list[str]:
    """Return leakage findings without embedding a list of reserved IDs.

    The guard treats the Phase 1 corpus as a closed set.  It catches explicit
    split markers, reserved-looking paths, extra case directories, and run
    outputs that mention a case outside the selected development set.  It does
    not scan prose, so documenting the policy remains possible.
    """
    errors: list[str] = []
    selected = set(case_ids(manifest))
    cases_dir = root / "cases"
    if cases_dir.is_dir():
        for entry in cases_dir.iterdir():
            if entry.is_dir() and entry.name not in selected:
                errors.append(f"extra case directory outside quick set: {entry.name}")
            if "holdout" in entry.name.lower():
                errors.append(f"reserved split marker in case path: {entry}")
    for path in root.rglob("*"):
        if not path.is_file() or ".git" in path.parts:
            continue
        if "holdout" in str(path.relative_to(root)).lower():
            errors.append(f"reserved split marker in path: {path.relative_to(root)}")
        if path.suffix.lower() not in {".json", ".jsonl"}:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if re.search(r"[\"']split[\"']\s*:\s*[\"']holdout[\"']", text, flags=re.IGNORECASE):
            errors.append(f"explicit non-development split marker in {path.relative_to(root)}")
        try:
            document = json.loads(text) if path.suffix.lower() == ".json" else None
        except json.JSONDecodeError:
            document = None
        if isinstance(document, dict):
            values = document.get("cases") if isinstance(document.get("cases"), list) else [document]
            for value in values:
                if not isinstance(value, dict):
                    continue
                if value.get("caseId") is not None and value.get("caseId") not in selected:
                    errors.append(f"run/corpus output outside quick set in {path.relative_to(root)}: {value.get('caseId')}")
                if value.get("split") == "holdout":
                    errors.append(f"non-development object in {path.relative_to(root)}")
    return sorted(set(errors))


def selftest() -> int:
    aliases = {"RESOURCE_LEAK": {"PERFORMANCE_RISK", "UNKNOWN"}, "NULLABILITY": {"NULL_POINTER"}}
    expected = [
        {"findingType": "CODE_QUALITY", "category": "RESOURCE_LEAK", "filePath": "src/a.java", "lineStart": 10, "lineEnd": 12},
    ]
    predicted = [
        {"findingType": "CODE_QUALITY", "category": "PERFORMANCE_RISK", "filePath": "./src/a.java", "lineStart": 11, "lineEnd": 11},
    ]
    matches, missed, unmatched = match_findings(expected, predicted, aliases)
    checks: list[tuple[str, bool]] = [
        ("path/category/range alias", matches == [(0, 0)] and not missed and not unmatched),
    ]
    null_predicted = [dict(predicted[0], lineStart=None)]
    _, null_missed, null_unmatched = match_findings(expected, null_predicted, aliases)
    checks.append(("null line is not a match", null_missed == [0] and null_unmatched == [0]))
    duplicate_predicted = [dict(predicted[0], lineStart=10), dict(predicted[0], lineStart=12)]
    _, _, duplicate_unmatched = match_findings(expected, duplicate_predicted, aliases)
    checks.append(("greedy one-to-one", len(duplicate_unmatched) == 1))
    wrong_type = [dict(predicted[0], findingType="REQUIREMENT")]
    _, type_missed, type_unmatched = match_findings(expected, wrong_type, aliases)
    checks.append(("finding type remains independent", type_missed == [0] and type_unmatched == [0]))

    ac_case = {
        "acceptanceCriteria": [{"acKey": "AC-0001", "text": "x"}, {"acKey": "AC-0002", "text": "y"}],
        "expectedAcVerdicts": [{"acKey": "AC-0001", "verdict": "AT_RISK"}, {"acKey": "AC-0002", "verdict": "COVERED"}],
    }
    ac_result = score_ac_verdicts(ac_case, {"acVerdicts": [{"acKey": "AC-0001", "verdict": "AT_RISK"}]})
    checks.append(("AC exact/missing", ac_result["exactHits"] == 1 and ac_result["missing"] == ["AC-0002"] and ac_result["accuracy"] == 0.5))

    checks.append(("zero denominator is null", ratio(0, 0) is None))
    checks.append(("manifest rejects reserved split", manifest_validator_rejects_reserved_split()))
    checks.append(("case set rejects duplicate IDs", case_set_validator_rejects_duplicate()))

    execution_manifest = selftest_manifest()
    completed_case = execution_manifest["cases"][0]
    completed = {
        "caseId": completed_case["id"],
        "status": "COMPLETED",
        "failureKind": None,
        "failureReason": None,
        "findings": [],
        "acVerdicts": [],
        "usage": None,
    }
    structure_failed = dict(completed, status="FAILED", failureKind="STRUCTURE", failureReason="bad JSON")
    report = score_corpus(
        execution_manifest,
        {completed_case["id"]: structure_failed},
        {"runKind": "SYNTHETIC_REFERENCE", "arm": "DIFF_ONLY", "config": {"model": "x", "temperature": 0, "promptVersion": "x"}},
        aliases,
    )
    checks.append(("structure failure is attempted, not notRun", report["execution"]["attempted"] == 1 and report["execution"]["structureFailures"] == 1 and report["execution"]["notRun"] == 11))
    checks.extend(extra_field_rejection_checks(execution_manifest, report))
    malformed_report = dict(report)
    malformed_report.pop("config")
    try:
        validate_score_report(malformed_report, "selftest report")
    except ContractError:
        checks.append(("score report requires config", True))
    else:
        checks.append(("score report requires config", False))
    second_case = execution_manifest["cases"][1]
    with_usage = dict(
        completed,
        caseId=second_case["id"],
        usage={"inputTokens": 10, "outputTokens": 4, "latencyMs": 100},
    )
    partial_usage_report = score_corpus(
        execution_manifest,
        {completed_case["id"]: completed, second_case["id"]: with_usage},
        {"runKind": "SYNTHETIC_REFERENCE", "arm": "DIFF_ONLY", "config": {"model": "x", "temperature": 0, "promptVersion": "x"}},
        aliases,
    )
    checks.append((
        "partial usage keeps totals and means null",
        partial_usage_report["usage"]["inputTokensTotal"] is None
        and partial_usage_report["usage"]["meanInputTokens"] is None
        and partial_usage_report["usage"]["meanOutputTokens"] is None
        and partial_usage_report["usage"]["meanLatencyMs"] is None,
    ))
    not_run_report = score_corpus(
        execution_manifest,
        {},
        {"runKind": "SYNTHETIC_REFERENCE", "arm": "DIFF_ONLY", "config": {"model": "x", "temperature": 0, "promptVersion": "x"}},
        aliases,
    )
    checks.append(("missing output is notRun", not_run_report["execution"]["attempted"] == 0 and not_run_report["execution"]["notRun"] == 12))
    for name, ok in checks:
        print(f"  [{'ok' if ok else 'FAIL'}] {name}")
    failed = [name for name, ok in checks if not ok]
    if failed:
        print(f"SELFTEST FAILED ({len(failed)}/{len(checks)})")
        return 1
    print(f"SELFTEST OK ({len(checks)} checks)")
    return 0


def selftest_manifest() -> dict[str, Any]:
    cases = []
    for index in range(12):
        cid = f"selftest-case-{index}"
        cases.append({
            "id": cid,
            "split": "development",
            "language": "JAVA",
            "fixture": f"cases/{cid}",
            "fixtureLayout": "single",
            "selectionReason": "selftest",
            "requirement": {"title": "t", "background": "b", "description": "d"},
            "acceptanceCriteria": [{"acKey": "AC-0001", "text": "x"}],
            "expectedAcVerdicts": [{"acKey": "AC-0001", "verdict": "COVERED"}],
            "expectedFindings": [],
            "nonFindings": [],
        })
    return {
        "schemaVersion": MANIFEST_VERSION,
        "corpusVersion": "selftest",
        "source": {"repository": "selftest", "commit": SOURCE_COMMIT, "migrationPolicy": "REWRITE_KEEP_DATA"},
        "cases": cases,
    }


def rejects_added_extra(document: dict[str, Any], path: list[str | int], validator: Any) -> bool:
    candidate = deepcopy(document)
    target: Any = candidate
    for segment in path:
        target = target[segment]
    target["unexpectedField"] = True
    try:
        validator(candidate)
    except ContractError:
        return True
    return False


def extra_field_rejection_checks(manifest: dict[str, Any], report: dict[str, Any]) -> list[tuple[str, bool]]:
    manifest_with_finding = deepcopy(manifest)
    manifest_with_finding["cases"][0]["expectedFindings"] = [{
        "findingType": "CODE_QUALITY",
        "category": "NULLABILITY",
        "severity": "MEDIUM",
        "filePath": "src/example.java",
        "lineStart": 1,
        "lineEnd": 1,
    }]
    manifest_validator = lambda value: validate_manifest(value)
    checks = [
        ("manifest rejects top-level extra field", rejects_added_extra(manifest_with_finding, [], manifest_validator)),
        ("manifest rejects source extra field", rejects_added_extra(manifest_with_finding, ["source"], manifest_validator)),
        ("manifest rejects case extra field", rejects_added_extra(manifest_with_finding, ["cases", 0], manifest_validator)),
        ("manifest rejects requirement extra field", rejects_added_extra(manifest_with_finding, ["cases", 0, "requirement"], manifest_validator)),
        ("manifest rejects finding extra field", rejects_added_extra(manifest_with_finding, ["cases", 0, "expectedFindings", 0], manifest_validator)),
        ("manifest rejects acceptance-criterion extra field", rejects_added_extra(manifest_with_finding, ["cases", 0, "acceptanceCriteria", 0], manifest_validator)),
        ("manifest rejects acceptance-truth extra field", rejects_added_extra(manifest_with_finding, ["cases", 0, "expectedAcVerdicts", 0], manifest_validator)),
    ]

    first_case = manifest["cases"][0]
    run = {
        "contractVersion": RUN_VERSION,
        "corpusVersion": manifest["corpusVersion"],
        "caseSetVersion": CASE_SET_VERSION,
        "runKind": "SYNTHETIC_REFERENCE",
        "arm": "DIFF_ONLY",
        "config": {"model": "selftest", "temperature": 0, "promptVersion": "selftest"},
        "cases": [{
            "caseId": first_case["id"],
            "status": "COMPLETED",
            "failureKind": None,
            "failureReason": None,
            "findings": [{
                "findingType": "CODE_QUALITY",
                "category": "NULLABILITY",
                "filePath": "src/example.java",
                "lineStart": 1,
                "lineEnd": 1,
            }],
            "acVerdicts": [{"acKey": "AC-0001", "verdict": "COVERED"}],
            "usage": {"inputTokens": 1, "outputTokens": 1, "latencyMs": 1},
        }],
    }
    run_validator = lambda value: validate_run_envelope(value, manifest)
    checks.extend([
        ("run rejects top-level extra field", rejects_added_extra(run, [], run_validator)),
        ("run rejects config extra field", rejects_added_extra(run, ["config"], run_validator)),
        ("run rejects case-result extra field", rejects_added_extra(run, ["cases", 0], run_validator)),
        ("run rejects finding extra field", rejects_added_extra(run, ["cases", 0, "findings", 0], run_validator)),
        ("run rejects AC-verdict extra field", rejects_added_extra(run, ["cases", 0, "acVerdicts", 0], run_validator)),
        ("run rejects usage extra field", rejects_added_extra(run, ["cases", 0, "usage"], run_validator)),
    ])

    report_validator = lambda value: validate_score_report(value, "selftest report")
    checks.extend([
        ("score report rejects top-level extra field", rejects_added_extra(report, [], report_validator)),
        ("score report rejects config extra field", rejects_added_extra(report, ["config"], report_validator)),
        ("score report rejects finding-metrics extra field", rejects_added_extra(report, ["findingMetrics"], report_validator)),
        ("score report rejects AC-metrics extra field", rejects_added_extra(report, ["acVerdict"], report_validator)),
    ])
    open_report = deepcopy(report)
    open_report["execution"]["futureMetric"] = 1
    open_report["usage"]["futureMetric"] = 1
    open_report["byCategory"]["FUTURE_CATEGORY"] = {"futureMetric": 1}
    open_report["cases"][0]["futureDetail"] = True
    try:
        validate_score_report(open_report, "selftest open report")
    except ContractError:
        open_objects_remain_open = False
    else:
        open_objects_remain_open = True
    checks.append(("score report keeps schema-open objects extensible", open_objects_remain_open))
    return checks


def manifest_validator_rejects_reserved_split() -> bool:
    manifest = selftest_manifest()
    manifest["cases"][0]["split"] = "holdout"
    try:
        validate_manifest(manifest)
    except ContractError:
        return True
    return False


def case_set_validator_rejects_duplicate() -> bool:
    manifest = selftest_manifest()
    ids = case_ids(manifest)
    case_set = {
        "schemaVersion": "forgepilot-evaluation-case-set-v1",
        "caseSetVersion": CASE_SET_VERSION,
        "manifestSchemaVersion": MANIFEST_VERSION,
        "sourceCommit": SOURCE_COMMIT,
        "allowedSplit": ALLOWED_SPLIT,
        "caseIds": [ids[0], *ids[1:-1], ids[0]],
    }
    try:
        validate_case_set(case_set, manifest)
    except ContractError:
        return True
    return False


def compare_reports(actual_path: Path, expected_path: Path) -> int:
    actual = validate_score_report(read_json(actual_path), str(actual_path))
    expected = validate_score_report(read_json(expected_path), str(expected_path))
    if actual == expected:
        print(f"REFERENCE MATCH: {actual_path} == {expected_path}")
        return 0
    actual_text = json.dumps(actual, ensure_ascii=False, indent=2, sort_keys=True).splitlines(keepends=True)
    expected_text = json.dumps(expected, ensure_ascii=False, indent=2, sort_keys=True).splitlines(keepends=True)
    print("REFERENCE MISMATCH", file=sys.stderr)
    sys.stderr.writelines(difflib.unified_diff(expected_text, actual_text, fromfile=str(expected_path), tofile=str(actual_path)))
    return 1


def score_command(args: argparse.Namespace) -> int:
    manifest_path = Path(args.manifest).resolve()
    manifest = validate_corpus(manifest_path, Path(args.case_set).resolve())
    if args.guard_no_holdout:
        errors = guard_no_holdout(EVALUATION_DIR if args.root is None else Path(args.root).resolve(), manifest)
        if errors:
            for error in errors:
                print(f"HOLDOUT GUARD: {error}", file=sys.stderr)
            return 1
        print("HOLDOUT GUARD OK: no non-development corpus or run output detected")
        if not args.runs:
            return 0
    aliases = load_aliases(Path(args.aliases).resolve())
    runs, metadata = load_runs(Path(args.runs).resolve(), manifest)
    report = score_corpus(manifest, runs, metadata, aliases)
    out_dir = Path(args.out_dir).resolve() if args.out_dir else Path(args.runs).resolve().parent
    label = args.label or ("reference" if Path(args.runs).name == "reference-runs" else Path(args.runs).stem)
    json_path = out_dir / f"scores-{label}.json"
    md_path = out_dir / f"scores-{label}.md"
    write_json(json_path, report)
    md_path.parent.mkdir(parents=True, exist_ok=True)
    md_path.write_text(render_markdown(report, label), encoding="utf-8")
    finding = report["findingMetrics"]
    print(f"SCORED {report['execution']['completed']} completed, {report['execution']['failed']} failed, {report['execution']['notRun']} notRun")
    print(f"  precision={format_ratio(finding['precision'])} recall={format_ratio(finding['recall'])} falseReportRate={format_ratio(finding['falseReportRate'])} missRate={format_ratio(finding['missRate'])}")
    print(f"  report={json_path}")
    print(f"  markdown={md_path}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="ForgePilot V2 deterministic Phase 1 scorer")
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--case-set", default=str(DEFAULT_CASE_SET))
    parser.add_argument("--aliases", default=str(DEFAULT_ALIASES))
    parser.add_argument("--runs", help="normalized run file or directory")
    parser.add_argument("--out-dir", help="output directory for scores-reference.json")
    parser.add_argument("--label", help="output label; defaults to reference for reference-runs")
    parser.add_argument("--compare-report", nargs=2, metavar=("ACTUAL", "EXPECTED"))
    parser.add_argument("--validate-corpus", action="store_true")
    parser.add_argument("--guard-no-holdout", action="store_true")
    parser.add_argument("--root", help="root to scan for holdout leakage")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    if args.compare_report:
        return compare_reports(Path(args.compare_report[0]), Path(args.compare_report[1]))
    if args.validate_corpus and not args.runs and not args.guard_no_holdout:
        validate_corpus(Path(args.manifest).resolve(), Path(args.case_set).resolve())
        print("CORPUS VALID")
        return 0
    if args.guard_no_holdout and not args.runs:
        manifest = validate_corpus(Path(args.manifest).resolve(), Path(args.case_set).resolve())
        errors = guard_no_holdout(EVALUATION_DIR if args.root is None else Path(args.root).resolve(), manifest)
        if errors:
            for error in errors:
                print(f"HOLDOUT GUARD: {error}", file=sys.stderr)
            return 1
        print("HOLDOUT GUARD OK: no non-development corpus or run output detected")
        return 0
    if not args.runs:
        parser.error("--runs is required unless --selftest, --compare-report, --validate-corpus, or --guard-no-holdout is used")
    try:
        return score_command(args)
    except ContractError as exc:
        print(f"CONTRACT ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
