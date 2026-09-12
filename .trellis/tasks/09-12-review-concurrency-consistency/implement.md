# Implementation and validation

- [x] Add delayed-response component regressions and page identity checks.
- [x] Add PostgreSQL contention regressions and a shared requirement write lock.
- [x] Add concurrent manual-review/PR-update regressions and lock identity reads.
- [x] Add provider-change/pagination regressions and bounded GitHub consistency reads.
- [x] Run focused regressions; demonstrate the original defects where practical.
- [x] Run frontend `npm ci`, lint, typecheck, tests, and production build.
- [x] Run backend `./mvnw -B -ntp verify` via the documented Java 21 container.
- [x] Review the complete diff against backend/frontend specs and update the
      prevention guidance for the newly enforced concurrency invariants.
- [x] Record verification evidence and present the concrete changes for review.
- [ ] Commit and push the verified fixes under the user's follow-up authorization.
- [ ] Back up the existing demo database and deploy the application containers.
- [ ] Clear the prior review records while preserving project setup and formal evaluation assets.
- [ ] Exercise the 15 demo PR reviews sequentially and record each outcome.
- [ ] Archive the completed task and record the session after operational verification.

The existing unrelated planning task is not modified. Implementation runs in the
main agent under the user's explicit fix request; no agent delegation is used.
