# Frontend capability completion and UX rationalization — design

## 1. Scope and governing boundary

This is a tracked `frontend/` change. It consumes existing HTTP contracts and
does not add a backend endpoint, table, business state, authorization rule,
top-level route, runtime dependency, or second request/state runtime.

The role matrix in `docs/v2/PRD.md`, the seven-route information architecture in
`docs/v2/ARCHITECTURE.md`, current backend Controllers, and the visual/motion
contracts under `.trellis/spec/frontend/` are authoritative. Archived task
claims are evidence only and must yield to current source when they disagree.

The untracked `ForgePilot-Frontend/` study remains untouched. Its visual ideas
are already represented by the tracked tokens and motion system; importing its
Tailwind, lucide, mock-data, or JavaScript runtime would create a second frontend
architecture.

## 2. Information architecture

The route set stays unchanged:

```text
/projects                       project index and creation
/projects/:id/members           member roles and SCM identity
/projects/:id/settings          SCM connection and honest Knowledge boundary
/requirements                   requirement index, filters, activity, creation
/requirements/:id               contract, activity, AI tools, edit/history
/reviews                        project Review index and selected-PR drilldown
/reviews/:id                    immutable evidence and human decision workspace
```

Every page follows the existing console rule:

```text
context / index  →  selected operational evidence  →  authorized human action
```

Rare diagnostics and recovery tools appear after the primary workflow, normally
inside native `<details>` disclosures. No workflow is moved to a fourth menu.

## 3. API and type changes

### 3.1 Auth

Add `changePassword(currentPassword, newPassword): Promise<void>` to the current
session boundary. A compact signed-in account disclosure in `AppShell` owns the
form. It has current/new/confirmation fields, clears secrets after success or
close, reports failure through `apiErrorMessage`, and does not alter the current
account ref because the backend keeps the current session alive.

### 3.2 Requirement AI tools

Add the exact `ImplementationGuidance` response:

```ts
interface ImplementationGuidance {
  requirementId: number;
  revisionId: number;
  revisionSeq: number;
  guidance: string;
}
```

`generateGuidance` performs one POST and stores only the current in-memory
answer. There is no conversation, cache, history, polling, or optimistic state.
Quality remains its current structured response and moves from Settings to the
Requirement detail owner.

### 3.3 Review activity ownership

The Review backend owns derived activity. Move/define the full activity value
and labels at the review boundary so Requirement screens can consume the Review
read facade without a circular `review/api → requirement/status → review` shape.
The Requirement's persistent status remains in `requirement/status.ts`.

Both list and detail load activity separately from Requirement data, as the
backend architecture requires. A missing activity map entry is rendered as
“not returned”, not silently converted to `NO_PR`.

### 3.4 Project-wide Review list

Add the backend's real row contract and request:

```ts
interface ProjectReviewRow {
  id: number;
  pullRequestId: number;
  pullRequestNumber: number;
  headSha: string;
  requirementId: number | null;
  status: ReviewStatus;
  decision: ReviewDecision;
  isCurrent: boolean;
  createdAt: string;
}

listProjectReviews(projectId): Promise<ProjectReviewRow[]>
```

The backend already orders rows newest first; the UI preserves this while using
a deterministic local tie-break only if necessary. Local filters never change
server identity or paging semantics.

### 3.5 Review context narrowing

`ReviewDetail.contextSnapshot` stays `unknown` at the network boundary. A pure
`parseReviewContext(value): ReviewContextSnapshot | null` helper validates all
objects/arrays/primitives before a view consumes them. Malformed or incomplete
context yields an explicit unavailable state plus the optional raw diagnostic,
never a coerced valid object.

The validated shape includes:

- nullable Requirement revision identity and prose;
- ordered AC ids, stable keys, and text;
- immutable PR provider/repository/number/base/head/fingerprint/title;
- changed files with nullable patch;
- recalled knowledge source/document/chunk ids, excerpt, and score;
- nullable truncation/coverage details.

This uses hand-written guards because adding a schema library for one boundary
would violate the zero-new-runtime-dependency contract.

### 3.6 Unified diff parsing

A pure `parseUnifiedDiff(patch)` helper converts a provider patch into display
rows without mutating its content:

```ts
type DiffLineKind = "meta" | "hunk" | "context" | "addition" | "deletion";
interface DiffLine {
  key: string;
  kind: DiffLineKind;
  oldLine: number | null;
  newLine: number | null;
  text: string;
}
```

It recognizes `@@ -old,count +new,count @@`, advances old/new counters according
to the first prefix character, keeps `+++`/`---` outside hunk bodies as metadata,
and preserves “no newline” markers. Finding line matching prefers `newLine` and
falls back to `oldLine` for deletion evidence. Malformed hunk headers degrade to
metadata rather than inventing line numbers.

## 4. Page composition

### 4.1 App shell

Keep brand, three navigation links, account identity, logout, particles, route
transition, and skip link. The account identity becomes an accessible disclosure
containing password change and logout. It remains compact on desktop and stacks
under the same `42rem` breakpoint on mobile.

### 4.2 Projects and members

Preserve the working endpoint set. Refine card/form hierarchy and success/error
feedback without changing behavior. Member edit forms continue to reload the
whole list after a LEADER transfer because multiple rows change atomically.

### 4.3 Project settings

Keep SCM register/update, provider defaults, write-only credential inputs, safe
response summary, and webhook path guidance. Remove Requirement Quality from this
page. Keep an explicit Knowledge capability boundary: internal ingestion exists,
but no HTTP Controller means no upload/status controls can work.

