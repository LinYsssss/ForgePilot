#!/usr/bin/env python3
"""Synthetic tests for the post-freeze Legacy manifest adapter."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path


TOOL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_DIR))

import formal_evaluation as formal  # noqa: E402
import postfreeze_legacy_adapter as adapter  # noqa: E402


def legacy_case(category: str = "BUSINESS_RULE_RISK") -> dict:
    return {
        "id": "synthetic-legacy",
        "split": "holdout",
        "language": "JAVA",
        "fixture": "cases/synthetic-legacy",
        "requirement": {
            "title": "Synthetic requirement",
            "background": "Synthetic background",
            "description": "Synthetic description",
        },
        "acceptanceCriteria": [
            {"id": "AC1", "text": "The business rule must be preserved."},
            {"id": "AC2", "text": "A safe behavior must not be reported."},
        ],
        "consistencyTruth": [
            {"acId": "AC1", "verdict": "AT_RISK"},
            {"acId": "AC2", "verdict": "COVERED"},
        ],
        "expectedFindings": [{
            "category": category,
            "severity": "HIGH",
            "path": "src/App.java",
            "line": 7,
            "lineEnd": 9,
            "categoryEquivalents": ["UNKNOWN"],
        }],
        "nonFindings": ["safe behavior"],
        "expectedPatch": {"result": "APPLIES_AND_PASSES", "file": "expected.patch"},
    }


class PostfreezeLegacyAdapterTest(unittest.TestCase):

    def test_predecessor_fields_are_mapped_without_changing_split_or_truth(self) -> None:
        normalized = adapter.normalize_case(legacy_case(), 0)

        self.assertEqual(normalized["split"], "holdout")
        self.assertEqual(normalized["fixtureLayout"], "single")
        self.assertEqual(
            normalized["acceptanceCriteria"],
            [
                {"acKey": "AC-0001", "text": "The business rule must be preserved."},
                {"acKey": "AC-0002", "text": "A safe behavior must not be reported."},
            ],
        )
        self.assertEqual(
            normalized["expectedAcVerdicts"],
            [
                {"acKey": "AC-0001", "verdict": "AT_RISK"},
                {"acKey": "AC-0002", "verdict": "COVERED"},
            ],
        )
        self.assertEqual(normalized["expectedFindings"][0], {
            "findingType": "REQUIREMENT",
            "category": "BUSINESS_RULE_RISK",
            "severity": "HIGH",
            "filePath": "src/App.java",
            "lineStart": 7,
            "lineEnd": 9,
            "categoryAliases": ["UNKNOWN"],
        })
        self.assertNotIn("expectedPatch", normalized)
        self.assertNotIn("consistencyTruth", normalized)

    def test_non_business_findings_remain_code_quality_findings(self) -> None:
        normalized = adapter.normalize_case(legacy_case("RESOURCE_LEAK"), 0)
        self.assertEqual(normalized["expectedFindings"][0]["findingType"], "CODE_QUALITY")

    def test_patch_answer_reference_is_identified_for_fixture_omission(self) -> None:
        self.assertEqual(adapter.patch_truth_file(legacy_case()), "expected.patch")
        case = legacy_case()
        case["expectedPatch"] = None
        self.assertIsNone(adapter.patch_truth_file(case))

    def test_unknown_legacy_fields_are_rejected(self) -> None:
        case = legacy_case()
        case["postFreezeTruthEdit"] = True
        with self.assertRaisesRegex(formal.FormalError, "incompatible fields"):
            adapter.normalize_case(case, 0)


if __name__ == "__main__":
    unittest.main()
