#!/usr/bin/env python3
"""Run the frozen formal experiment through an audited endpoint correction.

The original freeze accidentally recorded the public OpenAI endpoint although
the supplied credential belongs to an OpenAI-compatible third-party service.
The mistake was discovered before any holdout ledger or holdout provider call.
This wrapper leaves every frozen file untouched, verifies the original freeze,
content-addresses itself and the correction record, changes only the endpoint
identity in memory, and reuses the frozen runner, scorer, corpus validator, and
canonical output layout.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import sys
from pathlib import Path
from typing import Any


TOOL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_DIR))

import formal_evaluation as formal  # noqa: E402
import run_development as runner  # noqa: E402
import score  # noqa: E402


CORRECTION_VERSION = "forgepilot-postfreeze-provider-correction-v1"
CORRECTION_PATH = formal.ROOT_DIR / (
    ".trellis/tasks/08-22-phase-8-gitlab-evaluation-defense/"
    "evidence/provider-endpoint-correction.json"
)
CORRECTION_FIELDS = {
    "correctionVersion", "createdAt", "reason", "originalFreezeHash",
    "originalEndpointIdentity", "correctedEndpointIdentity", "model",
    "modelChanged", "temperatureChanged", "promptOrScorerChanged",
    "corpusOrSplitChanged", "holdoutStateBeforeCorrection", "runnerSha256",
    "correctionHash",
}


def correction_hash(document: dict[str, Any]) -> str:
    unsigned = {key: value for key, value in document.items() if key != "correctionHash"}
    return formal.sha256_bytes(formal.canonical_bytes(unsigned))


def load_correction(config: dict[str, Any], freeze: dict[str, Any]) -> dict[str, Any]:
    document = score.read_json(CORRECTION_PATH)
    if not isinstance(document, dict) or set(document) != CORRECTION_FIELDS:
        raise formal.FormalError("provider correction has the wrong fields")
    if document.get("correctionVersion") != CORRECTION_VERSION:
        raise formal.FormalError("provider correction version is invalid")
    if document.get("correctionHash") != correction_hash(document):
        raise formal.FormalError("provider correction hash is invalid")
    if document.get("runnerSha256") != formal.sha256_file(Path(__file__)):
        raise formal.FormalError("provider correction runner hash is invalid")
    if document.get("originalFreezeHash") != freeze["freezeHash"]:
        raise formal.FormalError("provider correction targets a different freeze")
    provider = config["provider"]
    if document.get("originalEndpointIdentity") != provider["endpointIdentity"]:
        raise formal.FormalError("provider correction has the wrong original endpoint")
    if document.get("model") != provider["model"] or document.get("modelChanged") is not False:
        raise formal.FormalError("provider correction must not change the frozen model")
    if document.get("temperatureChanged") is not False \
            or document.get("promptOrScorerChanged") is not False \
            or document.get("corpusOrSplitChanged") is not False:
        raise formal.FormalError("provider correction changes more than the endpoint")
    endpoint = score.require_string(
        document.get("correctedEndpointIdentity"), "correctedEndpointIdentity",
    )
    if not endpoint.startswith("https://") or runner.endpoint(endpoint) != endpoint:
        raise formal.FormalError("corrected endpoint must be an HTTPS /chat/completions identity")
    state = document.get("holdoutStateBeforeCorrection")
    if state != {"ledgerExists": False, "outputExists": False, "providerCalls": 0}:
        raise formal.FormalError("provider correction does not prove an untouched holdout run")
    return document


def runtime_config(config: dict[str, Any], correction: dict[str, Any]) -> dict[str, Any]:
    corrected = copy.deepcopy(config)
    corrected["provider"]["endpointIdentity"] = correction["correctedEndpointIdentity"]
    return corrected


def probe_development() -> dict[str, Any]:
    config = formal.load_config()
    freeze = formal.verify_freeze(formal.rooted(config["freezeArtifact"]))
    correction = load_correction(config, freeze)
    corrected = runtime_config(config, correction)
    corpus_root = formal.rooted(config["corpusWorkspace"])
    manifest = formal.validate_formal_corpus(corpus_root)
    case = next(case for case in manifest["cases"] if case["split"] == "development")
    api_key = os.environ.get("OPENAI_API_KEY", "")
    if not api_key:
        raise formal.FormalError("OPENAI_API_KEY is not set")
    provider = corrected["provider"]
    base_url = provider["endpointIdentity"][:-len("/chat/completions")]
    result = runner.run_case(
        case, runner.ARMS[0], base_url, api_key, provider["model"],
        provider["temperature"], provider["timeoutSeconds"], corpus_root,
    )
    return {
        "caseId": result["caseId"],
        "arm": runner.ARMS[0],
        "status": result["status"],
        "failureKind": result["failureKind"],
        "failureReason": result["failureReason"],
        "usagePresent": result.get("usage") is not None,
        "correctionHash": correction["correctionHash"],
    }


def execute_split(split: str) -> dict[str, Any]:
    if split not in {"development", "holdout"}:
        raise formal.FormalError("split must be development or holdout")
    config = formal.load_config()
    freeze = formal.verify_freeze(formal.rooted(config["freezeArtifact"]))
    correction = load_correction(config, freeze)
    corrected = runtime_config(config, correction)
    corpus_root = formal.rooted(config["corpusWorkspace"])
    full_manifest = formal.validate_formal_corpus(corpus_root)
    manifest = formal.subset_manifest(full_manifest, split)
    results_root = formal.EVALUATION_DIR / "results" / "formal"
    out_dir = results_root / split
    if out_dir.exists():
        raise formal.FormalError(f"refusing to overwrite formal {split} output: {out_dir}")
    api_key = os.environ.get("OPENAI_API_KEY", "")
    if not api_key:
        raise formal.FormalError("OPENAI_API_KEY is not set")
    provider = corrected["provider"]
    base_url = provider["endpointIdentity"][:-len("/chat/completions")]
    ledger_path = formal.rooted(config["holdoutLedger"])
    ledger: dict[str, Any] | None = None
    if split == "holdout":
        if ledger_path.exists():
            raise formal.FormalError("holdout ledger already exists; a second run is forbidden")
        ledger = {
            "ledgerVersion": "forgepilot-holdout-ledger-v1",
            "freezeHash": freeze["freezeHash"],
            "providerCorrectionHash": correction["correctionHash"],
            "sourceCommit": config["source"]["commit"],
            "startedAt": formal.utc_now(),
            "status": "STARTED",
            "attemptOrdinal": 1,
            "expectedProviderCallsAtMost": len(manifest["cases"]) * len(config["arms"])
                * runner.PROVIDER_ATTEMPTS,
        }
        formal.atomic_json(ledger_path, ledger, exclusive=True)
    out_dir.mkdir(parents=True)
    try:
        for arm in corrected["arms"]:
            values = [
                formal.not_run(case["id"], "formal run has not reached this case")
                for case in manifest["cases"]
            ]
            formal.persist_arm(out_dir, manifest, corrected, split, arm, values)
            for index, case in enumerate(manifest["cases"]):
                print(f"[{split}][{arm}] {case['id']}", file=sys.stderr, flush=True)
                values[index] = runner.run_case(
                    case, arm, base_url, api_key, provider["model"],
                    provider["temperature"], provider["timeoutSeconds"], corpus_root,
                )
                formal.persist_arm(out_dir, manifest, corrected, split, arm, values)
        if ledger is not None:
            ledger["status"] = "COMPLETE"
            ledger["completedAt"] = formal.utc_now()
            formal.atomic_json(ledger_path, ledger)
    except BaseException as exc:
        if ledger is not None:
            ledger["status"] = "INTERRUPTED"
            ledger["interruptedAt"] = formal.utc_now()
            ledger["reason"] = type(exc).__name__
            formal.atomic_json(ledger_path, ledger)
        raise
    return {
        "split": split,
        "cases": len(manifest["cases"]),
        "arms": len(corrected["arms"]),
        "correctionHash": correction["correctionHash"],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("verify", help="verify the original freeze and provider correction")
    sub.add_parser("probe", help="make one non-canonical development provider call")
    run = sub.add_parser("run", help="run one canonical corrected split")
    run.add_argument("--split", required=True, choices=("development", "holdout"))
    arguments = parser.parse_args()
    try:
        if arguments.command == "verify":
            config = formal.load_config()
            freeze = formal.verify_freeze(formal.rooted(config["freezeArtifact"]))
            result = load_correction(config, freeze)
        elif arguments.command == "probe":
            result = probe_development()
        else:
            result = execute_split(arguments.split)
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    except (formal.FormalError, score.ContractError, OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
