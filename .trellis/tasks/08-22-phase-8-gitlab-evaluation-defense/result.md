# Phase 8 result

## Outcome

Phase 8 is complete. GitLab uses the existing SCM and Review contracts, the locked 26-case development and 12-case holdout corpus was evaluated across all three arms, the one-time holdout ledger is complete, deterministic reports and an independent rescore exist, and every credential-free application gate passed.

The later `ForgePilot-Frontend/` visual rebuild remains a separate task. It starts from this completed feature boundary and may adapt the reference layouts to real functionality without adding routes, navigation entries, tables, backend packages, or duplicate runtimes.

## Delivered commits

| Commit | Delivery |
| --- | --- |
| `a96bcdc` | GitLab merge-request provider and webhook/API coverage |
| `3774804` | GitLab repository settings in the official frontend |
| `bd51a0a` | Formal experiment toolchain |
| `71c25e4` | Phase 8 research, defense guide, and cold-start support |
| `f87cacb` | Committed Phase 8 configuration freeze |

These commits are local. No push was authorized or performed. Post-run evidence and the endpoint-correction adapter are not yet committed.

## Freeze, correction, and corpus

- Original freeze: `forgepilot-configuration-freeze-v1`
- Frozen at: `2026-08-22T13:57:14.083757Z`
- Freeze hash: `55fc3176b6c843d214aa405781c1fe404ee7663c3e177f8a3cd67d9b22021e5d`
- Locked source commit: `96137dd3b43e14c5e8881c99688663afd979cf4e`
- Model / temperature: `gpt-5.6-luna` / `0.0`
- Arms: `DIFF_ONLY`, `DIFF_REQUIREMENT_AC`, `DIFF_REQUIREMENT_AC_KNOWLEDGE`
- Normalized corpus: 26 development + 12 holdout cases
- Corpus manifest SHA-256: `e00d9186537167443390c7c3d9852672602fea0d7d62f33b29f10af05ea03a0a`

The original freeze accidentally recorded the public OpenAI endpoint, while the supplied credential belongs to an OpenAI-compatible third-party service. The public endpoint returned HTTP 401 for all development attempts. Before any holdout ledger, output, or provider call, an independently content-addressed correction changed only the effective endpoint to `https://597965.xyz/v1/chat/completions`. Model, temperature, prompt, schema, runner, scorer, aliases, corpus, truth, and 26/12 split did not change.

This correction happened after private corpus import and is therefore an explicit protocol deviation from the original endpoint-freeze sequence. It is not presented as a blind pre-import correction. Its record and runner are bound by correction hash `8c2401958679866753b87926d8c1b28939a2390189c612b3421409139734100c`. The original freeze and failed 401 attempt remain preserved.

The locked Legacy manifest also required a documented `REWRITE_KEEP_DATA` field-name migration. The adapter omitted the Legacy `cases/typescript-known-patch/expected.patch` answer file so it could not enter a model prompt. All ten input/truth fields of the twelve cases already represented in the Phase 1 quick contract matched exactly after normalization.

## One-time execution proof

Canonical development completed before holdout:

- 26 cases per arm
- 26 completed, 0 failed, 0 not-run per arm
- 0 structure failures per arm

The holdout ledger was created atomically before its first provider call:

| Field | Value |
| --- | --- |
| Attempt ordinal | 1 |
| Status | `COMPLETE` |
| Started | `2026-08-22T14:40:38.465685Z` |
| Completed | `2026-08-22T14:46:43.362026Z` |
| Cases per arm | 12 |
| Completed per arm | 12 |
| Failed / not-run / structure failures | 0 / 0 / 0 |
| Ledger SHA-256 | `5de11b7c07778a6742d037a472e84ddeaef8693850e5080944c69e0b39e42783` |

No second holdout run was made. Six raw run-envelope hashes, the ledger hash, effective provider identity, and correction hash are recorded in `evidence/formal-run-evidence.json`.

## Formal metrics

Rates below are deterministic scorer outputs. `False report` is `FP / (TP + FP)` and `miss` is `FN / (TP + FN)`. The diff-only arm intentionally receives no acceptance criteria and returns an empty AC-verdict array, so its AC accuracy is zero by experiment design rather than a structure failure.

| Split | Arm | Precision | Recall | False report | Miss | Requirement recall | AC accuracy | Structure failures |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Development | Diff only | 14.29% | 13.64% | 85.71% | 86.36% | 0.00% | 0.00% | 0 |
| Development | Diff + requirement/AC | 31.82% | 31.82% | 68.18% | 68.18% | 100.00% | 94.83% | 0 |
| Development | Diff + requirement/AC/knowledge | 38.10% | 36.36% | 61.90% | 63.64% | 100.00% | 96.55% | 0 |
| Holdout | Diff only | 8.33% | 11.11% | 91.67% | 88.89% | 0.00% | 0.00% | 0 |
| Holdout | Diff + requirement/AC | 6.67% | 11.11% | 93.33% | 88.89% | 33.33% | 96.00% | 0 |
| Holdout | Diff + requirement/AC/knowledge | 20.00% | 22.22% | 80.00% | 77.78% | 66.67% | 92.00% | 0 |
| Full 38 | Diff only | 12.12% | 12.90% | 87.88% | 87.10% | 0.00% | 0.00% | 0 |
| Full 38 | Diff + requirement/AC | 21.62% | 25.81% | 78.38% | 74.19% | 80.00% | 95.18% | 0 |
| Full 38 | Diff + requirement/AC/knowledge | 32.26% | 32.26% | 67.74% | 67.74% | 90.00% | 95.18% | 0 |

