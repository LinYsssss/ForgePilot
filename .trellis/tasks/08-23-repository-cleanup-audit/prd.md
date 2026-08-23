# Repository-wide cleanup and completion audit

## Goal

Remove repository and workspace clutter that is demonstrably obsolete or
regenerable, repair the documentation defects found by the repository-wide
audit, and replace the dated completion claims with fresh verification
evidence without changing ForgePilot's product behavior.

## Background

- The tracked working tree was clean before this task was created.
- `frontend/` is the production Vue application. The ignored, untracked
  `ForgePilot-Frontend/` directory is an older visual study last modified on
  2026-08-22; archived task evidence confirms that its useful visual language
  was already adapted into `frontend/`.
- Regenerable local outputs currently occupy about 195 MB:
  `backend/target/`, `frontend/node_modules/`, `frontend/dist/`, and the old
  study's `dist/`.
- The Markdown audit found 151 broken repository-relative links across 17
  archived Batch 1-3 task documents. Archiving added two directory levels but
  those historical links still use their pre-archive relative paths.
- `docs/v2/IMPLEMENTATION-PLAN.md` still points at the pre-archive D018 result
  path, and `backend/pom.xml` still describes the completed backend as the
  Phase 1 foundation.
- Execution-time verification exposed the same archive-path defect in the
  formal-evaluation defaults: the frozen config and two post-freeze wrappers
  intentionally retain the original pre-archive evidence path. Those files
  are themselves content-addressed immutable inputs and cannot be edited
  without invalidating the experiment.
- Strict frontend type checking with `noUnusedLocals` and
  `noUnusedParameters` passed. The source scan found no TODO/FIXME marker or
  production class that can be proven dead; Spring components and
  `package-info.java` files must not be removed merely because static textual
  reference counts are low.

## Requirements

1. Delete the obsolete ignored `ForgePilot-Frontend/` visual-study directory.
2. After validation, delete the regenerable local directories
   `backend/target/`, `frontend/node_modules/`, and `frontend/dist/` so the
   final workspace does not retain build/cache output.
3. Repair all 151 confirmed broken relative links in the 17 archived Batch
   1-3 Markdown documents by changing paths only. Historical claims,
   acceptance results, raw evidence, and timestamps must remain unchanged.
4. Correct the D018 evidence path in `docs/v2/IMPLEMENTATION-PLAN.md` and the
   stale backend project description in `backend/pom.xml`.
5. Restore the frozen formal-evaluation evidence path with a narrow,
   repository-relative compatibility link at the original `evidence/`
   location. The compatibility directory must contain no `task.json`, must not
   appear as an active Trellis task, and must point only to the existing
   archived Phase 8 evidence directory.
6. Do not delete production source, current README files, the seven V2
   authority documents, Trellis platform adapters/specs, task evidence, or
   evaluation fixtures merely because some material is duplicated across
   entry points or platforms; each has a current consumer or evidentiary role.
7. Preserve `.env` and every formal-evaluation immutable asset, including the
   private corpus, configuration freeze, holdout ledger, and raw outputs.
   Never invoke a provider or rerun the holdout.
8. Recalculate the delivered shape and completion status from repository
   facts and fresh gates. Accepted MVP gaps and manual browser acceptance
   items must remain explicitly incomplete rather than being reported as
   implementation failures or silently marked complete.

## Acceptance Criteria

- [x] `ForgePilot-Frontend/`, `backend/target/`, `frontend/node_modules/`, and
      `frontend/dist/` are absent at handoff; `.env` and immutable evaluation
      assets are unchanged.
- [x] The local-link audit reports zero missing real file targets in tracked
      Markdown; the literal template placeholder `(url)` is excluded.
- [x] `docs/v2/IMPLEMENTATION-PLAN.md` resolves D018 evidence to its archived
      result and `backend/pom.xml` no longer labels the whole backend as only
      the Phase 1 foundation.
- [x] Formal freeze/correction verification and all 18 evaluation unit tests
      resolve the immutable archived evidence through the compatibility link;
      the frozen config, wrappers, correction record, and evidence bytes stay
      unchanged.
- [x] No production API, class, route, table, dependency, migration, or UI
      behavior changes.
- [x] Backend verification passes in the documented Java 21 container path,
      including all Maven tests, ArchUnit, and PostgreSQL/pgvector tests.
- [x] Frontend `lint`, strict typecheck (including unused declarations), all
      tests, and production build pass before generated outputs are removed.
- [x] Evaluation unit tests, scorer self-test, corpus validation, reference
      score comparison, and no-holdout guard pass without a provider call.
- [x] Scope checks still report 8 backend top-level packages, 16 business
      tables across 7 migrations, 6 top-level frontend entries, 10 product
      routes, and no forbidden package/runtime.
- [x] Final completion assessment distinguishes automated completion from the
      two remaining manual browser acceptance items and the three explicitly
      accepted MVP limitations in `docs/v2/README.md`.

## Out of Scope

- New product features, schema changes, dependencies, routes, or refactors.
- Rewriting historical task conclusions or deleting archival evidence.
- Running Compose capacity tests or any formal model/holdout execution.
- Automatically committing or pushing changes.
