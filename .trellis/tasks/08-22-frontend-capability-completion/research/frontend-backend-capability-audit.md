# Frontend/backend capability audit

Date: 2026-08-22

## Question

Which current backend capabilities belong in the approved human-facing Vue
application, which are already connected, which are missing or misplaced, and
which must remain outside the UI because no public product endpoint exists?

## Authoritative boundaries

- `docs/v2/PRD.md` defines the three human roles and their allowed actions.
- `docs/v2/ARCHITECTURE.md` limits the UI to Projects, Requirements, and Reviews
  and seven product paths.
- Backend Controllers are authoritative for the currently callable HTTP surface.
- `frontend/` is the tracked production application. `ForgePilot-Frontend/` is an
  untracked reference study and cannot be treated as a second implementation.

## Current baseline

From `frontend/` on 2026-08-22:

```text
npm run lint              PASS
npm run typecheck         PASS
npm run test -- --run     8 files / 25 tests PASS
npm run build             PASS (70 modules)
```

The worktree had no tracked frontend modifications before this task. The only
pre-existing untracked directory was `ForgePilot-Frontend/`.

## Controller-to-UI matrix

| Backend capability | Current frontend | Required disposition |
|---|---|---|
| Register/login/logout/me | Implemented in Login/session/AppShell | Preserve |
| Change password | No UI or request helper | Add to existing signed-in shell; no new route |
| Project list/create/read | Implemented | Preserve and refine layout/states |
| Member list/add/update | Implemented | Preserve LEADER-only edit surface and SCM identity fields |
| Requirement list/create/read/edit | Implemented | Preserve; add index filters/activity |
| Requirement revisions/status/assignee | Implemented | Preserve; keep state rules and immutable history explicit |
| Requirement Quality | Implemented under Project Settings | Move to Requirement detail; it belongs to one revision |
| Requirement Guidance | Backend endpoint exists; no frontend helper/UI | Add one-shot detail panel with role rules |
| Requirement review activity | API helpers exist; only Review page consumes project map | Add separate status/activity display on Requirement list/detail |
| Register/update SCM repository | Implemented | Preserve GitHub/GitLab behavior and write-only secrets |
| Read SCM repository | No backend endpoint | Do not invent persisted/reloadable state |
| Read Pull Request | Implemented only after manual PR id or Review detail | Keep for selected Review/PR context |
| Correct PR association | Implemented in Review detail for LEADER | Preserve actual backend authorization and audit reason |
| Project-wide Review list | Backend `GET /api/projects/{p}/reviews` exists; frontend falsely says absent | Make this the primary `/reviews` index |
| PR Review history | Implemented after manual PR id | Make it the selected-PR drilldown from project rows |
| Request/retry Review | Implemented after manual PR id | Preserve; expose after selecting a row/PR with role/ownership checks |
| Review detail | Implemented | Replace raw-context-first diagnostics with structured evidence workspace |
| Review Decision | Implemented | Preserve one-shot gate and comment |
| Finding lifecycle | Implemented, but transitions always send empty comment | Add optional comment and keep role matrix |
| Finding events | Implemented, actor shown as numeric id | Resolve known project members to usernames |
| Project Knowledge upload/status | Internal service only; no Controller | Honest unavailable state; no fake upload button |
| GitHub/GitLab webhook ingestion | Public machine endpoint, not a human action | Show setup URL/instructions only; no “run webhook” control |
| Review reconciliation/fencing/executor | Internal runtime | No UI action |
| AI gateway/call log/evaluation/deploy | Internal/runtime/research | No top-level or operational UI |

## Confirmed stale frontend assumptions

The archived visual rebuild task recorded that Requirement Guidance and the
project-wide Review list were absent from the tracked backend. Current source
contradicts that record:

- `RequirementController.generateGuidance` exposes
  `POST /api/projects/{projectId}/requirements/{requirementId}/guidance`.
- `ReviewController.list` exposes `GET /api/projects/{projectId}/reviews`.
- `ReviewDecisionService.listForProject` returns newest-first `ProjectReviewRow`
  values including PR id/number, head, Requirement id, execution status,
  Decision, currentness, and creation time.

The production frontend still embeds the obsolete sentence “the server has no
project-wide Review list endpoint” and lacks both request helpers. This is the
highest-value parity defect.

## Review context evidence available to the frontend

`ReviewDecisionService.detail` returns an immutable context union assembled from
the Review's stored input snapshot and completed summary:

```text
requirement: id, revisionId, title, background, description (or null)
acceptanceCriteria[]: id, acKey, text
pullRequest: provider, instance, repository, number, baseSha, headSha,
             inputFingerprint, title
changedFiles[]: path, changeType, patch (nullable)
knowledgeEvidence[]: source/chunk/document identity, excerpt, score/hash fields
truncation: coverage manifest or null
```

The current frontend types this as `unknown` and renders `JSON.stringify` as the
primary view. Backend integration tests prove real patches, ACs, knowledge
excerpts, and truncation are present. The frontend can therefore render a real
diff/evidence workspace without a backend change.

One limitation is authoritative: `FindingView` has `acKey`, path, and line, but
no knowledge `sourceId`/`chunkId`. AC and code linkage can be deterministic;
Finding-to-knowledge linkage cannot. The frontend must show the Review's recalled
knowledge set without claiming which excerpt belongs to which Finding.

## Layout findings

- `/reviews` is organized around a rare recovery input (internal PR row id)
  before the actual Review index, even though the server now returns the index.
- Requirement Quality is under Project Settings, far from the Requirement and
  revision it evaluates.
- Requirement list/detail omit the already implemented derived activity read.
- Review detail correctly separates execution status, Decision, currentness,
  Finding status, continuity, and confidence, but pushes usable immutable evidence
  behind a raw JSON dump.
- Finding audit accepts comments in the backend but the UI always sends an empty
  comment, and renders actor ids rather than available usernames.
- The current dark theme and normal/reduced-motion contracts are well specified
  and tested; the rationalization should reuse them, not import the reference
  study's Tailwind/lucide runtime or replace the current style system.

## Recommended product mapping

1. AppShell: compact account control containing password change and logout.
2. Projects: keep project cards and member/settings child routes.
3. Project Settings: SCM configuration plus honest Knowledge boundary only.
4. Requirements index: project context, filters, creation, rows with separate
   Requirement status and review activity.
5. Requirement detail: contract/current activity, role actions, Quality,
   one-shot Guidance, revision editing/history.
6. Reviews index: project-wide rows first, filters, selected PR summary/history,
   request/retry; manual PR-id lookup demoted to a recovery disclosure.
7. Review detail: identity/gate, AC matrix, coverage, Findings, structured
   Requirement/knowledge/diff evidence, Decision zone, collapsed raw diagnostics.

## Validation implications

- Extend the journey mock with project-wide Reviews, Guidance, Requirement
  activity, Quality in its new owner, and password change.
- Add pure unit tests for context narrowing and unified-diff line mapping.
- Preserve route, HTTP/CSRF, session, motion, SCM provider, and role tests.
- Run the standard five frontend commands.
- Use a real browser at 1440/768/390 if an executable/container is available;
  otherwise record visual evidence as unverified rather than inferring it from
  jsdom.