### Holdout 95% Wilson intervals

| Arm | Precision | Recall | False report | Miss | Requirement recall | AC accuracy | Structure failure rate |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Diff only | 1.49–35.39% | 1.99–43.50% | 64.61–98.51% | 56.50–98.01% | 0.00–56.15% | 0.00–13.32% | 0.00–24.25% |
| Diff + requirement/AC | 1.19–29.82% | 1.99–43.50% | 70.18–98.81% | 56.50–98.01% | 6.15–79.23% | 80.46–99.29% | 0.00–24.25% |
| Diff + requirement/AC/knowledge | 5.67–50.98% | 6.32–54.74% | 49.02–94.33% | 45.26–93.68% | 20.77–93.85% | 75.03–97.78% | 0.00–24.25% |

The intervals are wide because the holdout contains only twelve cases. Observed arm differences are descriptive, not strong population claims.

### Usage and latency

| Split | Arm | Input tokens | Output tokens | Mean latency ms |
| --- | --- | ---: | ---: | ---: |
| Development | Diff only | 29,660 | 11,110 | 9,467.96 |
| Development | Diff + requirement/AC | 33,903 | 14,327 | 11,281.85 |
| Development | Diff + requirement/AC/knowledge | 35,382 | 13,158 | 10,386.12 |
| Holdout | Diff only | 12,579 | 5,234 | 9,537.42 |
| Holdout | Diff + requirement/AC | 14,452 | 6,794 | 11,453.75 |
| Holdout | Diff + requirement/AC/knowledge | 15,252 | 5,291 | 9,322.25 |

The low finding scores despite high AC accuracy reflect the scorer's intentionally strict match on finding type, normalized category, changed path, and overlapping line range. Semantically related but differently typed or categorized findings remain unmatched; no aliases or truth were changed after seeing these results.

## Deterministic report reproduction

The canonical report contains 23 files. An independent rescore to `/tmp/forgepilot-formal-rescore` reproduced every file byte-for-byte except the expected `formal-summary.json.generatedAt` timestamp. After removing that timestamp, both trees have aggregate SHA-256:

`89ee9ee4dc1aeb3d1975e9c4b66289d11cbed0fcc5bc10123e18409bcd0a26c0`

Canonical summary hashes:

- JSON: `c7d1772f5db991736b0acb485b62ba09728ec34d560a35f1a3ad623d09fc3bc1`
- Markdown: `8eeb842f2bee7ca66116562fe4e72c485b71f90f28479744cfc625fed9be7563`

## Application verification

| Gate | Result |
| --- | --- |
| Backend `./mvnw -B -ntp verify` under Java 21 | 307 tests, 0 failures, 0 errors, 0 skips; `BUILD SUCCESS` |
| Frontend install/lint/typecheck/test/build | 7 test files, 20 tests; all gates passed |
| Evaluation unit tests | 18 passed after endpoint-correction coverage was added |
| Deterministic scorer self-test | 30 checks passed |
| Freeze and corpus integrity | valid; 38 cases with exact 26/12 split |
| Empty-volume Compose cold start | PostgreSQL, backend, and frontend healthy; pgvector 0.8.6; 16 business tables |
| Architecture and information boundary | 8 top-level backend packages; 3 top-level navigation entries; 7 product route paths |
| Migration / secret / whitespace audit | 7 migrations; 16 business tables; no tracked OpenAI-key pattern; `git diff --check` passed |

The disposable Compose project, network, containers, and PostgreSQL volume were removed. External CI is not proven because the five authorized commits remain local and no push was authorized.

## Commands

```bash
python3 evaluation/tools/formal_evaluation.py verify-freeze
python3 evaluation/tools/postfreeze_legacy_adapter.py
python3 evaluation/tools/postfreeze_provider_correction.py verify
python3 evaluation/tools/postfreeze_provider_correction.py probe
python3 evaluation/tools/postfreeze_provider_correction.py run --split development
python3 evaluation/tools/postfreeze_provider_correction.py run --split holdout  # executed once
python3 evaluation/tools/formal_evaluation.py report
python3 evaluation/tools/formal_evaluation.py report --out-dir /tmp/forgepilot-formal-rescore

python3 -m unittest discover -s evaluation/tools -p 'test_*.py'
python3 evaluation/tools/score.py --selftest
```

Deployment, demonstration, clean-build, rescore, secret, and cleanup steps are in `docs/v2/DEFENSE-GUIDE.md`.

## Rollback and evidence preservation

- The completed holdout ledger and raw outputs are immutable evidence. Never delete, overwrite, or rerun them; a different experiment requires a new case-set/configuration identity.
- The endpoint-correction wrapper may be removed only if these formal results are abandoned together. It must not be folded into the original freeze or rewritten to conceal the protocol deviation.
- GitLab provider and settings changes remain independently revertible and add no migration. Applied Flyway migrations stay untouched.
- The visual rebuild must preserve real behavior and can be reverted independently from this formal evidence package.

## Visual-rebuild handoff

The next task may inspect the untracked `ForgePilot-Frontend/` reference and use its visual system as the basis for the official Vue frontend. It must adapt mock layouts to the real APIs, session, roles, project scope, requirements, reviews, findings, GitHub/GitLab settings, and error/loading/empty states. The product boundary remains exactly three top-level navigation entries and seven approved product route paths. The reference directory itself stays untracked.
