# Repository cleanup implementation plan

## 1. Pre-change safety snapshot

- [x] Confirm the task is active only after plan approval.
- [x] Confirm the tracked tree contains only this task's planning artifacts.
- [x] Record secret-safe protected-asset fingerprints and `.env` stat metadata.

## 2. Repair tracked defects

- [x] Mechanically repair the 151 archived Markdown relative links without
      changing historical content.
- [x] Update the D018 result path in `docs/v2/IMPLEMENTATION-PLAN.md`.
- [x] Replace the stale Phase 1-only Maven project description with a current,
      scope-neutral description.
- [x] Add the Phase 8 `evidence` compatibility link without copying or editing
      immutable evaluation files; verify `task.py list` ignores the shim.
- [x] Run `git diff --check` and inspect the complete diff.

## 3. Validate documentation and delivered shape

- [x] Run the tracked Markdown local-target checker; require zero real missing
      targets.
- [x] Recount backend top-level packages, migrations/tables, frontend
      navigation/routes, and forbidden names.
- [x] Confirm no API/source/schema/dependency file changed except
      `backend/pom.xml` metadata.

## 4. Run build and test gates

- [x] Backend: use `eclipse-temurin:21-jdk` with the repository Maven wrapper,
      host networking, `/root/.m2`, and the Docker socket to run
      `./mvnw -B -ntp verify`.
- [x] Backend dependency analysis: run `dependency:analyze` in the same Java 21
      container and assess any report rather than deleting dependencies based
      on heuristics.
- [x] Frontend: run lint, strict typecheck with unused checks, 35-test gate,
      and production build.
- [x] Evaluation: run the four unit-test modules, scorer self-test, quick
      corpus validation, synthetic reference rescore/compare, and
      `--guard-no-holdout` only when compatible with the evidence machine.
- [x] Run both content-addressed freeze/correction verification commands and
      confirm the compatibility path resolves to the archived evidence.

## 5. Exact local cleanup

- [x] Delete only `/root/ForgePilot/ForgePilot-Frontend`,
      `/root/ForgePilot/backend/target`, `/root/ForgePilot/frontend/node_modules`,
      and `/root/ForgePilot/frontend/dist`.
- [x] Confirm those paths are absent and `.env` plus protected evaluation
      fingerprints/stat metadata are unchanged.

## 6. Finish review

- [x] Run final `git status`, `git diff --check`, link audit, and changed-file
      scope review.
- [x] Write `result.md` with actual counts, test results, retained gaps,
      protected-asset confirmation, rollback, and any deviations.
- [x] Perform the required spec-update judgment; update specs only if a new
      reusable convention was actually discovered.
- [x] Present commit grouping for user approval; do not commit or push without
      confirmation.