### 4.4 Requirements index

Load projects once and, for the selected project, load Requirements and the
project activity map together. Provide local text, status, and activity filters.
Each row shows Requirement status and Review activity in separate `<dt>/<dd>`
cells; mixed/per-PR counts can expand without fusing those concepts.

Keep creation on the same route but place it after the index context in a clear
“create Requirement” disclosure/panel so long forms do not dominate read-only
users. Only LEADER sees it.

### 4.5 Requirement detail

Load project, members, Requirement, revisions, and per-Requirement activity with
stale-request protection. Organize into:

1. contract identity/status/activity/current revision;
2. role-authorized state/assignee controls;
3. AI assistance with separate Quality and Guidance panels;
4. editor/new revision publisher;
5. immutable history.

Quality and Guidance each show the revision id/sequence they describe and retain
their own pending/error/result state. A Requirement mutation clears displayed AI
answers whose revision no longer matches.

### 4.6 Reviews index

For a selected project, load projects, Requirements, activity, and the project
Review rows. Primary content is a filterable table/list with Review id, PR number,
head, Requirement, execution status, Decision, currentness, and time.

Selecting/opening a row can preserve `pullRequest=<id>` in the existing query.
That query drives a selected-PR panel loading authoritative PR metadata and full
Review history. The request/retry button appears for:

- LEADER or REVIEWER; or
- DEVELOPER when the fetched PR's mapped `authorUserId` equals the signed-in
  account id.

The backend remains authoritative and may still reject stale ownership. A manual
positive PR row-id input remains in a recovery `<details>` for the rare case with
no Review row, and its copy no longer says the project endpoint is absent.

### 4.7 Review detail

The operational order is:

1. immutable/current identity and stale/Decision-gate warnings;
2. AC verdicts and explicit coverage/truncation;
3. active Findings and separate collapsed suppressed group;
4. evidence workspace: Requirement/AC, recalled Knowledge set, changed-file
   selector and diff viewer;
5. human Decision zone;
6. raw context diagnostic disclosure and Review history.

Selecting a Finding stores a local `selectedFindingId`. The evidence workspace
focuses the matching `acKey`, selects the matching file path, and highlights its
line if present. This is deterministic for AC/code evidence. Knowledge excerpts
are shown as “recalled evidence for this Review”, never as a fabricated direct
Finding citation.

Each Finding owns an optional comment input. A move emits target, action, and
comment; success reloads the Review and audit history, then clears the comment.
Audit actor ids are resolved against the already loaded project member list with
an honest numeric fallback for actors no longer in the project.

## 5. State, races, and error behavior

- Keep Vue `ref`/`computed`, URL query state, and explicit `requestJson`; add no
  global store or query cache.
- Watch-based project/detail loads use a monotonically increasing request token
  so a slower response from the previous route cannot overwrite the current page.
- Independent panels own independent pending/error state; one AI failure does not
  erase the Requirement or the other panel's answer.
- Writes disable only their relevant action group and show a success result or
  refreshed server state.
- A reload never pretends one-shot Guidance, Quality output, or SCM write response
  is persisted when the corresponding read contract does not return it.
- The centralized 401 handler, same-origin credentials, CSRF cookie/header, and
  `HttpError` body are unchanged.

## 6. Styling and motion

Reuse `tokens.css`, `base.css`, and existing scoped styles. New reusable visual
values require named tokens; layout-only ratios/dimensions may remain structural.

The dark canvas, particle field, moving grid/scanlines, neon orbs, radar/laser,
holographic borders, shimmer, pulse, hover lift, and route choreography remain
enabled under normal motion. Changes may tune containment/z-index/contrast so
evidence stays legible, but may not remove the effects or replace the theme.

Dense tables and diffs scroll inside bounded `.table-scroll`/evidence containers.
At `64rem` multi-column workspaces collapse; at `42rem` forms/actions stack.
`prefers-reduced-motion` continues to disable continuous/non-essential movement
without changing content or focus order.

## 7. Compatibility, rollout, and rollback

- Existing route helpers and `project`/`pullRequest` query keys remain valid.
- API additions are additive frontend helpers over existing backend contracts.
- No persisted frontend migration exists.
- The change can roll back as one frontend/task/spec group; backend and database
  remain untouched.
- The user-provided untracked reference directory is neither edited nor committed.

## 8. Verification design

Automated coverage:

- request helper and CSRF behavior for password, Guidance, Quality, activity,
  project Reviews, Review request, Finding comments/events;
- three-role journey through the real App/router with updated fake server;
- separate Requirement status/activity containers;
- project-wide Review index/filter/query drilldown;
- runtime context narrowing and malformed response behavior;
- unified diff multi-hunk line mapping and highlight selection;
- role-specific controls and Finding transition/comment payloads;
- route count, semantic structure, session expiry, SCM provider, cyber particle,
  normal motion, and reduced motion regressions.

Gates run from `frontend/` in the mandated order: `npm ci`, lint, typecheck,
tests, build. A final static Controller-to-UI audit proves endpoint coverage.

Real-browser evidence should inspect 1440/768/390, normal/reduced motion, focus,
local overflow, network/console behavior, and representative LEADER/DEVELOPER/
REVIEWER actions. If the environment has no browser surface, that portion remains
explicitly unverified; jsdom is not accepted as a substitute.
