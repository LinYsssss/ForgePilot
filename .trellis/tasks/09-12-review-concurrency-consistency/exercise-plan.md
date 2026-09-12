# Sequential 15-PR exercise

The user requested actual operations on the three demo projects, one PR at a
time. [exercise-cases.json](exercise-cases.json) contains 15 separately scoped
requirements and 65 concrete acceptance criteria based on the existing archived
review materials. This is a fresh application workflow exercise, independent of
the immutable formal evaluation.

## Executed sequence

| Order | Project | Scenario |
|---|---|---|
| 1 | mall | Warehouse shipping preconditions |
| 2 | mall | Forced shipping authorization and audit |
| 3 | mall | Order search binding and data protection |
| 4 | mall | Promotion batch shipping and idempotency |
| 5 | mall | Customer order ownership and masking |
| 6 | tenant | Operations authentication and authorization |
| 7 | tenant | Tenant-scoped user query and pagination |
| 8 | tenant | User export limits and audit |
| 9 | tenant | Password reset authorization and storage |
| 10 | tenant | Safe rendering and preference merging |
| 11 | payment | Instant settlement precision and rules |
| 12 | payment | Refund idempotency, authorization, and logging |
| 13 | payment | Bank callback signature and deduplication |
| 14 | payment | Settlement query tenant isolation |
| 15 | payment | Merchant fee configuration and rounding |

## Operation and evidence

1. Log in with the real owner, developer, and reviewer test roles. Preserve role
   boundaries. Add the existing developer/reviewer to projects 2 and 3 using
   authenticated product endpoints.
2. Connect the two missing repositories, synchronize the demo webhooks, and upload
   the existing project specifications. Require knowledge indexing to be READY.
3. For one case, create a requirement through the browser, publish READY, assign
   its developer and reviewer, and verify the frozen acceptance criteria.
4. Open one real GitHub PR from its existing review branch, with its new REQ ID.
   Wait for the real webhook and AI review to finish before proceeding.
5. Compare the review head/base, fingerprint, requirement revision, and saved
   patches against GitHub. Inspect every finding; supply an explicit confirmation
   or rejection with a concrete reason instead of a blanket automatic verdict.
6. Use reviewer page actions for adjudication and the decision. Use developer page
   actions to claim confirmed issues. Do not label unimplemented fixes as done.
7. Save decision/event evidence, verify the PR and requirement state, then allow
   the next case to start. The driver refuses to start a case until all preceding
   cases are marked complete.

The existing archived branches contain deliberate defects. Decisions must follow
the actual evidence; this exercise does not authorize merging those defects into
the demo main branches.

## Completed and audited

- Pushed and deployed the fix; prior review records are backed up and cleared.
- Owner `ysainlin`, developer `dev01`, and reviewer `rev01` logged into the real
  public browser UI using the supplied credentials. The initial review list was
  empty before this exercise.
- All 15 case sources exist and their acceptance criteria are distinct. Fourteen
  existing remote review branches contain only their intended source file and
  review material. The deleted first mall branch was restored as
  `review/20260912-mall-pr-01-shipping-validation` from verified commit
  `1338abc40f4e5c0738c51a8640b3207fbbf2c5dc`, whose two changed files match the
  archive byte for byte.
- Temporary browser drivers are prepared in
  `/tmp/forgepilot-sequential-pr-20260912/`: `prepare.mjs`, `run-case.mjs`, and
  `adjudicate.mjs`. Syntax checks pass. Playwright 1.58.2 and Chromium are installed
  outside the application repository.
- Project memberships, missing repository connections, and signed GitHub hooks
  were configured. All 12 project specification documents are READY.
- All 15 cases completed AI review, explicit reviewer adjudication,
  REQUEST_CHANGES, and developer claims before the next case started. The reviewer
  confirmed 55 findings and rejected two; exactly 112 finding events were verified.
- Final browser and database checks found five current reviews per project,
  matching PR heads and frozen requirement versions, and no pending/running
  review jobs. Every real GitHub opened delivery was successful.
- [Results, PR/review links, recovery notes, and interpretation limits](live-review-exercise.md)
  and [machine-readable evidence](live-review-results.json) record the completed run.

The driver reads the supplied credentials from a private local file. Credential
contents and browser storage state remain outside Git. A verified post-exercise
database backup preserves the new records; see [deployment.md](deployment.md).
