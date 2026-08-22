#!/usr/bin/env python3
"""Freeze, import, run, and report ForgePilot's one-time formal evaluation.

The tool uses only the Python standard library and the existing deterministic
scorer.  Import is deliberately impossible until a valid configuration freeze
exists.  Holdout execution has one canonical, create-once ledger and keeps
explicit NOT_RUN rows throughout a partial run.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

import run_development as runner
import score


TOOL_DIR = Path(__file__).resolve().parent
EVALUATION_DIR = TOOL_DIR.parent
ROOT_DIR = EVALUATION_DIR.parent
CONFIG_PATH = EVALUATION_DIR / "formal" / "config.json"
FORMAL_MANIFEST_VERSION = "forgepilot-evaluation-manifest-v1"
FREEZE_VERSION = "forgepilot-configuration-freeze-v1"
INTEGRITY_VERSION = "forgepilot-formal-corpus-integrity-v1"
SUMMARY_VERSION = "forgepilot-formal-summary-v1"
TRUTH_FIELDS = {"selectionReason", "expectedAcVerdicts", "expectedFindings", "nonFindings"}
CASE_FIELDS = {
    "id", "split", "language", "fixture", "fixtureLayout", "selectionReason",
    "requirement", "acceptanceCriteria", "expectedAcVerdicts", "expectedFindings", "nonFindings",
}
INFLUENCING_PATHS = (
    "evaluation/formal/config.json",
    "evaluation/tools/formal_evaluation.py",
    "evaluation/tools/run_development.py",
    "evaluation/tools/score.py",
    "evaluation/tools/category-aliases.json",
    "evaluation/contracts/formal-manifest.schema.json",
    "evaluation/contracts/run.schema.json",
    "evaluation/contracts/score-report.schema.json",
    "evaluation/contracts/metrics.md",
)


class FormalError(RuntimeError):
    """A formal-evaluation invariant was violated."""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def atomic_json(path: Path, value: Any, *, exclusive: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if exclusive:
        with path.open("x", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
        return
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def git(*arguments: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["git", *arguments], cwd=ROOT_DIR, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if check and completed.returncode != 0:
        raise FormalError(completed.stderr.strip() or f"git {' '.join(arguments)} failed")
    return completed.stdout.strip()


def load_config() -> dict[str, Any]:
    config = score.read_json(CONFIG_PATH)
    if not isinstance(config, dict) or config.get("configVersion") != "forgepilot-formal-evaluation-config-v1":
        raise FormalError("formal configuration version is invalid")
    if config.get("arms") != list(runner.ARMS):
        raise FormalError("formal arms differ from the frozen three-arm order")
    expected = config.get("expectedCases")
    if expected != {"development": 26, "holdout": 12}:
        raise FormalError("formal split must remain exactly 26 development and 12 holdout cases")
    provider = config.get("provider")
    if not isinstance(provider, dict) or provider.get("retryCount") != runner.PROVIDER_ATTEMPTS - 1:
        raise FormalError("provider retry policy differs from the runner")
    if config.get("runContractVersion") != score.RUN_VERSION:
        raise FormalError("run contract version differs from the scorer")
    if config.get("matchRuleVersion") != score.MATCH_RULE_VERSION:
        raise FormalError("match-rule version differs from the scorer")
    return config


def rooted(relative: str) -> Path:
    path = PurePosixPath(relative)
    if path.is_absolute() or ".." in path.parts:
        raise FormalError(f"unsafe configured path: {relative}")
    return ROOT_DIR.joinpath(*path.parts)


def prompt_schema_hash() -> str:
    return sha256_bytes(canonical_bytes({
        "systemPrompt": runner.SYSTEM_PROMPT,
        "outputSchema": runner.OUTPUT_SCHEMA,
        "promptCharacterLimit": runner.PROMPT_CHAR_LIMIT,
    }))


def freeze_hash(document: dict[str, Any]) -> str:
    unsigned = {key: value for key, value in document.items() if key != "freezeHash"}
    return sha256_bytes(canonical_bytes(unsigned))


def verify_freeze(path: Path, *, verify_current_files: bool = True) -> dict[str, Any]:
    document = score.read_json(path)
    if not isinstance(document, dict) or document.get("freezeVersion") != FREEZE_VERSION:
        raise FormalError("configuration freeze version is invalid")
    if document.get("freezeHash") != freeze_hash(document):
        raise FormalError("configuration freeze hash is invalid")
    config = load_config()
    if document.get("config") != config:
        raise FormalError("configuration freeze does not contain the current formal config")
    if document.get("promptSchemaSha256") != prompt_schema_hash():
        raise FormalError("prompt/schema changed after configuration freeze")
    if verify_current_files:
        recorded = document.get("files")
        if not isinstance(recorded, dict):
            raise FormalError("configuration freeze has no file hashes")
        for relative in INFLUENCING_PATHS:
            path_value = ROOT_DIR / relative
            if recorded.get(relative) != sha256_file(path_value):
                raise FormalError(f"frozen evaluation file changed: {relative}")
    return document


def create_freeze() -> dict[str, Any]:
    config = load_config()
    output = rooted(config["freezeArtifact"])
    corpus = rooted(config["corpusWorkspace"])
    ledger = rooted(config["holdoutLedger"])
    if output.exists():
        raise FormalError(f"refusing to overwrite configuration freeze: {output}")
    if corpus.exists() or ledger.exists():
        raise FormalError("holdout corpus or ledger already exists; configuration cannot be frozen now")
    tracked = git("status", "--porcelain", "--untracked-files=no")
    if tracked:
        raise FormalError("tracked worktree is dirty; commit the completed implementation before freezing")
    relevant = git("status", "--porcelain", "--", *INFLUENCING_PATHS)
    if relevant:
        raise FormalError("output-affecting evaluation files are not committed")
    source_commit = config["source"]["commit"]
    git("cat-file", "-e", source_commit + "^{commit}")
    document = {
        "freezeVersion": FREEZE_VERSION,
        "frozenAt": utc_now(),
        "forgePilotCommit": git("rev-parse", "HEAD"),
        "trackedWorktreeClean": True,
        "holdoutStateBeforeFreeze": {
            "corpusWorkspaceExists": False,
            "ledgerExists": False,
        },
        "apiKeyPresent": bool(os.environ.get("OPENAI_API_KEY")),
        "config": config,
        "promptSchemaSha256": prompt_schema_hash(),
        "files": {relative: sha256_file(ROOT_DIR / relative) for relative in INFLUENCING_PATHS},
    }
    document["freezeHash"] = freeze_hash(document)
    atomic_json(output, document, exclusive=True)
    return document


def validate_case(case: Any, index: int) -> dict[str, Any]:
    label = f"formal manifest case {index}"
    if not isinstance(case, dict) or set(case) != CASE_FIELDS:
        raise FormalError(f"{label} has the wrong fields")
    cid = score.require_string(case.get("id"), f"{label}.id")
    if re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", cid) is None:
        raise FormalError(f"{cid} has an invalid case ID")
    if case.get("split") not in {"development", "holdout"}:
        raise FormalError(f"{cid} has an invalid split")
    if case.get("language") not in score.ALLOWED_LANGUAGES:
        raise FormalError(f"{cid} has an invalid language")
    if case.get("fixture") != f"cases/{cid}":
        raise FormalError(f"{cid} fixture must be cases/{cid}")
    if case.get("fixtureLayout") not in {"single", "base-head"}:
        raise FormalError(f"{cid} has an invalid fixture layout")
    score.require_string(case.get("selectionReason"), f"{cid}.selectionReason")
    requirement = case.get("requirement")
    if not isinstance(requirement, dict) or set(requirement) != {"title", "background", "description"}:
        raise FormalError(f"{cid} requirement has the wrong fields")
    for field in ("title", "background", "description"):
        score.require_string(requirement.get(field), f"{cid}.requirement.{field}")
    criteria = score.validate_ac_list(case.get("acceptanceCriteria"), f"{cid}.acceptanceCriteria", "text")
    truths = score.validate_ac_list(case.get("expectedAcVerdicts"), f"{cid}.expectedAcVerdicts", "verdict")
    if {item["acKey"] for item in criteria} != {item["acKey"] for item in truths}:
        raise FormalError(f"{cid} acceptance-criterion truth keys differ")
    findings = case.get("expectedFindings")
    if not isinstance(findings, list):
        raise FormalError(f"{cid}.expectedFindings must be an array")
    for finding_index, finding in enumerate(findings):
        score.validate_expected_finding(finding, f"{cid}.expectedFindings[{finding_index}]")
    non_findings = case.get("nonFindings")
    if not isinstance(non_findings, list):
        raise FormalError(f"{cid}.nonFindings must be an array")
    for item in non_findings:
        score.require_string(item, f"{cid}.nonFindings item")
    return case


def validate_formal_corpus(root: Path) -> dict[str, Any]:
    config = load_config()
    manifest_path = root / "manifest.json"
    manifest = score.read_json(manifest_path)
    if not isinstance(manifest, dict) or set(manifest) != {"schemaVersion", "corpusVersion", "source", "cases"}:
        raise FormalError("formal manifest has the wrong fields")
    if manifest.get("schemaVersion") != FORMAL_MANIFEST_VERSION:
        raise FormalError("formal manifest schema version is invalid")
    source = manifest.get("source")
    if not isinstance(source, dict) or set(source) != {"repository", "commit", "migrationPolicy"}:
        raise FormalError("formal corpus source has the wrong fields")
    if source.get("repository") != config["source"]["repository"] \
            or source.get("commit") != config["source"]["commit"]:
        raise FormalError("formal corpus source commit is not the locked Legacy commit")
    if source.get("migrationPolicy") != "REWRITE_KEEP_DATA":
        raise FormalError("formal corpus migration policy is invalid")
    cases = manifest.get("cases")
    if not isinstance(cases, list) or len(cases) != 38:
        raise FormalError("formal corpus must contain exactly 38 cases")
    seen: set[str] = set()
    counts = {"development": 0, "holdout": 0}
    for index, case in enumerate(cases):
        validated = validate_case(case, index)
        cid = validated["id"]
        if cid in seen:
            raise FormalError(f"duplicate formal case ID: {cid}")
        seen.add(cid)
        counts[validated["split"]] += 1
        fixture = root / validated["fixture"]
        if not fixture.is_dir():
            raise FormalError(f"missing formal fixture: {fixture}")
        if validated["fixtureLayout"] == "base-head":
            if not (fixture / "base").is_dir() or not (fixture / "head").is_dir():
                raise FormalError(f"{cid} is missing base/head fixture directories")
        suspicious = [
            path for path in fixture.rglob("*") if path.is_file()
            and any(token in path.name.lower() for token in ("truth", "expected", "nonfinding"))
        ]
        if suspicious:
            raise FormalError(f"{cid} fixture contains prompt-leakage-prone files")
    if counts != config["expectedCases"]:
        raise FormalError(f"formal split differs from 26/12: {counts}")
    cases_dir = root / "cases"
    actual = {item.name for item in cases_dir.iterdir() if item.is_dir()}
    if actual != seen:
        raise FormalError("formal fixture directory set differs from the manifest")
    integrity_path = root / "corpus-integrity.json"
    if integrity_path.exists():
        integrity = score.read_json(integrity_path)
        if integrity.get("integrityVersion") != INTEGRITY_VERSION:
            raise FormalError("formal corpus integrity version is invalid")
        hashes = integrity.get("files", {})
        current_files = {
            path.relative_to(root).as_posix()
            for path in root.rglob("*") if path.is_file() and path != integrity_path
        }
        if not isinstance(hashes, dict) or set(hashes) != current_files:
            raise FormalError("formal corpus file set differs from its integrity record")
        for relative, expected_hash in hashes.items():
            if sha256_file(root / relative) != expected_hash:
                raise FormalError(f"formal corpus file hash changed: {relative}")
    return manifest


def normalized_manifest(legacy: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    cases = legacy.get("cases") if isinstance(legacy, dict) else None
    if not isinstance(cases, list):
        raise FormalError("Legacy evaluation manifest has no cases array")
    normalized_cases = []
    for index, case in enumerate(cases):
        if not isinstance(case, dict):
            raise FormalError(f"Legacy case {index} is not an object")
        missing = CASE_FIELDS - set(case)
        if missing:
            raise FormalError(f"Legacy case {index} is missing fields: {sorted(missing)}")
        normalized_cases.append({field: case[field] for field in CASE_FIELDS})
    return {
        "schemaVersion": FORMAL_MANIFEST_VERSION,
        "corpusVersion": legacy.get("corpusVersion", "legacy-formal-38-v1"),
        "source": {
            "repository": config["source"]["repository"],
            "commit": config["source"]["commit"],
            "migrationPolicy": "REWRITE_KEEP_DATA",
        },
        "cases": normalized_cases,
    }


def import_corpus() -> dict[str, Any]:
    config = load_config()
    freeze = verify_freeze(rooted(config["freezeArtifact"]))
    target = rooted(config["corpusWorkspace"])
    if target.exists():
        raise FormalError(f"refusing to overwrite formal corpus: {target}")
    ledger = rooted(config["holdoutLedger"])
    if ledger.exists():
        raise FormalError("holdout ledger already exists before corpus import")
    with tempfile.TemporaryDirectory(prefix="forgepilot-formal-import-") as temporary:
        temporary_root = Path(temporary)
        archive = subprocess.run(
            ["git", "archive", "--format=tar", config["source"]["commit"], "evaluation"],
            cwd=ROOT_DIR, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
        )
        if archive.returncode != 0:
            raise FormalError(archive.stderr.decode("utf-8", errors="replace").strip())
        tar_path = temporary_root / "legacy.tar"
        tar_path.write_bytes(archive.stdout)
        with tarfile.open(tar_path) as bundle:
            bundle.extractall(temporary_root, filter="data")
        legacy_root = temporary_root / "evaluation"
        legacy_manifest_path = legacy_root / "manifest.json"
        if not legacy_manifest_path.is_file():
            raise FormalError("locked Legacy commit has no evaluation/manifest.json")
        manifest = normalized_manifest(score.read_json(legacy_manifest_path), config)
        staging = target.with_name(target.name + ".importing")
        if staging.exists():
            raise FormalError(f"stale corpus import staging directory exists: {staging}")
        staging.mkdir(parents=True)
        try:
            atomic_json(staging / "manifest.json", manifest)
            for case in manifest["cases"]:
                source_fixture = legacy_root / case["fixture"]
                if not source_fixture.is_dir():
                    raise FormalError(f"Legacy fixture is missing: {case['fixture']}")
                shutil.copytree(source_fixture, staging / case["fixture"])
            validate_formal_corpus(staging)
            file_hashes = {
                path.relative_to(staging).as_posix(): sha256_file(path)
                for path in sorted(item for item in staging.rglob("*") if item.is_file())
            }
            integrity = {
                "integrityVersion": INTEGRITY_VERSION,
                "createdAt": utc_now(),
                "freezeHash": freeze["freezeHash"],
                "sourceCommit": config["source"]["commit"],
                "splitCounts": config["expectedCases"],
                "files": file_hashes,
            }
            atomic_json(staging / "corpus-integrity.json", integrity)
            os.replace(staging, target)
        except BaseException:
            if staging.exists():
                shutil.rmtree(staging)
            raise
    validate_formal_corpus(target)
    return {"corpusWorkspace": str(target), "freezeHash": freeze["freezeHash"], "cases": 38}


def subset_manifest(manifest: dict[str, Any], split: str) -> dict[str, Any]:
    return {**manifest, "cases": [case for case in manifest["cases"] if case["split"] == split]}


def not_run(case_id: str, reason: str) -> dict[str, Any]:
    return {
        "caseId": case_id, "status": "NOT_RUN", "failureKind": None,
        "failureReason": reason, "findings": [], "acVerdicts": [], "usage": None,
    }


def run_document(manifest: dict[str, Any], config: dict[str, Any], split: str,
                 arm: str, cases: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "contractVersion": score.RUN_VERSION,
        "corpusVersion": manifest["corpusVersion"],
        "caseSetVersion": config["formalCaseSetVersion"] + "-" + split,
        "runKind": "MODEL_EVALUATION",
        "arm": arm,
        "config": {
            "model": config["provider"]["model"],
            "temperature": config["provider"]["temperature"],
            "promptVersion": config["promptVersion"],
        },
        "cases": cases,
    }


def persist_arm(out_dir: Path, manifest: dict[str, Any], config: dict[str, Any],
                split: str, arm: str, cases: list[dict[str, Any]]) -> None:
    arm_dir = out_dir / arm.lower()
    document = run_document(manifest, config, split, arm, cases)
    atomic_json(arm_dir / "runs" / "run.json", document)
    metadata = {key: document[key] for key in (
        "contractVersion", "corpusVersion", "caseSetVersion", "runKind", "arm", "config"
    )}
    report = score.score_corpus(
        manifest, {case["caseId"]: case for case in cases}, metadata,
        score.load_aliases(runner.ALIASES_PATH),
    )
    score.validate_score_report(report)
    atomic_json(arm_dir / "score.json", report)
    markdown = score.render_markdown(report, split + " / " + arm)
    markdown_path = arm_dir / "score.md"
    markdown_path.parent.mkdir(parents=True, exist_ok=True)
    markdown_path.write_text(markdown, encoding="utf-8")


def execute_split(split: str) -> dict[str, Any]:
    if split not in {"development", "holdout"}:
        raise FormalError("split must be development or holdout")
    config = load_config()
    freeze = verify_freeze(rooted(config["freezeArtifact"]))
    corpus_root = rooted(config["corpusWorkspace"])
    full_manifest = validate_formal_corpus(corpus_root)
    manifest = subset_manifest(full_manifest, split)
    results_root = ROOT_DIR / "evaluation" / "results" / "formal"
    out_dir = results_root / split
    if out_dir.exists():
        raise FormalError(f"refusing to overwrite formal {split} output: {out_dir}")
    api_key = os.environ.get("OPENAI_API_KEY", "")
    if not api_key:
        raise FormalError("OPENAI_API_KEY is not set")
    provider = config["provider"]
    endpoint_identity = runner.endpoint(provider["endpointIdentity"])
    if endpoint_identity != provider["endpointIdentity"]:
        raise FormalError("configured endpoint identity must end in /chat/completions")
    base_url = provider["endpointIdentity"][:-len("/chat/completions")]
    ledger_path = rooted(config["holdoutLedger"])
    ledger: dict[str, Any] | None = None
    if split == "holdout":
        if ledger_path.exists():
            raise FormalError("holdout ledger already exists; a second run is forbidden")
        ledger = {
            "ledgerVersion": "forgepilot-holdout-ledger-v1",
            "freezeHash": freeze["freezeHash"],
            "sourceCommit": config["source"]["commit"],
            "startedAt": utc_now(),
            "status": "STARTED",
            "attemptOrdinal": 1,
            "expectedProviderCallsAtMost": len(manifest["cases"]) * len(config["arms"])
                * runner.PROVIDER_ATTEMPTS,
        }
        atomic_json(ledger_path, ledger, exclusive=True)
    out_dir.mkdir(parents=True)
    try:
        for arm in config["arms"]:
            values = [not_run(case["id"], "formal run has not reached this case") for case in manifest["cases"]]
            persist_arm(out_dir, manifest, config, split, arm, values)
            for index, case in enumerate(manifest["cases"]):
                print(f"[{split}][{arm}] {case['id']}", file=sys.stderr, flush=True)
                values[index] = runner.run_case(
                    case, arm, base_url, api_key, provider["model"],
                    provider["temperature"], provider["timeoutSeconds"], corpus_root,
                )
                persist_arm(out_dir, manifest, config, split, arm, values)
        if ledger is not None:
            ledger["status"] = "COMPLETE"
            ledger["completedAt"] = utc_now()
            atomic_json(ledger_path, ledger)
    except BaseException as exc:
        if ledger is not None:
            ledger["status"] = "INTERRUPTED"
            ledger["interruptedAt"] = utc_now()
            ledger["reason"] = type(exc).__name__
            atomic_json(ledger_path, ledger)
        raise
    return {"split": split, "cases": len(manifest["cases"]), "arms": len(config["arms"])}


def wilson(successes: int, total: int) -> dict[str, float] | None:
    if total == 0:
        return None
    z = 1.959963984540054
    proportion = successes / total
    denominator = 1 + z * z / total
    center = (proportion + z * z / (2 * total)) / denominator
    margin = z * math.sqrt(proportion * (1 - proportion) / total + z * z / (4 * total * total)) / denominator
    return {"low": round(max(0.0, center - margin), 6), "high": round(min(1.0, center + margin), 6)}


def interval_summary(report: dict[str, Any]) -> dict[str, Any]:
    finding = report["findingMetrics"]
    tp, fp, fn = finding["truePositive"], finding["falsePositive"], finding["falseNegative"]
    requirement_expected = requirement_matched = 0
    for case in report["cases"]:
        if case.get("status") != "COMPLETED":
            continue
        for expected in case.get("missedExpected", []):
            if expected.get("findingType") == "REQUIREMENT":
                requirement_expected += 1
        for pair in case.get("matchedPairs", []):
            if pair["expected"].get("findingType") == "REQUIREMENT":
                requirement_expected += 1
                requirement_matched += 1
    execution = report["execution"]
    ac = report["acVerdict"]
    return {
        "precision95": wilson(tp, tp + fp),
        "recall95": wilson(tp, tp + fn),
        "falseReportRate95": wilson(fp, tp + fp),
        "missRate95": wilson(fn, tp + fn),
        "requirementViolationRecall95": wilson(requirement_matched, requirement_expected),
        "acAccuracy95": wilson(ac["exactHits"], ac["expected"]),
        "structureFailureRate95": wilson(execution["structureFailures"], execution["attempted"]),
    }


def load_split_run(results_root: Path, split: str, arm: str,
                   manifest: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    document = score.read_json(results_root / split / arm.lower() / "runs" / "run.json")
    score.validate_run_envelope(
        document, manifest, config["formalCaseSetVersion"] + "-" + split,
    )
    return document


def render_summary(summary: dict[str, Any]) -> str:
    lines = [
        "# ForgePilot formal three-arm evaluation",
        "",
        f"- Freeze: `{summary['freezeHash']}`",
        f"- Model: `{summary['config']['provider']['model']}`; temperature: `{summary['config']['provider']['temperature']}`",
        "- Corpus: 26 development + 12 holdout cases; holdout was executed under one create-once ledger.",
        "- The 12-case holdout is small. Arm differences are descriptive, not strong population claims; 95% Wilson intervals are reported for binomial rates.",
        "",
        "| Split | Arm | Completed | Failed | Not run | Precision | Recall | Requirement recall | AC accuracy | Structure failures | Input tokens | Mean latency ms |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for split in ("development", "holdout", "full"):
        for arm in runner.ARMS:
            item = summary["results"][split][arm]
            report = item["metrics"]
            finding, execution, usage = report["findingMetrics"], report["execution"], report["usage"]
            def pct(value: Any) -> str:
                return "—" if value is None else f"{100 * value:.2f}%"
            lines.append(
                f"| {split} | `{arm}` | {execution['completed']} | {execution['failed']} | {execution['notRun']} | "
                f"{pct(finding['precision'])} | {pct(finding['recall'])} | "
                f"{pct(report['requirementViolationRecall'])} | {pct(report['acVerdict']['accuracy'])} | "
                f"{execution['structureFailures']} | {usage['inputTokensTotal'] if usage['inputTokensTotal'] is not None else '—'} | "
                f"{usage['meanLatencyMs'] if usage['meanLatencyMs'] is not None else '—'} |"
            )
    lines.extend([
        "",
        "The JSON companion contains every Wilson interval, per-verdict metric, category breakdown, failure, and not-run reason. No aggregate score is computed.",
        "",
    ])
    return "\n".join(lines)


def build_report(out_dir: Path | None = None) -> dict[str, Any]:
    config = load_config()
    freeze = verify_freeze(rooted(config["freezeArtifact"]))
    ledger = score.read_json(rooted(config["holdoutLedger"]))
    if ledger.get("attemptOrdinal") != 1 or ledger.get("freezeHash") != freeze["freezeHash"]:
        raise FormalError("holdout ledger does not prove the frozen first attempt")
    corpus = validate_formal_corpus(rooted(config["corpusWorkspace"]))
    results_root = EVALUATION_DIR / "results" / "formal"
    report_root = out_dir if out_dir is not None else results_root / "reports"
    if report_root.exists():
        raise FormalError(f"refusing to overwrite formal reports: {report_root}")
    aliases = score.load_aliases(runner.ALIASES_PATH)
    summary: dict[str, Any] = {
        "summaryVersion": SUMMARY_VERSION,
        "generatedAt": utc_now(),
        "freezeHash": freeze["freezeHash"],
        "config": config,
        "limitations": [
            "The corpus contains hand-constructed demonstration defects, not sampled enterprise incidents.",
            "The holdout has only 12 cases; arm differences are descriptive and have substantial sampling uncertainty.",
        ],
        "results": {"development": {}, "holdout": {}, "full": {}},
    }
    full_dir = report_root / "full"
    for arm in config["arms"]:
        combined_cases: list[dict[str, Any]] = []
        metadata: dict[str, Any] | None = None
        for split in ("development", "holdout"):
            split_manifest = subset_manifest(corpus, split)
            document = load_split_run(results_root, split, arm, split_manifest, config)
            split_metadata = {key: document[key] for key in (
                "contractVersion", "corpusVersion", "caseSetVersion", "runKind", "arm", "config"
            )}
            report = score.score_corpus(
                split_manifest, {case["caseId"]: case for case in document["cases"]},
                split_metadata, aliases,
            )
            score.validate_score_report(report)
            split_dir = report_root / split / arm.lower()
            atomic_json(split_dir / "score.json", report)
            (split_dir / "score.md").write_text(
                score.render_markdown(report, split + " / " + arm), encoding="utf-8",
            )
            summary["results"][split][arm] = {
                "metrics": report,
                "wilson95": interval_summary(report),
            }
            combined_cases.extend(document["cases"])
            current = document["config"]
            if metadata is not None and current != metadata:
                raise FormalError(f"{arm} development and holdout configs differ")
            metadata = current
        full_document = {
            "contractVersion": score.RUN_VERSION,
            "corpusVersion": corpus["corpusVersion"],
            "caseSetVersion": config["formalCaseSetVersion"] + "-full",
            "runKind": "MODEL_EVALUATION", "arm": arm, "config": metadata,
            "cases": combined_cases,
        }
        full_report = score.score_corpus(
            corpus, {case["caseId"]: case for case in combined_cases},
            {key: full_document[key] for key in (
                "contractVersion", "corpusVersion", "caseSetVersion", "runKind", "arm", "config"
            )}, aliases,
        )
        score.validate_score_report(full_report)
        arm_dir = full_dir / arm.lower()
        atomic_json(arm_dir / "runs" / "run.json", full_document)
        atomic_json(arm_dir / "score.json", full_report)
        (arm_dir / "score.md").write_text(score.render_markdown(full_report, "full / " + arm), encoding="utf-8")
        summary["results"]["full"][arm] = {
            "metrics": full_report,
            "wilson95": interval_summary(full_report),
        }
    atomic_json(report_root / "formal-summary.json", summary)
    (report_root / "formal-summary.md").write_text(render_summary(summary), encoding="utf-8")
    return summary


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("freeze", help="write the one-time configuration freeze")
    sub.add_parser("verify-freeze", help="verify the freeze and current tool hashes")
    sub.add_parser("import", help="import the locked Legacy 26+12 corpus after freeze")
    run = sub.add_parser("run", help="run one formal split")
    run.add_argument("--split", choices=("development", "holdout"), required=True)
    report = sub.add_parser("report", help="build development/holdout/full reports and intervals")
    report.add_argument("--out-dir", type=Path,
                        help="new derived-report directory; defaults to evaluation/results/formal/reports")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    config = load_config()
    if args.command == "freeze":
        result = create_freeze()
    elif args.command == "verify-freeze":
        result = verify_freeze(rooted(config["freezeArtifact"]))
    elif args.command == "import":
        result = import_corpus()
    elif args.command == "run":
        result = execute_split(args.split)
    else:
        result = build_report(args.out_dir)
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FormalError, score.ContractError, runner.RunnerError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(2)
