# Result

## Outcome

D020 is implemented with the approved minimum product shape: account display names, project role sets,
user-owned verified SCM identities, and project-specific SCM binding history. No backend top-level package,
primary navigation entry, runtime dependency, OAuth flow, generic authorization/approval framework, member
removal model, second AI runtime, or second Review pipeline was added.

The existing `fp-demo` database volume was removed under explicit user authorization and the stack was rebuilt
from the final working tree. The new deployment is healthy and contains no business data.

## Measured shape

- Backend: 8 top-level packages, 19 business tables, 8 Flyway migrations, 317 tests passed, 0 failed/errors/skipped.
- Frontend: 6 primary navigation entries, 11 product routes, 11 test files / 35 tests, lint/typecheck/build green.
- Providers: GitHub and GitLab verification paths preserve their respective API base paths.
- Deployment: backend and proxied health both `UP`, frontend HTTP 200, 8 successful migrations through V8,
  19 tables, 0 business rows.

## Verification

- `./mvnw -B -ntp verify` in a Java 21 container with real PostgreSQL 15 + pgvector: BUILD SUCCESS,
  317 tests, zero skips.
- `npm run lint`, `npm run typecheck`, `npm run test -- --run`, `npm run build`: all green; 35 tests.
- Representative V7-to-V8 fixture: display name, role, legacy SCM identity/binding conversion and old-column
  removal all proven in one test.
- `scripts/phase1-compose-smoke.sh`: isolated empty-volume build/boot passed with V8 and exactly 19 tables;
  the script now uses random host ports so it can coexist with a running deployment.
- `git diff --check`, shell syntax, old-field boundary search, package/migration/route counts: green.
- Formal evaluation freeze/corpus/holdout/raw outputs were not edited or rerun.

## Remaining manual acceptance

The real-browser end-to-end flow in `docs/v2/FULL-CHAIN-UI-TEST.md` remains intentionally unexecuted because
the user postponed testing. The guide covers registration, batch membership, multi-role behavior, two identities,
default/strict binding, PR author mapping and the existing Requirement-to-Review chain.

## Deployment reset

Removed: `fp-demo` containers/network and the prior `fp-demo_postgres-data` volume. The deleted database data is
not recoverable from this workspace without an external backup. `cpa` and `cpa-manager-plus` were untouched and
remained running. A new `fp-demo_postgres-data` volume was created by the clean deployment.
