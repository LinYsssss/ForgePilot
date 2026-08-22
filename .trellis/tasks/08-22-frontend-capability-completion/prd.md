# Frontend capability completion and UX rationalization

## Goal

Make the tracked Vue application a complete, coherent user surface for every
backend capability that belongs to ForgePilot's approved human workflows. A
signed-in LEADER, DEVELOPER, or REVIEWER should be able to discover and complete
their authorized part of the requirement-driven review flow without knowing
database record ids or falling back to direct API calls.

The work must preserve the existing Precision Review Console visual direction
and its full cyber-motion language. Layout and styling may be refined to improve
hierarchy, density, responsiveness, and task flow, but the dark console theme,
particles, scan/grid atmosphere, radar/laser effects, holographic borders,
shimmer, route choreography, and reduced-motion behavior remain product
requirements.

## Background and confirmed facts

- `frontend/` is the tracked production frontend. The untracked
  `ForgePilot-Frontend/` directory is a visual/reference study, not a second
  application to ship.
- The approved information architecture has exactly three top-level entries and
  seven product paths: Projects, Requirements, and Reviews plus their approved
  detail/settings paths. No new route is required for this work.
- The frontend currently passes lint, strict type checking, 25 tests, and the
  production build.
- The backend already exposes user-facing capabilities which the frontend does
  not expose or exposes in the wrong place:
  - account password change;
  - one-shot Requirement implementation guidance;
  - project-wide Review listing;
  - per-Requirement and project-wide derived review activity;
  - structured immutable Review context containing Requirement, AC, recalled
    knowledge excerpts, changed-file patches, and truncation information.
- Requirement Quality exists in the frontend, but is currently placed under
  Project Settings even though it operates on one Requirement revision.
- Project Knowledge has an internal service but no HTTP Controller. SCM
  repository configuration has write endpoints but no read endpoint. The
  frontend must describe these limits honestly and must not fabricate controls
  or persisted state.
- Webhook ingestion, execution fencing, reconciliation, provider clients, AI
  gateway internals, and evaluation tooling are not direct human UI workflows.

## Requirements

### R1 — User-facing backend capability coverage

Every current user-facing Controller action must have an appropriate frontend
entry, result state, and error path within the approved routes:

- Auth: register, login, logout, current-session bootstrap, and password change.
- Project: list/create/read projects and list/add/update members, including
  LEADER transfer and SCM identity editing.
- Requirement: list/create/read/edit, immutable revision history/publication,
  status transitions, assignment, Quality Check, one-shot Implementation
  Guidance, and derived review activity.
- SCM: register/update repository configuration, display the safe write result,
  show provider-specific webhook instructions, read a Pull Request, and correct
  its Requirement association where the backend authorizes the action.
- Review: list all Reviews in a project, inspect one PR's history, request/retry
  a Review, inspect one Review, and write a one-shot final Decision.
- Finding: display Findings, perform every role-authorized lifecycle transition,
  accept an optional audit comment, and read the audit history.

Capabilities with no public backend endpoint must remain explicitly unavailable
instead of being mocked. Credentials and webhook secrets must never be echoed.

### R2 — Requirement workflow rationalization

- Requirement list rows show persistent Requirement status and derived review
  activity as separate facts, including the six per-PR counts when useful.
- The list supports useful local search/status/activity filtering without adding
  server semantics or a new state runtime.
- Requirement detail fetches and displays its current derived review activity.
- Requirement Quality and one-shot Implementation Guidance live on Requirement
  detail, show the revision they describe, and never imply that AI changed the
  Requirement state.
- Guidance remains one-shot output with no chat input, history, streaming, or
  conversation model.
- Role visibility matches the backend: Quality is LEADER-only; Guidance is
  available to LEADER and only the assigned DEVELOPER.
- Project Settings no longer acts as a Requirement action page.

### R3 — Review index rationalization

- `/reviews` loads the backend's project-wide Review list and no longer claims
  that the endpoint is missing.
- A user can understand and open Reviews without first knowing an internal PR
  record id. Each row keeps execution status, final Decision, and current/stale
  validity separate.
- The index supports local filtering by meaningful Review fields and can narrow
  to a selected PR through the existing URL query.
- A selected PR exposes its authoritative snapshot and complete Review history,
  and offers request/retry only when the viewer's role/ownership can perform it.
- Manual PR-id lookup may remain only as a secondary recovery path for a PR that
  has no visible Review row; it must not be the primary information architecture.

### R4 — Review evidence workspace

- The immutable `contextSnapshot` is validated/narrowed from `unknown` and
  rendered as human-readable Requirement, AC, knowledge, PR, and changed-file
  evidence instead of only as a raw JSON dump.
- Changed-file patches are rendered in a bounded diff viewer with old/new line
  numbers, addition/deletion/context styling, local horizontal scrolling, and no
  page-level overflow.
- Selecting a Finding links to its `acKey` and file/line evidence and highlights
  the corresponding evidence when the backend provides enough identity.
- Recalled knowledge excerpts are displayed as the Review's evidence set. The UI
  must not invent a one-to-one Finding-to-chunk link because the response does not
  contain that relationship.
- Coverage/truncation and unreviewed files remain explicit. A missing manifest is
  not presented as full coverage.
- `SUPPRESSED` Findings are grouped separately and remain reopenable according to
  their real lifecycle rules. Finding status, continuity, AI confidence, and
  Review Decision stay separate; absent AI confidence remains “not recorded”.
- Raw JSON may remain as a collapsed diagnostic view, not the primary evidence UI.

### R5 — Permissions and honest interaction states

- Controls are hidden or disabled according to the loaded project role and
  resource ownership; backend authorization remains authoritative.
