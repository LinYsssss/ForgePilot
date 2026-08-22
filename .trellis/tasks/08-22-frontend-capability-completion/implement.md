# Frontend capability completion and UX rationalization — implementation plan

## Preconditions

- Read `prd.md`, `design.md`, and `research/frontend-backend-capability-audit.md`.
- Load the frontend and cross-layer Trellis specs through `trellis-before-dev`.
- Confirm the latest planning summary with the user, then run `task.py start`.
- Preserve the pre-existing untracked `ForgePilot-Frontend/` directory.

## Ordered checklist

### 1. Establish boundary types and pure helpers

- [x] Add password-change request support to the existing auth/session boundary.
- [x] Add Requirement Guidance response/type/request.
- [x] Move/full-define Requirement review activity types at the Review API owner.
- [x] Add `ProjectReviewRow` and `listProjectReviews` to the Review API.
- [x] Add strict Review context guards/narrowing in a focused review module.
- [x] Add pure unified-diff parsing and selected-line matching helpers.
- [x] Add focused unit tests for new requests, context guards, and multi-hunk diff
      mapping before view rewrites depend on them.

Rollback point: these are additive boundary/helper files and can be reverted
without touching pages.

### 2. Complete account, Requirement, and settings workflows

- [x] Add the accessible password-change disclosure/form to `AppShell` while
      preserving three navigation links, logout, skip link, and route motion.
- [x] Load/display project activity on Requirement list; add local text/status/
      activity filters and preserve separate status/activity markup.
- [x] Load/display one Requirement's activity on detail.
- [x] Move Requirement Quality UI/state from Project Settings to detail.
- [x] Add one-shot Guidance panel with exact LEADER/assigned-DEVELOPER visibility,
      revision identity, pending/error/success behavior, and no chat affordance.
- [x] Clear stale AI output after Requirement mutation/revision change.
- [x] Simplify Project Settings to SCM plus the honest Knowledge boundary.
- [x] Preserve Project/member CRUD behavior and refine async/success layout where
      the shared styling changes affect it.
- [x] Update journey/request tests for password, Quality ownership, Guidance,
      Requirement activity, and role surfaces.

Rollback point: account/Requirement/settings changes form one coherent slice;
Review pages still build independently against additive APIs.

### 3. Replace the obsolete Review index

- [x] Make project-wide Review loading the primary `/reviews` content.
- [x] Add local filters and stable newest-first rendering with separate execution,
      Decision, and currentness cells.
- [x] Preserve/open Review detail links and `pullRequest` query drilldown.
- [x] Load selected PR metadata/history and compute request/retry visibility from
      project role plus mapped PR ownership.
- [x] Retain manual PR id only in a labelled recovery disclosure; remove the false
      “endpoint absent” copy.
- [x] Keep project Requirement activity as a secondary overview without merging it
      into Review execution state.
- [x] Update journey tests for list, filtering, selection, history, retry, errors,
      empty project, and all three roles.

Rollback point: `ReviewsPage.vue`, Review API/types, and its focused tests can be
reverted as a slice.

### 4. Build the structured Review evidence workspace

- [x] Add small typed components for immutable context summary, AC/knowledge
      evidence, and changed-file diff rendering where component extraction makes
      responsibilities clearer than extending `ReviewDetailPage.vue`.
- [x] Parse/narrow `contextSnapshot`; show explicit malformed/missing states.
- [x] Render Requirement/AC, PR identity, recalled knowledge, changed files,
      patches, and truncation in operational order.
- [x] Add Finding selection → matching AC/file/line focus and accessible selected
      state; do not manufacture Finding-to-knowledge linkage.
- [x] Split normal and `SUPPRESSED` Findings, keeping suppressed entries auditable
      and reopenable.
- [x] Add optional per-Finding comments to transition payloads and resolve audit
      actors to usernames with an id fallback.
- [x] Keep Decision gate/currentness/execution/finding/continuity/confidence axes
      visibly independent and preserve raw JSON as a collapsed diagnostic.
- [x] Update journey/component tests and add malformed context/patch fixtures.

Rollback point: evidence components/helpers are isolated from backend and routing;
the prior raw diagnostic view remains recoverable through a local revert.

### 5. Rationalize layout without replacing the visual direction

- [x] Update token-backed global/scoped styles for the new account disclosure,
      filters, tables, AI panels, evidence tabs/selectors, diff lines, selected
      Finding/AC states, suppressed disclosure, and responsive stacks.
- [x] Preserve all particle/grid/scanline/orb/radar/laser/border/shimmer/route
      effects and their existing lifecycle cleanup.
- [x] Keep diff/table overflow local and check long paths/hashes/text.
- [x] Preserve focus-visible, landmarks, headings, labels, status text, skip link,
      and reduced-motion overrides.
- [x] Update the manual acceptance checklist to reflect real project Review list,
      Requirement-owned AI tools, account password change, and structured evidence.
- [x] Update frontend spec only if implementation establishes a reusable convention
      not already covered by the approved contract.

### 6. Verification and completion audit

- [x] Run `npm ci`.
- [x] Run `npm run lint`.
- [x] Run `npm run typecheck`.
- [x] Run `npm run test -- --run`.
- [x] Run `npm run build`.
- [x] Run `git diff --check` and inspect all frontend/task/spec diffs.
- [x] Compare every current Controller mapping against the final UI/API helpers;
      record internal/no-public-endpoint exclusions explicitly.
- [x] Verify exactly three top-level links, seven product paths, no forbidden
      dependency, no raw colors outside tokens, and no credential rendering.
- [x] Attempt real-browser inspection at 1440/768/390 for normal/reduced motion,
      focus, overflow, console/network, and representative three-role flows.
- [x] If a browser is unavailable, record AC16 as unverified/partial; never use
      jsdom output as visual proof.
- [x] Run full-scope `trellis-check`, then `trellis-update-spec` review.
- [x] Write `result.md` with requirement-by-requirement evidence and honest gaps.

## High-risk files

- `frontend/src/features/review/ReviewDetailPage.vue`: already dense; keep request
  orchestration in the page and evidence rendering in typed child components.
- `frontend/tests/journey.spec.ts`: stateful fake server spans three roles; extend
  contracts rather than weakening existing assertions.
- `frontend/src/styles/base.css`: large shared stylesheet; group additions by
  component and avoid altering ambient effect selectors unintentionally.
- `frontend/src/components/motion/*`: should not require business changes. Any edit
  needs particle lifecycle and reduced-motion regression coverage.

## Final review gate

Before `task.py start`, confirm that the user approves this exact scope: complete
all approved human-facing backend workflows, rationalize their placement and
layout, preserve the existing dark cyber style/effects, and make no backend or
route expansion.
