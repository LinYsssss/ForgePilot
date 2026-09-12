# Verification — 2026-09-12

Base: `main` at `3b7fda5`; results below include the uncommitted task changes.

## Regression evidence

- Before the frontend fix, the new navigation suite reported 13 failures and one
  passing control. Delayed responses replaced the active review/PR, erased input
  on a later visit, or changed the next operation's pending/error state.
- Before the backend fixes, the targeted run reported 15 failures in 36 cases.
  Real PostgreSQL contention reproduced terminal-state revival, draft freeze
  violations, lost reviewer assignment, repeated deletion, and a Review whose
  identity referenced head A while its immutable context contained head B.
  The provider fixture also demonstrated missing confirmation and pagination
  consistency checks.
- After the fixes, both targeted suites passed: 14 frontend cases and 36 backend
  cases, with no failures or errors.

## Final gates

| Gate | Result |
|---|---|
| Frontend `npm ci` | Passed |
| Frontend `npm run lint` | Passed |
| Frontend `npm run typecheck` | Passed |
| Frontend `npm test -- --run` | 16 files, 58 tests passed |
| Frontend `npm run build` | Passed |
| Backend `./mvnw -B -ntp verify` | 395 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS |
| Architecture/configuration checks | 9 packages, 21 business tables, 14 migrations; existing JVM/schema/test boundaries preserved |
| `git diff --check` | Passed |
| Trellis context validation | Passed |

The backend used the documented `eclipse-temurin:21-jdk` container with real
PostgreSQL 15/pgvector through Testcontainers. Provider calls used loopback stubs;
the formal evaluation and running deployment were not involved.

The first full backend run caught two existing assertions expecting only one
metadata read. They now assert the exact metadata → files → metadata sequence;
the oversized-manifest test still requires refusal and zero stored PR rows.
The final full run completed at `2026-09-12T03:15:40Z`.

## Local logs

- `/tmp/forgepilot-concurrency-frontend-before.log`
- `/tmp/forgepilot-concurrency-frontend-focused.log`
- `/tmp/forgepilot-concurrency-frontend-install.log`
- `/tmp/forgepilot-concurrency-frontend-test.log`
- `/tmp/forgepilot-concurrency-frontend-build.log`
- `/tmp/forgepilot-concurrency-backend-before.log`
- `/tmp/forgepilot-concurrency-backend-focused.log`
- `/tmp/forgepilot-concurrency-backend-verify-final.log`

These gates passed before commit or deployment. On 2026-09-12 the user authorized
push, deployment, cleanup of prior review records, and a sequential exercise of
the 15 demo PRs. Operational results are recorded separately from these automated
test results.
