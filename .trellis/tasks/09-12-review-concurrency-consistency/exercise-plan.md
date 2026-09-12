# Sequential 15-PR exercise

The user requested actual operations on the three demo projects, one PR at a
time. [exercise-cases.json](exercise-cases.json) contains 15 separately scoped
requirements and 65 concrete acceptance criteria based on the existing archived
review materials. This is a fresh application workflow exercise, independent of
the immutable formal evaluation.

## Planned sequence

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

## Preparation completed; execution pending

- Pushed and deployed the fix; prior review records are backed up and cleared.
- Developer `dev01` and reviewer `rev01` browser login succeeded, each observing
  an empty review list without JavaScript errors.
- All 15 case sources exist and their acceptance criteria are distinct. Fourteen
  existing remote review branches contain only their intended source file and
  review material. The deleted first mall branch can be restored under a fresh
  branch name from verified commit `1338abc40f4e5c0738c51a8640b3207fbbf2c5dc`,
  whose two changed files match the archive byte for byte.
- Temporary browser drivers are prepared in
  `/tmp/forgepilot-sequential-pr-20260912/`: `prepare.mjs`, `run-case.mjs`, and
  `adjudicate.mjs`. Syntax checks pass. Playwright 1.58.2 and Chromium are installed
  outside the application repository.
- No new requirement, PR, or review has been created yet. The current owner
  `ysainlin` does not accept the shared test password (HTTP 401). A valid owner
  login or private credential-file path was requested. Do not reset that owner's
  password, fabricate a session, or change role grants to bypass this prerequisite.

The driver reads an owner password from `FORGEPR_LEADER_PASSWORD_FILE`, or from
`/root/forgepilot-demo-docs/leader-password.txt`. Credential contents and browser
storage state must remain private and outside Git. Complete this plan after the
owner login prerequisite is supplied; the task remains in progress.
