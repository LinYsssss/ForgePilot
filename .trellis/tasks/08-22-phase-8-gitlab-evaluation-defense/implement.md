# Phase 8 implementation plan

## Gate 0: baseline and research

- [x] Record the starting Git status and Phase 7 exit evidence.
- [x] Read backend/frontend specs and the existing GitHub/SCM/evaluation contracts.
- [x] Persist GitLab API/webhook protocol research without reading holdout data.
- [x] Run focused baseline tests for SCM, evaluation, and frontend project settings.

## Slice 1: GitLab provider

- [x] Add `ScmProvider.GITLAB` and `scm.gitlab` package documentation.
- [x] Implement constant-time GitLab token verification and uniform pre-auth failure behavior.
- [x] Implement the GitLab webhook endpoint over raw bytes.
- [x] Implement authoritative MR and paginated diff retrieval through per-repository `api_base`.
- [x] Normalize GitLab fields into `PullRequestSnapshot`, including stable diff revision and explicit null patches.
- [x] Cover malformed fields, pagination, 429/errors, size limit, replay, ordering, association, author mapping, Review creation, and cross-project boundaries.
- [x] Add GitLab to project settings/API provider selection without exposing credentials.
- [x] Run backend focused tests and all five frontend gates.

## Slice 2: formal evaluation tooling (holdout still locked)

- [x] Generalize the runner without changing the production Review Engine or deterministic scorer.
- [x] Add strict full-corpus validation (26 development + 12 holdout), truth-leakage guards, run-ledger semantics, and tests using synthetic fixtures only.
- [x] Add reporting of development/holdout/full metrics and Wilson intervals.
- [x] Add freeze generation and hash verification tooling.
- [x] Run unit/self/contract tests and dry runs using development/synthetic data only.
- [x] Run full backend/frontend/evaluation validation and boundary checks.

## Gate 3: configuration freeze

- [x] Resolve the exact model, temperature, endpoint identity, prompt/schema, runner, scorer, aliases, corpus source commit, timeout/retry, and arms.
- [ ] Ensure all output-affecting and scoring files are final and the relevant tracked worktree is clean/committed or fully content-addressed.
- [ ] Write the freeze artifact and verify all recorded hashes.
- [ ] Record proof that holdout has not yet been imported, read, or run.

## Slice 4: one-time formal run

- [ ] Acquire the locked original corpus only after Gate 3.
- [ ] Validate source commit, fixture hashes, and exact 26/12 split without changing it.
- [ ] Run all three arms on development and preserve raw outputs.
- [ ] Start the atomic holdout ledger, run all three arms exactly once, and preserve partial failures/not-run entries if any.
- [ ] Generate deterministic development, holdout, and full reports plus uncertainty summary.
- [ ] Verify no prompt/scorer/config change occurred after freeze and no second holdout run exists.

## Slice 5: defense reproduction and exit gate

- [ ] Write clean deployment, demo, rescore, and secret-handling instructions.
- [ ] Run backend `./mvnw -B -ntp verify` with zero skips.
- [ ] Run frontend `npm ci`, lint, typecheck, tests, and build.
- [ ] Run evaluation unit tests, schema/corpus validation, scorer self-test, and raw-output report recomputation.
- [ ] Run a fresh-volume Compose cold start and verify all three services plus exactly sixteen tables.
- [ ] Run architecture, dependency, route/menu, migration/table, credential, and `git diff --check` audits.
- [ ] Confirm CI state when a pushed commit exists; otherwise record that CI is not yet externally proven.
- [ ] Complete `result.md` with evidence, deviations, limitations, rollback, and the explicit handoff to the later `ForgePilot-Frontend/` visual rebuild.

## Rollback points

- Before freeze, GitLab, frontend selection, evaluation tooling, and docs are independently revertible file groups.
- After freeze, never rewrite or replace formal raw results. A failed/partial holdout stays as evidence and is reported honestly.
- Applied Flyway migrations are not edited; this task is expected to add none.
