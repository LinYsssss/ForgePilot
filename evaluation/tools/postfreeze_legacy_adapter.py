#!/usr/bin/env python3
"""One-time, post-freeze adapter for the locked Legacy evaluation corpus.

The Legacy commit was intentionally not inspected until after the Phase 8
configuration freeze.  Its manifest uses the predecessor field names, so the
frozen importer cannot consume it directly.  This adapter performs only the
documented REWRITE_KEEP_DATA field migration, omits Legacy patch-answer files
from model-visible fixtures, and delegates final validation to the frozen
formal-evaluation validator.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path
from typing import Any


TOOL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_DIR))

import formal_evaluation as formal  # noqa: E402
import score  # noqa: E402


ADAPTER_VERSION = "forgepilot-postfreeze-legacy-adapter-v1"
EVIDENCE_PATH = formal.ROOT_DIR / (
    ".trellis/tasks/08-22-phase-8-gitlab-evaluation-defense/"
    "evidence/import-compatibility.json"
)
LEGACY_CASE_FIELDS = {
    "id", "split", "language", "fixture", "fixtureLayout", "requirement",
    "acceptanceCriteria", "consistencyTruth", "expectedFindings",
    "nonFindings", "expectedPatch",
}
LEGACY_FINDING_FIELDS = {
    "category", "severity", "path", "line", "lineEnd", "categoryEquivalents",
}


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise formal.FormalError(f"{label} is not an object")
    return value


def ac_key(value: Any, label: str) -> str:
    raw = score.require_string(value, label)
    match = re.fullmatch(r"AC([0-9]+)", raw)
    if match is None:
        raise formal.FormalError(f"{label} is not a Legacy AC identifier")
    return f"AC-{int(match.group(1)):04d}"


def normalize_finding(value: Any, label: str) -> dict[str, Any]:
    finding = require_object(value, label)
    extra = set(finding) - LEGACY_FINDING_FIELDS
    required = {"category", "severity", "path", "line"}
    missing = required - set(finding)
    if extra or missing:
        raise formal.FormalError(
            f"{label} has incompatible fields; missing={sorted(missing)}, extra={sorted(extra)}"
        )
    category = score.require_string(finding["category"], f"{label}.category")
    start = finding["line"]
    end = finding.get("lineEnd", start)
    normalized = {
        "findingType": "REQUIREMENT" if category == "BUSINESS_RULE_RISK" else "CODE_QUALITY",
        "category": category,
        "severity": finding["severity"],
        "filePath": finding["path"],
        "lineStart": start,
        "lineEnd": end,
    }
    aliases = finding.get("categoryEquivalents", [])
    if aliases:
        normalized["categoryAliases"] = aliases
    score.validate_expected_finding(normalized, label)
    return normalized


def normalize_case(value: Any, index: int) -> dict[str, Any]:
    label = f"Legacy case {index}"
    case = require_object(value, label)
    extra = set(case) - LEGACY_CASE_FIELDS
    required = LEGACY_CASE_FIELDS - {"fixtureLayout"}
    missing = required - set(case)
    if extra or missing:
        raise formal.FormalError(
            f"{label} has incompatible fields; missing={sorted(missing)}, extra={sorted(extra)}"
        )

    criteria = []
    key_map: dict[str, str] = {}
    for ac_index, raw_value in enumerate(case["acceptanceCriteria"]):
        raw = require_object(raw_value, f"{label}.acceptanceCriteria[{ac_index}]")
        if set(raw) != {"id", "text"}:
            raise formal.FormalError(f"{label}.acceptanceCriteria[{ac_index}] has incompatible fields")
        normalized_key = ac_key(raw["id"], f"{label}.acceptanceCriteria[{ac_index}].id")
        key_map[raw["id"]] = normalized_key
        criteria.append({"acKey": normalized_key, "text": raw["text"]})

    truths = []
    for truth_index, raw_value in enumerate(case["consistencyTruth"]):
        raw = require_object(raw_value, f"{label}.consistencyTruth[{truth_index}]")
        if set(raw) != {"acId", "verdict"} or raw["acId"] not in key_map:
            raise formal.FormalError(f"{label}.consistencyTruth[{truth_index}] is incompatible")
        truths.append({"acKey": key_map[raw["acId"]], "verdict": raw["verdict"]})

    split = case["split"]
    return {
        "id": case["id"],
        "split": split,
        "language": case["language"],
        "fixture": case["fixture"],
        "fixtureLayout": case.get("fixtureLayout", "single"),
        "selectionReason": (
            f"Locked Legacy {split} case; original split and truth labels retained "
            "without post-freeze selection or resampling."
        ),
        "requirement": case["requirement"],
        "acceptanceCriteria": criteria,
        "expectedAcVerdicts": truths,
        "expectedFindings": [
            normalize_finding(item, f"{label}.expectedFindings[{finding_index}]")
            for finding_index, item in enumerate(case["expectedFindings"])
        ],
        "nonFindings": case["nonFindings"],
    }


def normalize_manifest(legacy: Any, config: dict[str, Any]) -> dict[str, Any]:
    document = require_object(legacy, "Legacy manifest")
    cases = document.get("cases")
    if not isinstance(cases, list):
        raise formal.FormalError("Legacy evaluation manifest has no cases array")
    return {
        "schemaVersion": formal.FORMAL_MANIFEST_VERSION,
        "corpusVersion": document.get("corpusVersion", "legacy-formal-38-v1"),
        "source": {
            "repository": config["source"]["repository"],
            "commit": config["source"]["commit"],
            "migrationPolicy": "REWRITE_KEEP_DATA",
        },
        "cases": [normalize_case(case, index) for index, case in enumerate(cases)],
    }


def patch_truth_file(case: dict[str, Any]) -> str | None:
    expected_patch = case.get("expectedPatch")
    if not isinstance(expected_patch, dict):
        return None
    value = expected_patch.get("file")
    return value if isinstance(value, str) and value else None


def copy_fixture(source: Path, destination: Path, omitted_name: str | None) -> None:
    def ignore(_directory: str, names: list[str]) -> set[str]:
        return {omitted_name} if omitted_name is not None and omitted_name in names else set()

    shutil.copytree(source, destination, ignore=ignore)


def import_corpus() -> dict[str, Any]:
    config = formal.load_config()
    freeze = formal.verify_freeze(formal.rooted(config["freezeArtifact"]))
    target = formal.rooted(config["corpusWorkspace"])
    ledger = formal.rooted(config["holdoutLedger"])
    if target.exists() or target.with_name(target.name + ".importing").exists():
        raise formal.FormalError(f"refusing to overwrite formal corpus or staging path: {target}")
    if ledger.exists():
        raise formal.FormalError("holdout ledger already exists before corpus import")
    if EVIDENCE_PATH.exists():
        raise formal.FormalError(f"refusing to overwrite compatibility evidence: {EVIDENCE_PATH}")

    with tempfile.TemporaryDirectory(prefix="forgepilot-postfreeze-adapter-") as temporary:
        temporary_root = Path(temporary)
        archive = subprocess.run(
            ["git", "archive", "--format=tar", config["source"]["commit"], "evaluation"],
            cwd=formal.ROOT_DIR, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
        )
        if archive.returncode != 0:
            raise formal.FormalError(archive.stderr.decode("utf-8", errors="replace").strip())
        tar_path = temporary_root / "legacy.tar"
        tar_path.write_bytes(archive.stdout)
        with tarfile.open(tar_path) as bundle:
            bundle.extractall(temporary_root, filter="data")
        legacy_root = temporary_root / "evaluation"
        legacy_manifest_path = legacy_root / "manifest.json"
        source_manifest_hash = formal.sha256_file(legacy_manifest_path)
        legacy = score.read_json(legacy_manifest_path)
        manifest = normalize_manifest(legacy, config)

        source_cases = {case["id"]: case for case in legacy["cases"]}
        staging = target.with_name(target.name + ".importing")
        staging.mkdir(parents=True)
        try:
            formal.atomic_json(staging / "manifest.json", manifest)
            omitted: list[str] = []
            for case in manifest["cases"]:
                source_fixture = legacy_root / case["fixture"]
                if not source_fixture.is_dir():
                    raise formal.FormalError(f"Legacy fixture is missing: {case['fixture']}")
                truth_file = patch_truth_file(source_cases[case["id"]])
                copy_fixture(source_fixture, staging / case["fixture"], truth_file)
                if truth_file is not None:
                    omitted.append(f"{case['fixture']}/{truth_file}")
            formal.validate_formal_corpus(staging)
            file_hashes = {
                path.relative_to(staging).as_posix(): formal.sha256_file(path)
                for path in sorted(item for item in staging.rglob("*") if item.is_file())
            }
            integrity = {
                "integrityVersion": formal.INTEGRITY_VERSION,
                "createdAt": formal.utc_now(),
                "freezeHash": freeze["freezeHash"],
                "sourceCommit": config["source"]["commit"],
                "splitCounts": config["expectedCases"],
                "files": file_hashes,
            }
            formal.atomic_json(staging / "corpus-integrity.json", integrity)
            os.replace(staging, target)
        except BaseException:
            if staging.exists():
                shutil.rmtree(staging)
            raise

    validated = formal.validate_formal_corpus(target)
    evidence = {
        "adapterVersion": ADAPTER_VERSION,
        "createdAt": formal.utc_now(),
        "freezeHash": freeze["freezeHash"],
        "adapterSha256": formal.sha256_file(Path(__file__)),
        "sourceManifestSha256": source_manifest_hash,
        "normalizedManifestSha256": formal.sha256_file(target / "manifest.json"),
        "sourceCommit": config["source"]["commit"],
        "caseCount": len(validated["cases"]),
        "splitCounts": config["expectedCases"],
        "mappingRules": [
            "AC<n> -> AC-<n zero-padded to four digits>",
            "consistencyTruth -> expectedAcVerdicts",
            "path/line/lineEnd -> filePath/lineStart/lineEnd",
            "categoryEquivalents -> categoryAliases",
            "BUSINESS_RULE_RISK -> REQUIREMENT; all other categories -> CODE_QUALITY",
            "missing fixtureLayout -> single",
            "Legacy expectedPatch fields and referenced patch-answer files are omitted",
        ],
        "omittedTruthFiles": omitted,
        "configurationChanged": False,
        "promptOrScorerChanged": False,
        "holdoutSplitChanged": False,
    }
    formal.atomic_json(EVIDENCE_PATH, evidence, exclusive=True)
    return {
        "corpusWorkspace": str(target),
        "freezeHash": freeze["freezeHash"],
        "cases": len(validated["cases"]),
        "compatibilityEvidence": str(EVIDENCE_PATH),
    }


def main() -> int:
    try:
        print(json.dumps(import_corpus(), ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    except (formal.FormalError, score.ContractError, OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
