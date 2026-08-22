#!/usr/bin/env python3
"""Synthetic-only tests for the formal evaluation lock and report tooling."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


TOOL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_DIR))

import formal_evaluation as formal  # noqa: E402
import run_development as runner  # noqa: E402
import score  # noqa: E402


def synthetic_case(index: int, split: str) -> dict:
    case_id = f"synthetic-{index:02d}"
    return {
        "id": case_id,
        "split": split,
        "language": "JAVA",
        "fixture": f"cases/{case_id}",
        "fixtureLayout": "single",
        "selectionReason": f"TRUTH_SELECTION_{index}",
        "requirement": {
            "title": f"Requirement {index}",
            "background": "Synthetic background",
            "description": "Synthetic description",
        },
        "acceptanceCriteria": [{"acKey": "AC-0001", "text": "The change is safe."}],
        "expectedAcVerdicts": [{"acKey": "AC-0001", "verdict": "AT_RISK"}],
        "expectedFindings": [{
            "findingType": "REQUIREMENT",
            "category": "TRUTH_ONLY_CATEGORY",
            "severity": "HIGH",
            "filePath": "truth-only/path.java",
            "lineStart": 1,
            "lineEnd": 1,
        }],
        "nonFindings": [f"TRUTH_NON_FINDING_{index}"],
    }


def make_corpus(root: Path) -> dict:
    cases = [synthetic_case(index, "development" if index < 26 else "holdout") for index in range(38)]
    manifest = {
        "schemaVersion": formal.FORMAL_MANIFEST_VERSION,
        "corpusVersion": "synthetic-formal-v1",
        "source": {
            "repository": formal.load_config()["source"]["repository"],
            "commit": formal.load_config()["source"]["commit"],
            "migrationPolicy": "REWRITE_KEEP_DATA",
        },
        "cases": cases,
    }
    formal.atomic_json(root / "manifest.json", manifest)
    for case in cases:
        source = root / case["fixture"] / "src" / "App.java"
        source.parent.mkdir(parents=True)
        source.write_text("class App {}\n", encoding="utf-8")
    return manifest


class FormalEvaluationTest(unittest.TestCase):

    def test_strict_formal_contract_accepts_only_the_original_26_12_shape(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = make_corpus(root)
            self.assertEqual(len(formal.validate_formal_corpus(root)["cases"]), 38)

            manifest["cases"][25]["split"] = "holdout"
            formal.atomic_json(root / "manifest.json", manifest)
            with self.assertRaisesRegex(formal.FormalError, "26/12"):
                formal.validate_formal_corpus(root)

    def test_truth_fields_and_markers_never_enter_a_model_request(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case = synthetic_case(0, "development")
            source = root / case["fixture"] / "src" / "App.java"
            source.parent.mkdir(parents=True)
            source.write_text("class App {}\n", encoding="utf-8")

            prompt = runner.prompt_for(case, "DIFF_REQUIREMENT_AC_KNOWLEDGE", root)
            body = runner.request_body("synthetic-model", 0, prompt).decode("utf-8")
            for marker in (
                case["selectionReason"], case["nonFindings"][0],
                case["expectedFindings"][0]["category"],
                case["expectedFindings"][0]["filePath"],
            ):
                self.assertNotIn(marker, body)
            self.assertNotIn(case["expectedAcVerdicts"][0]["verdict"], prompt)
            self.assertNotIn("expectedFindings", body)
            self.assertNotIn("expectedAcVerdicts", body)
            self.assertIn(case["requirement"]["title"], body)

    def test_legacy_normalization_drops_every_non_contract_field(self) -> None:
        case = synthetic_case(0, "development")
        case["expectedPatch"] = "must disappear"
        case["runtimeMetadata"] = {"secret": "must disappear"}
        normalized = formal.normalized_manifest(
            {"corpusVersion": "legacy", "cases": [case]}, formal.load_config(),
        )
        self.assertEqual(set(normalized["cases"][0]), formal.CASE_FIELDS)
        self.assertNotIn("expectedPatch", json.dumps(normalized))
        self.assertNotIn("runtimeMetadata", json.dumps(normalized))

    def test_wilson_intervals_are_bounded_and_zero_denominators_stay_null(self) -> None:
        self.assertIsNone(formal.wilson(0, 0))
        zero = formal.wilson(0, 12)
        perfect = formal.wilson(12, 12)
        assert zero is not None and perfect is not None
        self.assertEqual(zero["low"], 0.0)
        self.assertEqual(perfect["high"], 1.0)
        self.assertGreater(zero["high"], 0.2)
        self.assertLess(perfect["low"], 0.8)

    def test_freeze_hash_and_create_once_files_detect_tampering_or_replay(self) -> None:
        document = {"freezeVersion": formal.FREEZE_VERSION, "config": {"model": "x"}}
        document["freezeHash"] = formal.freeze_hash(document)
        self.assertEqual(document["freezeHash"], formal.freeze_hash(document))
        document["config"]["model"] = "changed"
        self.assertNotEqual(document["freezeHash"], formal.freeze_hash(document))

        with tempfile.TemporaryDirectory() as temporary:
            ledger = Path(temporary) / "holdout-ledger.json"
            formal.atomic_json(ledger, {"attemptOrdinal": 1}, exclusive=True)
            with self.assertRaises(FileExistsError):
                formal.atomic_json(ledger, {"attemptOrdinal": 2}, exclusive=True)

    def test_scorer_preserves_a_formal_case_set_identifier(self) -> None:
        case = synthetic_case(0, "development")
        manifest = {
            "corpusVersion": "synthetic",
            "cases": [case],
        }
        run = {
            "caseId": case["id"], "status": "COMPLETED", "failureKind": None,
            "failureReason": None, "findings": [], "acVerdicts": [], "usage": None,
        }
        metadata = {
            "runKind": "MODEL_EVALUATION", "arm": runner.ARMS[0],
            "caseSetVersion": "formal-test-v1",
            "config": {"model": "synthetic", "temperature": 0, "promptVersion": "test"},
        }
        report = score.score_corpus(manifest, {case["id"]: run}, metadata, {})
        self.assertEqual(report["caseSetVersion"], "formal-test-v1")


if __name__ == "__main__":
    unittest.main()
