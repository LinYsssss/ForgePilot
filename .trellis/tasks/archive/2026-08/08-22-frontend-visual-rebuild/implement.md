# Official frontend visual rebuild — implementation plan

## Gate 1: baseline and boundaries

- [x] Record clean baseline frontend lint, typecheck, 20 tests, and production
      build on 2026-08-22 before any official frontend source change.
- [x] Record the exact three navigation entries, seven product paths, package
      dependencies, and current untracked reference directory.
- [x] Update the frontend design contract for the intentional dark,
      reference-informed direction before changing tokens.

## Slice 1: visual foundations and shell

- [x] Rebuild semantic tokens and global primitives in `tokens.css` and
      `base.css`, with no raw values outside the token file.
- [x] Restyle `AppShell.vue` while preserving skip link, landmarks, navigation
      count, real session display, and logout behavior.
- [x] Rebuild the login surface around the real login/register workflow.
- [x] Run lint, typecheck, session/routes/motion tests, and production build.

## Slice 2: Projects, Members, and Settings

- [x] Recompose Projects into responsive real-data cards and a clear create
      surface without fabricated metrics.
- [x] Recompose Members around leader-only forms and project-role/SCM identity
      status.
- [x] Recompose Settings without changing GitHub/GitLab fields, write-only
      credentials, honest missing knowledge endpoint, or quality semantics.
- [x] Run lint, typecheck, SCM/session/journey tests, and build.

## Slice 3: Requirements

- [x] Recompose Requirements selector, creation form, and list with separate
      Requirement status presentation and all real empty/error states.
- [x] Recompose Requirement detail around metadata, current revision, actions,
      editing, and immutable history without inventing guidance or PR data.
- [x] Keep acceptance criterion ids, stable keys, request bodies, and test hooks.
- [x] Run lint, typecheck, Requirement/journey tests, and build.

## Slice 4: Reviews and Findings

- [x] Recompose Reviews around the real project selector, PR-id lookup,
      execution/Decision table, trigger action, and Requirement activity.
- [x] Recompose Review detail in context/evidence/decision reading order while
      preserving every Decision blocker, stale state, coverage distinction,
      AC verdict distinction, Finding action, and context snapshot.
- [x] Rebuild Finding presentation with the four required marks visibly
      separate and evidence/event regions locally scrollable.
- [x] Run lint, typecheck, journey/routes tests, full tests, and build.

## Gate 2: full verification

- [x] Run `npm ci`, lint, typecheck, `npm run test -- --run`, and build.
- [x] Audit exact route/menu counts, forbidden dependencies, raw colors,
      untracked reference status, and `git diff --check`.
- [ ] Inspect 1440, 768, and 390 layouts when a browser surface is available;
      check keyboard order, focus, empty/error/disabled/current/stale states,
      reduced motion, long paths, evidence, snapshots, and page overflow.
- [x] Run Compose rebuild/cold smoke and verify all three services healthy.
- [x] Record commands, results, deviations, manual-check availability,
      rollback, and remaining risks in `result.md`.

## Rollback points

- After Slice 1: restore shell and shared theme without touching route views.
- After Slice 2/3/4: revert only the affected feature templates and scoped CSS;
  API modules remain unchanged throughout.
- If the dark contrast or responsive contract fails, do not patch with local
  raw values; revise semantic tokens and re-run the full visual audit.
