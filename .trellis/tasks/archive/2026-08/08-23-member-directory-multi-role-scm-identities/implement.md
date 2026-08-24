# Implementation plan

## Preconditions

- [x] Receive explicit approval of the final planning summary after `prd.md`, `design.md`, and this file are complete.
- [x] Run `python3 ./.trellis/scripts/task.py start 08-23-member-directory-multi-role-scm-identities` and load `trellis-before-dev` plus all backend/frontend/cross-layer specs it routes to.
- [x] Create `feat/member-directory-scm-identities` from current `origin/main` (`cfcdb8c` at planning time), record it with `task.py set-branch`, and preserve unrelated user changes. Do not push or commit automatically.

## Ordered execution checklist

### 1. Freeze the decision and contracts

- [x] Add D020 to `docs/v2/DECISIONS.md`, explicitly replacing D010's Leader-entered project identity ownership while preserving D010's stable repository/author identity and event-ordering rules.
- [x] Update `docs/v2/PRD.md` and `docs/v2/ARCHITECTURE.md` from 16 to 19 tables while retaining the existing Service + partial-index Leader invariant pattern.
- [x] Define the final API request/response records and role/binding status enums before implementing controllers.
- Review gate: no API accepts a caller-supplied verified external id, and no `project -> scm` dependency appears.

### 2. Add V8 and migration coverage

- [x] Add `V8__member_roles_and_scm_identities.sql` with display-name backfill, three new tables, legacy data copy/quarantine, necessary indexes/FKs/checks, repository approval policy, and removal of old role/SCM columns.
- [x] Update JPA entities/repositories to match the migrated schema; avoid editing V1–V7.
- [x] Extend database integration coverage with one representative V7-to-V8 fixture plus focused uniqueness/ownership assertions; rely on the full existing verify suite for empty migration coverage.
- [x] Update schema-count smoke assertions from 16 to 19 only after the migration test proves the count.
- Rollback point: before V8 is applied outside disposable tests. Production reversal after this step requires the pre-deploy backup.

### 3. Add account display name and bounded directory search

- [x] Add `displayName` to account entity/view/principal/session responses; require it during registration and allow the current user to update it through a profile endpoint.
- [x] Extend `UserDirectory` with bounded candidate-search and enabled-account lookup methods; keep `UserAccountRepository` private to `auth`.
- [x] Cover the changed account/session behavior and directory boundary through focused service/API tests plus the full validation suite; no input-combination matrix was added.

### 4. Replace single roles with role sets

- [x] Add role collection mapping and load deterministic sets on memberships.
- [x] Change `ProjectAccessService` to intersection-based capability checks.
- [x] Implement candidate search, all-or-nothing batch add (max 50), non-Leader role editing, and explicit Leader transfer. Do not add member removal.
- [x] Migrate every direct `getRole()`/`myRole` branch in project, requirement, knowledge, SCM, review, controllers, DTOs, and tests to set semantics.
- [x] Add focused integration tests for role union, batch rollback, Leader preservation/transfer, and non-enumeration; no combinatorial role/concurrency matrix was created.
- Review gate: repository-wide search finds no production single-role field or equality-based authorization.

### 5. Implement user-owned verified identities

- [x] Add SCM identity entity/repository/service/controller and owner-only list/update/revoke operations.
- [x] Add focused Provider verification with GitHub/GitLab current-user calls that reuse outbound URL validation and instance normalization, without a general Provider framework.
- [x] Enforce verified identity uniqueness and legacy quarantine; refresh username snapshots only from Provider responses.
- [x] Add secret-safe request objects/error mapping and focused diagnostics tests; deployment logs, responses and persistence shape contain no one-time token.
- [x] Test the two Provider paths and representative identity/binding behavior without enumerating equivalent combinations.

### 6. Implement project binding and PR authorization

