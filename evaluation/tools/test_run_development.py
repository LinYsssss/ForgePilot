#!/usr/bin/env python3
"""Contract tests for the Phase 6 development-only three-arm runner."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path


TOOL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_DIR))

import run_development as runner  # noqa: E402
import score  # noqa: E402


class DevelopmentRunnerTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest = score.validate_corpus(runner.MANIFEST_PATH, runner.CASE_SET_PATH)
        cls.by_id = {case["id"]: case for case in cls.manifest["cases"]}

    def test_all_frozen_cases_render_under_the_prompt_budget(self) -> None:
        for arm in runner.ARMS:
            for case in self.manifest["cases"]:
                prompt = runner.prompt_for(case, arm)
                self.assertLessEqual(len(prompt), runner.PROMPT_CHAR_LIMIT)
                self.assertNotIn("expectedFindings", prompt)
                self.assertNotIn("expectedAcVerdicts", prompt)
                self.assertNotIn("nonFindings", prompt)

    def test_arm_context_is_incremental_and_does_not_leak_knowledge(self) -> None:
        case = self.by_id["biz-currency-unchecked"]
        knowledge_text = runner.knowledge(case)[0]["content"]
        diff_only = runner.prompt_for(case, "DIFF_ONLY")
        requirement = runner.prompt_for(case, "DIFF_REQUIREMENT_AC")
        full = runner.prompt_for(case, "DIFF_REQUIREMENT_AC_KNOWLEDGE")

        self.assertNotIn(case["requirement"]["title"], diff_only)
        self.assertNotIn(case["acceptanceCriteria"][0]["text"], diff_only)
        self.assertNotIn(knowledge_text, diff_only)
        self.assertIn(case["requirement"]["title"], requirement)
        self.assertIn(case["acceptanceCriteria"][0]["text"], requirement)
        self.assertNotIn(knowledge_text, requirement)
        self.assertIn(knowledge_text, full)

    def test_base_head_and_single_layouts_both_produce_real_hunks(self) -> None:
        base_head = runner.changed_files(self.by_id["sec-java-customer-search-sqli"])
        single = runner.changed_files(self.by_id["java-sql-resource-leak"])
        self.assertTrue(any("@@" in item["patch"] for item in base_head))
        self.assertTrue(any("@@" in item["patch"] for item in single))
        self.assertTrue(all(item["path"] for item in base_head + single))

    def test_normalized_answer_rejects_unknown_ac_keys(self) -> None:
        case = self.by_id["java-sql-resource-leak"]
        with self.assertRaises(runner.RunnerError):
            runner.normalized_answer(
                {"findings": [], "acVerdicts": [{"acKey": "AC-9999", "verdict": "COVERED"}]},
                case,
            )


if __name__ == "__main__":
    unittest.main()
