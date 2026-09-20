# Production PR revalidation — 2026-09-20

## Scope and method

The deployed call-chain fixes were exercised against the same 15 real GitHub
PR snapshots used by the earlier sequential exercise. A COMPLETED Review is
immutable and cannot legally be rerun, so each IN_DEVELOPMENT requirement
received a new revision with identical title, background, description,
acceptance-criterion keys, and acceptance-criterion text. The revision reason
was `2026-09-20 部署后调用链复验（序号 NN/15），不改变需求语义。`.

For each case, the driver performed these operations serially:

1. Read and validate the current requirement and its prior exercise mapping.
2. Publish or recover the uniquely identified revalidation revision.
3. Find or request exactly one Review for that PR and new revision.
4. Wait for COMPLETED or FAILED before starting the next case.
5. Assert current Review identity, requirement revision, PR head, input
   fingerprint, and immutable context snapshot agreement.

The driver was restart-safe and refused duplicate matching revisions or
Reviews. It did not alter Finding status, submit a new Review decision, claim
developer work, change a PR branch, or delete historical business data.

Run window: `2026-09-20T07:52:43.568Z` through
`2026-09-20T08:15:25.792Z`. Deployed commit: `4121cea`.

## Results

| Seq | Project | PR row | Requirement | Revision | Review | Findings | Result |
|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 1 | 2 | 3 | 19 | 17 | 4 | COMPLETED |
| 2 | 1 | 3 | 4 | 20 | 18 | 3 | COMPLETED |
| 3 | 1 | 4 | 5 | 21 | 19 | 5 | COMPLETED |
| 4 | 1 | 5 | 6 | 22 | 20 | 5 | COMPLETED |
| 5 | 1 | 6 | 7 | 23 | 21 | 4 | COMPLETED |
| 6 | 2 | 7 | 8 | 24 | 22 | 1 | COMPLETED |
| 7 | 2 | 8 | 9 | 25 | 23 | 2 | COMPLETED |
| 8 | 2 | 9 | 10 | 26 | 24 | 6 | COMPLETED |
| 9 | 2 | 10 | 11 | 27 | 25 | 5 | COMPLETED |
| 10 | 2 | 11 | 12 | 28 | 26 | 3 | COMPLETED |
| 11 | 3 | 12 | 13 | 29 | 27 | 2 | COMPLETED |
| 12 | 3 | 13 | 14 | 30 | 28 | 4 | COMPLETED |
| 13 | 3 | 14 | 15 | 31 | 29 | 4 | COMPLETED |
| 14 | 3 | 15 | 16 | 32 | 30 | 3 | COMPLETED |
| 15 | 3 | 16 | 17 | 33 | 31 | 4 | COMPLETED |

All 15 new Reviews used `gpt-5.6-luna`. All 15 requirement revisions have
sequence 2, remain current, and preserve their stable acceptance-criterion
keys. All Review rows match the current PR head, current input fingerprint, and
current requirement revision. Their immutable snapshots carry the same head,
fingerprint, and revision.

## Independent database audit

- Reviews 17–31: 15 rows, 15 COMPLETED, zero PENDING/RUNNING/FAILED.
- New decisions: 15 PENDING. No duplicate business verdict was entered.
- New AI calls: 45 SUCCESS, zero unsuccessful, zero null `review_id`.
- Per new Review: one EMBEDDING call and two REVIEW calls, all linked to that
  exact Review ID.
- Historical Reviews 2–16: 15 COMPLETED and 15 REQUEST_CHANGES; all preserved.
- Revalidation revisions: 15, all sequence 2.
- Knowledge documents: 12 READY, zero PENDING/FAILED.
- Total Review state after the run: 30 COMPLETED, no active row.

The operational log scan for the run window found no warning, error, exception,
lease-loss, prompt-size, or failed-call line. Backend, frontend, PostgreSQL,
loopback health, and public `/api/actuator/health` remained healthy after the
run.

## Evidence

- The [thesis evidence](../../../../../docs/thesis/PRODUCTION-REVALIDATION.md)
  provides a compact per-case CSV and a dependency-free recomputation check.
  It retains identifiers, counts, latency, token use, coverage and consistency
  flags without duplicating PR patches, knowledge excerpts or model prose.
- The temporary restartable driver, browser state and individual API response
  copies were removed after the compact dataset passed its consistency check.
- The post-run verified backup is recorded in [deployment.md](deployment.md).

This was production operational verification of the deployed call chain. It is
separate from automated regression tests and from the immutable formal
evaluation, which was neither modified nor rerun.