- [x] Add binding entity/repository state transitions, compatible-option endpoint, display-safe summaries, and repository access verification.
- [x] Implement default auto-activation, strict pending approval, approve/reject, revoke, replacement while old binding remains active, identity-revoke propagation, and actor/time history.
- [x] Add the Leader-managed repository approval-policy setting.
- [x] Resolve/remap `pull_request.author_user_id` on sync and binding transitions without changing immutable author snapshots.
- [x] Replace “my PR” authorization with Developer role plus active provider/instance/external-id match.
- [x] Test one successful default flow and one strict approval flow while reusing existing constraint/authorization coverage; equivalent invalid combinations were not enumerated.

### 7. Implement account and project-member UX

- [x] Update registration/session types and every `myRole` consumer to `myRoles` with reusable capability helpers.
- [x] Add the non-primary `/account` route and account-menu link; implement display-name/password and multiple identity cards with cleared one-time-token inputs.
- [x] Rebuild project members as search + multi-select batch preview, common/per-row role checkboxes, separate Leader transfer, role chips, and SCM binding status.
- [x] Let only the signed-in member select/verify their identity; let Leaders approve/reject strict bindings but never edit identity facts.
- [x] Preserve responsive, keyboard, focus, loading/error/empty states and Chinese user-facing copy under the existing design contract.
- [x] Update the representative frontend journey/API assertions without adding a role/provider/status matrix.

### 8. Documentation and structural cleanup

- [x] Update `README.md`, `docs/v2/README.md`, `PRD.md`, `ARCHITECTURE.md`, `IMPLEMENTATION-PLAN.md`, `DECISIONS.md`, and affected backend/frontend specs with measured table/route/test counts.
- [x] Remove obsolete single-role/single-identity DTOs, methods, tests, CSS, and Leader-entered SCM copy after replacements are proven.
- [x] Do not edit or rerun formal evaluation freeze, corpus, holdout ledger, raw outputs, or reference results.
- [x] Re-run boundary searches for top-level packages, dependencies, routes/navigation, old fields, secret names, and dead compatibility code.

## Validation commands

Run targeted tests while implementing, then the complete gate:

```bash
cd /root/ForgePilot/backend
./mvnw -B -ntp verify

cd /root/ForgePilot/frontend
npm run lint
npm run typecheck
npm run test -- --run
npm run build

cd /root/ForgePilot
scripts/phase1-compose-smoke.sh
git diff --check
```

Additional required checks:

- [x] zero skipped backend/frontend tests and no formal-evaluation command executed;
- [x] empty-database Compose boot and V7-to-V8 upgrade integration fixture both pass;
- [x] log/database/response sentinel scan contains no one-time token;
- [x] ArchUnit/package-boundary tests remain green with exactly 8 top-level backend packages;
- [x] exactly 19 business tables and 8 Flyway migrations after V8;
- [x] six primary navigation entries remain; route/test counts are measured and documented;
- [ ] real browser flow: register with display name -> Leader searches/batch adds -> multi-role authorization -> member verifies two identities -> member selects compatible identity -> default activation and strict approval -> PR author mapping and replacement history.

## High-risk files and review points

- `backend/src/main/resources/db/migration/V8__member_roles_and_scm_identities.sql`: irreversible data conversion; independently inspect copy queries and constraint timing.
- `backend/src/main/java/com/forgepilot/project/ProjectAccessService.java` and all former `getRole()` branches: any missed equality check creates silent authorization drift.
- Provider HTTP clients and token-bearing DTOs: review log/error paths for secret leakage.
- Binding activation/remapping services: review locking order, one-active invariant, strict replacement behavior, and immutable PR snapshots.
- `frontend/src/features/project/ProjectMembersPage.vue`: keep API authorization authoritative despite conditional controls.

## Planned commit groups (approval still required before committing)

1. `docs: decide member roles and verified SCM identities`
2. `feat(backend): add member roles and account directory`
3. `feat(scm): verify identities and project bindings`
4. `feat(frontend): add account identity and batch member flows`
5. `test(docs): validate and document the completed identity flow`

Before staging or creating these commits, present the actual diff/status and ask the user to approve the grouping as required by repository rules. Pushing is a separate explicit action.
