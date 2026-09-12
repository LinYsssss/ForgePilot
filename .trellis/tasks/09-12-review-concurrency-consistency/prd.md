# Review and requirement concurrency consistency

## Goal

Fix the four independently reproduced defects reported by the user on `main` so
that delayed or concurrent work cannot change the meaning of a human review.

## Authorized scope

The user explicitly requested implementation of these four fixes. Preserve the
existing product rules, API shapes, project isolation, and single Review Engine.
On 2026-09-12 the user additionally requested push, deployment, cleanup of prior
review records, and a sequential real-workflow exercise of 15 demo PRs. That
follow-up authorizes the corresponding operational work on the existing demo
stack and three demo repositories.

## Acceptance criteria

1. After navigating between reviews or projects, delayed association, decision,
   Finding, and event responses cannot overwrite the active page's data, errors,
   comments, or pending flags. Decisions always target the displayed review.
2. Concurrent requirement writes observe the latest lifecycle and revision before
   checking permissions to mutate it. Cancellation/deletion remain final; entering
   READY freezes both draft prose and acceptance criteria. Concurrent assignments
   and publications preserve each other's changes.
3. A manual re-review concurrent with a PR update stores matching review identity
   and immutable PR/diff context. Repeated creation remains idempotent.
4. GitHub synchronization detects changes during metadata/file pagination,
   retries only within a bounded budget, and stores no mixed snapshot if the PR
   remains unstable.
5. Regression tests exercise actual delayed responses, PostgreSQL contention, and
   provider changes. Frontend gates and the backend verification suite pass.
6. Push and deploy the verified fixes, preserve a checked database backup, and
   remove the prior review chain without erasing project setup or formal
   evaluation assets.
7. Process 15 demo PRs one at a time through requirement assignment, real SCM
   ingestion, AI review, explicit inspection of findings, and role-appropriate
   human decisions. Record actual results and distinguish pending work from
   completed checks.

## Exclusions

Schema migrations, new product workflows, application dependency additions,
formal evaluation reruns, and unrequested changes to the demo repositories'
main branches.