- Every network-owned section has explicit loading, empty, error, pending, and
  success feedback, with a usable retry path for failed reads where appropriate.
- Write controls prevent accidental duplicate submission and show the backend's
  error message without swallowing `401/403/404/409/422` distinctions.
- Existing session-loss handling, same-origin cookies, and CSRF injection remain
  centralized in the current request boundary.

### R6 — Layout and visual continuity

- Preserve exactly three top-level navigation entries and the seven approved
  product paths.
- Reorganize pages around `context/index → selected evidence → human decision`.
  Primary work appears before diagnostics and rare recovery tools.
- Preserve the current dark tokens and cyber effects. Styling changes should
  refine information hierarchy, density, spacing, tables, panels, and responsive
  stacking rather than replace the visual direction or reduce the effect set.
- No new theme runtime, UI framework, icon package, state library, or request
  library is introduced.
- At 1440, 768, and 390 CSS px, long titles, hashes, paths, forms, tables, and
  diffs remain usable without page-wide horizontal overflow.
- Keyboard focus, semantic landmarks/headings, native controls, visible status
  text, skip navigation, and `prefers-reduced-motion` behavior remain intact.

### R7 — Compatibility and test coverage

- Existing working flows remain compatible with current backend request/response
  contracts and bookmarked project/PR/review query URLs.
- Automated tests cover every newly connected endpoint, role-dependent action
  surface, project-wide Review loading/filtering, Requirement activity/guidance/
  quality, password change, context narrowing, diff-line mapping, and Finding
  evidence/audit interactions.
- The established frontend lint, typecheck, test, and build gates remain green.

## Acceptance Criteria

- [ ] AC1: A signed-in user can change their password from the existing shell;
      success keeps the current session usable and clears all password fields.
- [ ] AC2: Requirement list and detail show persistent status and current derived
      review activity in separate labelled containers, never a composite badge.
- [ ] AC3: A LEADER can run Quality Check from Requirement detail and see rule/AI
      output plus revision identity; Project Settings contains no Requirement
      quality action.
- [ ] AC4: A LEADER or the assigned DEVELOPER can generate one-shot guidance on
      Requirement detail and see its revision identity and prose; REVIEWER and an
      unassigned DEVELOPER see no enabled generation action.
- [ ] AC5: `/reviews?project=<id>` displays the project-wide backend Review list
      newest first, with filters and separate status/Decision/currentness columns,
      without requiring a PR id.
- [ ] AC6: Selecting a project Review can open its Review detail and its PR history;
      the existing `pullRequest` query remains linkable and reloadable.
- [ ] AC7: Review request/retry is offered to LEADER/REVIEWER and the owning
      DEVELOPER only; the response and refreshed Review state are visible.
- [ ] AC8: Review detail renders immutable Requirement, AC, knowledge, PR and diff
      context from the backend. A Finding can focus matching AC and code evidence,
      while unsupported knowledge linkage is not invented.
- [ ] AC9: Diff rendering distinguishes context/add/delete lines, preserves real
      line mapping across hunks, highlights a selected Finding line, scrolls
      locally, and does not create page-wide overflow.
- [ ] AC10: Findings expose all authorized transitions with optional comments;
      audit history is readable with known actors resolved to usernames, and
      suppressed Findings are a distinct collapsible group.
- [ ] AC11: SCM settings preserve GitHub/GitLab registration/update and webhook
      guidance, never echo credentials, and honestly explain absent repository
      read and Knowledge HTTP endpoints.
- [ ] AC12: Project/member and Requirement CRUD/revision/status/assignment flows
      continue to work with explicit role and async states after layout changes.
- [ ] AC13: The header still has exactly Projects, Requirements, and Reviews;
      approved routes, session bootstrap/expiry, CSRF, and error handling remain
      compatible.
- [ ] AC14: The dark Precision Review Console and all existing normal-motion cyber
      effects remain present; reduced-motion mode disables non-essential movement.
- [ ] AC15: Automated endpoint, role, evidence, diff, route, accessibility-structure,
      and motion tests pass together with lint, strict typecheck, and production
      build.
- [ ] AC16: Real-browser inspection at 1440/768/390 confirms usable hierarchy,
      local table/diff scrolling, visible focus/status, no page overflow, and no
      new console/network errors; any unavailable browser evidence is recorded as
      unverified rather than treated as passing.
- [ ] AC17: Static audit finds no current user-facing Controller endpoint missing
      from an appropriate UI flow, except capabilities explicitly documented as
      internal or lacking a public read/write endpoint.

## Out of scope

- New backend endpoints, database migrations, business states, or authorization
  changes.
- A Knowledge upload/status UI while no Knowledge Controller exists.
- UI controls for webhook ingestion, reconciliation, worker leases/fencing,
  provider HTTP clients, AI call logs, evaluation runners, or deployment
  operations.
- New top-level navigation, Workbench, Assistant/chat, Conversation, Agent, Patch,
  Metrics, repository browser, or standalone Knowledge page.
- Fabricated PRs, Reviews, knowledge chunks, confidence values, source links, or
  persisted SCM configuration.
- Replacing the selected visual direction, removing the approved cyber effects,
  or introducing a second theme.

## Risks and deferred items

- The Review response does not identify which knowledge chunk supports each
  Finding. The UI can show the immutable recalled evidence set but cannot claim a
  deterministic per-Finding knowledge link.
- There is no project-level Pull Request list endpoint. Project-wide Review rows
  remove the normal need for internal ids, while manual PR lookup remains a
  recovery path for the unusual case where no Review row exists.
- SCM repository state cannot be reloaded because there is no read endpoint.
  Only the safe response of the current register/update request can be rendered.
- Knowledge ingestion exists internally but is not a public HTTP workflow; this
  task records the boundary and does not create a nonfunctional upload control.
