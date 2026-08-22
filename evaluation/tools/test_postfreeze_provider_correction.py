#!/usr/bin/env python3
"""Synthetic tests for the post-freeze provider endpoint correction."""

from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


TOOL_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOL_DIR))

import formal_evaluation as formal  # noqa: E402
import postfreeze_provider_correction as correction  # noqa: E402


def correction_document(config: dict, freeze: dict) -> dict:
    document = {
        "correctionVersion": correction.CORRECTION_VERSION,
        "createdAt": "2026-08-22T00:00:00Z",
        "reason": "Synthetic endpoint-only correction.",
        "originalFreezeHash": freeze["freezeHash"],
        "originalEndpointIdentity": config["provider"]["endpointIdentity"],
        "correctedEndpointIdentity": "https://provider.invalid/v1/chat/completions",
        "model": config["provider"]["model"],
        "modelChanged": False,
        "temperatureChanged": False,
        "promptOrScorerChanged": False,
        "corpusOrSplitChanged": False,
        "holdoutStateBeforeCorrection": {
            "ledgerExists": False,
            "outputExists": False,
            "providerCalls": 0,
        },
        "runnerSha256": formal.sha256_file(Path(correction.__file__)),
    }
    document["correctionHash"] = correction.correction_hash(document)
    return document


class PostfreezeProviderCorrectionTest(unittest.TestCase):

    def setUp(self) -> None:
        self.config = formal.load_config()
        self.freeze = formal.verify_freeze(
            formal.rooted(self.config["freezeArtifact"]), verify_current_files=False,
        )

    def load(self, document: dict) -> dict:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "correction.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            with mock.patch.object(correction, "CORRECTION_PATH", path):
                return correction.load_correction(self.config, self.freeze)

    def test_exact_endpoint_only_correction_is_accepted(self) -> None:
        document = correction_document(self.config, self.freeze)
        self.assertEqual(self.load(document), document)

        runtime = correction.runtime_config(self.config, document)
        self.assertEqual(
            runtime["provider"]["endpointIdentity"],
            document["correctedEndpointIdentity"],
        )
        original_without_endpoint = copy.deepcopy(self.config)
        runtime_without_endpoint = copy.deepcopy(runtime)
        del original_without_endpoint["provider"]["endpointIdentity"]
        del runtime_without_endpoint["provider"]["endpointIdentity"]
        self.assertEqual(runtime_without_endpoint, original_without_endpoint)

    def test_tampering_is_rejected_by_the_correction_hash(self) -> None:
        document = correction_document(self.config, self.freeze)
        document["correctedEndpointIdentity"] = "https://tampered.invalid/v1/chat/completions"
        with self.assertRaisesRegex(formal.FormalError, "hash is invalid"):
            self.load(document)

    def test_model_prompt_and_corpus_changes_are_rejected(self) -> None:
        for field in ("modelChanged", "promptOrScorerChanged", "corpusOrSplitChanged"):
            document = correction_document(self.config, self.freeze)
            document[field] = True
            document["correctionHash"] = correction.correction_hash(document)
            with self.subTest(field=field), self.assertRaises(formal.FormalError):
                self.load(document)

    def test_non_chat_or_non_https_endpoint_is_rejected(self) -> None:
        for endpoint in ("http://provider.invalid/v1/chat/completions", "https://provider.invalid/v1"):
            document = correction_document(self.config, self.freeze)
            document["correctedEndpointIdentity"] = endpoint
            document["correctionHash"] = correction.correction_hash(document)
            with self.subTest(endpoint=endpoint), self.assertRaisesRegex(
                formal.FormalError, "HTTPS /chat/completions",
            ):
                self.load(document)


if __name__ == "__main__":
    unittest.main()
