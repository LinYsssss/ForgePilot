# Review and requirement concurrency consistency

## Goal

Fix the four independently reproduced defects reported by the user on `main` so
that delayed or concurrent work cannot change the meaning of a human review.

## Authorized scope

The user explicitly requested implementation of these four fixes. Preserve the
existing product rules, API shapes, project isolation, and single Review Engine.
No deployment or remote repository changes are part of this task.

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

## Exclusions

Schema migrations, new workflows, dependency additions, evaluation reruns,
automatic commit/push, and deployment.
