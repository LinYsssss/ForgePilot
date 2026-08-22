# Phase 8: GitLab, formal evaluation, and defense reproducibility

## Goal

Finish the last product phase without widening ForgePilot's V2 boundary: prove that the existing SCM contract works with GitLab, freeze and run the thesis evaluation exactly once against the original 26-case development and 12-case holdout split, and leave a clean deployment and evidence package that can be reproduced during the defense.

The separate user-requested visual rebuild based on `ForgePilot-Frontend/` begins only after this task freezes the final feature set.

## Product requirements

1. GitLab merge requests use the same `scm` contract, `pull_request` table, `PullRequestChanged` event, Review Engine, requirement association rules, author identity rules, and human review workflow as GitHub pull requests.
2. GitLab webhook authentication is performed on the untouched request body before any write or provider fetch. Unknown repositories, malformed routing input, and invalid tokens are indistinguishable and write nothing.
3. A webhook is only a synchronization signal. The saved merge-request snapshot comes from GitLab's API, resists replay and out-of-order delivery, paginates changed files, preserves explicit missing/truncated patches, and produces the existing deterministic review fingerprint.
4. Repository registration and the project settings UI can select either GitHub or GitLab without adding a route, table, top-level package, dependency, or top-level navigation entry.
5. The evaluation configuration is frozen before any holdout fixture, truth, ID, or model output is read. The freeze records the corpus source commit, runner/scorer version, prompt/schema, arms, model, temperature, retry/timeout policy, and non-secret endpoint identity.
6. After that freeze, the original split is imported without re-cutting: 26 development cases and 12 holdout cases from the locked Legacy commit. Truth fields never enter model prompts.
7. Holdout is run exactly once. Its outputs are preserved as immutable evidence and are not used to tune prompts, schemas, model parameters, matching rules, aliases, or corpus truth.
8. The formal report presents development, holdout, and full-corpus results for all three experiment arms. It includes Precision, Recall, false-report/miss rates, requirement-violation recall, AC verdict metrics, structure failures, Token usage, latency, and not-run cases. Small-sample uncertainty is stated explicitly and interval estimates are reported where meaningful.
9. A clean checkout can build, start from an empty volume, expose healthy frontend/backend services, and recompute the deterministic report from preserved raw model outputs without provider credentials.
10. No secret, private token, webhook secret, or provider key is committed or printed into evidence.

## Constraints

- Keep exactly sixteen business tables and the eight authorized top-level backend packages.
- Add only the already-authorized `scm.gitlab` provider subpackage; do not add another SCM, Review, AI, queue, agent, patch, or evaluation runtime.
- Do not read or execute holdout material until a committed configuration-freeze artifact exists.
- Never rerun or tune against holdout, even if its quality is lower than expected or some cases fail.
- CI and automated tests use local/JDK fake servers and require no AI or SCM credential.
- Applied Flyway migrations remain append-only. Phase 8 should require no schema change.
- The final `ForgePilot-Frontend/`-based visual overhaul is a subsequent independently verifiable task, not a reason to mix visual churn into the formal experiment.

## Acceptance criteria

- [ ] AC1: `ScmProvider.GITLAB` is implemented through a GitLab adapter and the GitHub path remains green.
- [ ] AC2: GitLab webhook tests cover raw-body authentication, malformed/unknown/invalid indistinguishability, non-MR no-op, valid MR sync, replay, out-of-order input, and zero writes/fetches before authentication.
- [ ] AC3: GitLab API tests cover authoritative MR fields, URL-safe numeric project identity, changed-file pagination, stable source revision/order fields, missing or provider-truncated patches, malformed required fields, rate/error mapping, and explicit total-size rejection.
- [ ] AC4: GitLab MR ingestion creates the same PENDING Review and supports the same requirement association and stable external-author mapping as GitHub.
- [ ] AC5: project settings/API support GitHub and GitLab while credentials remain write-only and the information architecture stays at three top-level entries.
- [ ] AC6: configuration-freeze evidence predates any holdout import/run and contains every non-secret reproducibility parameter plus hashes of the relevant runner, prompt/schema, scorer, and corpus contract.
- [ ] AC7: the imported formal corpus validates as exactly 26 development and 12 holdout cases from the locked Legacy commit, with no split changes and no truth leakage into prompts.
- [ ] AC8: one and only one preserved holdout run exists; the run ledger and artifact hashes prove the sequence, and no post-run tuning change exists.
- [ ] AC9: raw runs and deterministic reports exist for development, holdout, and all 38 cases across all three arms, with failures/not-run entries preserved rather than converted to empty success.
- [ ] AC10: the final report contains all PRD metrics, Wilson intervals for binomial rates where applicable, honest small-sample limitations, frozen model/configuration identifiers, and exact recomputation commands.
- [ ] AC11: backend verify, frontend lint/typecheck/tests/build, evaluation tests/contracts, architecture/boundary checks, and a fresh-volume Compose cold start all pass with zero skipped tests.
- [ ] AC12: a clean-environment defense guide reproduces deployment and rescoring without secrets; secret scans and Git status identify no leaked credential or generated private corpus.
- [ ] AC13: `result.md` records completed and incomplete items, deviations, commands/results, CI state, the exact holdout count, and the handoff boundary for the later reference-frontend rebuild.

## Out of scope

- Changing the accepted three-arm experiment after seeing holdout results.
- Expanding the corpus or replacing the fixed split.
- Adding GitLab-specific business semantics above the provider adapter.
- Redesigning the application UI from `ForgePilot-Frontend/` in this task.
- Adding report export as a product feature; evaluation/defense artifacts remain non-runtime assets.
