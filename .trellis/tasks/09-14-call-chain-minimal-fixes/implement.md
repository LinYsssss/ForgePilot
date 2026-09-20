# Implementation and verification plan

## Planning gate

- [x] Read the product/architecture authority and workflow; inspect each affected boundary.
- [x] Link the seven requirements to the read-only audit and its isolated probes.
- [x] Write `prd.md` and `design.md`; separate the minimal fix from optional tracing work.
- [x] Obtain the repository-required approval of the final planning summary (2026-09-15).
- [x] Run `task.py start` for this task under the same session context.

Session context for task commands:

```sh
env TRELLIS_CONTEXT_ID=codex-call-chain-minimal-20260914 python3 .trellis/scripts/task.py current --source
```

## Ordered changes

1. [x] Complete relevant backend/frontend spec reads before editing. Work in the
       main session; no subagents are authorized.
2. [x] Fix embedding index validation and existing review_id audit linkage.
       Add real-HTTP counterexamples and verify FK/project isolation.
3. [x] Fix final prompt budgeting, reuse the prompt renderer, and fail explicitly
       at the chat boundary on residual overflow. Preserve accurate coverage.
4. [x] Centralize pre-request lease checks and runtime-failure cleanup. Include
       repair, recall, retry, ownership loss and failed result-commit cases.
5. [x] Run focused AI/review tests and inspect the whole first change group for
       duplicate budgets, callbacks, exception handlers and misleading comments.
6. [x] Move document embedding into one serial processor over existing PENDING
       document rows. Preserve attachment atomicity and READY/FAILED semantics;
       ensure review reconciliation is not blocked by the processor.
7. [x] Add one finite polling composable; update every pending-status consumer
       with route-safe, form-preserving refresh.
8. [x] Update API/architecture and prevention specs for actual changed behavior.
       Do not edit historical migration files or frozen evaluation output.
9. [x] Run required full gates, review the complete diff, and remove unnecessary
       helpers or compatibility branches introduced only during development.
10. [x] Report what changed, test evidence and remaining limitations. Prepare
        logical commit grouping; do not commit/push/deploy without the applicable
        repository review/authorization.

## Focused verification

- Extend existing AiGateway tests: correct index ordering, duplicate/missing/
  out-of-range indexes, review-aware audit context, pre-attempt callback on retry,
  and over-budget chat rejection without a wire request.
- Extend batcher/pipeline tests using the audit's large-patch counterexample:
  full formatted payload fits, coverage accurately records omissions, synthesis
  overflow cannot produce a false complete result.
- Extend executor/fencing tests: runtime failure during analyse and result
  transaction, stale ownership during repair/retry, no stale failure event.
- Exercise the new upload contract through MVC with a deliberately blocked
  local/mock embedding. Assert acceptance returns before the provider is
  released, PENDING is visible, database connection is not held for the external
  wait, all vectors and READY commit together, and FAILED is durable.
- Verify pending recovery, delete-during-processing, attachment relationship
  atomicity, and scheduler availability for review reconciliation.
- Frontend fake-timer/request tests verify terminal refresh, no overlapping
  requests, route disposal, stale response rejection, retained form edits and
  pending document presentation.

These are behavioral regressions, not tests that merely mirror a helper's code.
Existing diagnostic artifacts remain immutable evidence and are not overwritten.

## Verification record

- 2026-09-20 backend `./mvnw -B -ntp verify` in the pinned Java 21 container:
  416 tests, 0 failures, 0 errors, `BUILD SUCCESS`.
- 2026-09-20 frontend gates: lint and typecheck passed; 18 test files / 63 tests
  passed; production build passed.
- Final `git diff --check`, task context validation and architecture/configuration
  checks passed. The tree remains at nine production packages, 21 business tables
  and 14 migrations, with no dependency or migration change.
- 2026-09-20 production deployment completed at commit `4121cea`, preserving
  PostgreSQL and Flyway V14. Internal and public health checks passed.
- 2026-09-20 production revalidation completed for all 15 existing real PR
  snapshots. Reviews 17–31 are COMPLETED; their 45 REVIEW/EMBEDDING AI calls are
  successful and linked to the correct Review IDs. Historical reviews and
  decisions remain intact. See `deployment.md` and
  `production-revalidation.md`.

## Required final gates

Use the current pinned container path in `docs/v2/DEFENSE-GUIDE.md`; the host has
no JDK. The established Java 21 invocation is:

```sh
docker run --rm --network host \
  -v /root/ForgePilot/backend:/workspace \
  -v /root/.m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

Frontend commands run with the repository-supported Node version in `frontend/`:

```sh
npm run lint
npm run typecheck
npm test -- --run
npm run build
```

Read fresh test results rather than quoting old documented case counts. After
successful gates, inspect `git diff --check`, the final file inventory and the
architecture/configuration checks in the spec. Repeat only affected checks
when new changes or failures justify it. No formal evaluation or live-generation
smoke is part of this plan.
