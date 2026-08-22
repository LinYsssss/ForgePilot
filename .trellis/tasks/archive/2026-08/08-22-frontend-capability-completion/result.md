# Frontend capability completion and UX rationalization — result

## Outcome

Completed the approved tracked `frontend/` scope without backend, database,
dependency, top-level route, or motion-system expansion. The existing untracked
`ForgePilot-Frontend/` reference directory was not edited.

The production UI now exposes every approved human-facing backend workflow:
password change, Requirement activity/Quality/one-shot Guidance, project-wide
Review discovery, selected-PR history/request, structured immutable evidence,
Finding comments/events, and final Decision. Capabilities without public read or
write endpoints remain explicitly unavailable.

## Acceptance evidence

| AC | Result | Evidence |
|---|---|---|
| AC1 | PASS | Account disclosure posts `/api/auth/password`, validates confirmation, clears secrets after success/close, and preserves the current session. Journey test asserts payload and success state. |
| AC2 | PASS | Requirement list/detail load Review activity separately and render `.requirement-status` and `.review-activity` in different labelled cells. |
| AC3 | PASS | Quality moved from Project Settings to Requirement detail, remains LEADER-only, and shows revision/rules/AI output without changing status. |
| AC4 | PASS | One-shot Guidance is visible only to LEADER or the assigned DEVELOPER and identifies the revision. It has no conversation or streaming UI. |
| AC5 | PASS | `/reviews?project=3` calls `GET /api/projects/3/reviews`, renders the server's newest-first rows, and provides text/status/Decision/currentness filters. |
| AC6 | PASS | Project Review and PR links open `/reviews/:id` and the existing `pullRequest` query drilldown. |
| AC7 | PASS | Review request/retry visibility derives from LEADER/REVIEWER or mapped DEVELOPER ownership; focused API test asserts the POST contract. |
| AC8 | PASS | `parseReviewContext` validates Requirement, AC, PR, knowledge, files, patches, and truncation before rendering. Finding selection focuses AC/file/line without inventing knowledge linkage. |
| AC9 | PASS | Multi-hunk unit tests verify old/new mapping and deletion fallback. Chromium inspection found one selected line and no page overflow at all three widths. |
| AC10 | PASS | Finding moves include optional comments; successful moves clear the comment; events resolve known actors to usernames; `SUPPRESSED` rows are a separate disclosure. |
| AC11 | PASS | GitHub/GitLab SCM write workflows and webhook guidance remain; credentials are write-only; missing SCM read and Knowledge HTTP endpoints are explained honestly. |
| AC12 | PASS | Existing project/member and Requirement journey assertions remain green after the layout changes. |
| AC13 | PASS | Route tests retain exactly three top-level entries and seven product paths. Navigation now preserves current project context; session/CSRF handling remains centralized. |
| AC14 | PASS | Particle/grid/scanline/orb/radar/laser/holographic/shimmer/route selectors were preserved. Existing normal/reduced-motion tests pass; Chromium checked both motion preferences. |
| AC15 | PASS | Final `npm ci`, lint, strict typecheck, 11 files / 32 tests, and production build all passed. |
| AC16 | PASS | Headless Chromium inspected 1440×900, 768×900, and 390×844 in normal and reduced-motion modes. Every run reported `documentWidth === viewport`, zero console/page errors, one selected AC, and one selected Diff line. Screenshots were visually inspected for hierarchy, focus, local Diff scrolling, and responsive stacks. |
| AC17 | PASS | Static Controller audit covered Auth, Project, Member, Requirement, Review Activity, SCM, Review, and Finding mappings. Webhook ingestion and service-only/internal executor, reconciliation, fencing, AI-log, evaluation, and Knowledge ingestion remain out of direct UI scope. |

## Verification log

Run from `frontend/` after implementation:

```text
npm ci                         PASS (206 packages; one inherited glob deprecation warning)
npm run lint                   PASS
npm run typecheck              PASS
npm run test -- --run          PASS — 11 files, 32 tests
npm run build                  PASS — 76 modules transformed
git diff --check               PASS
task.py validate               PASS
```

Browser evidence used Chromium from
`mcr.microsoft.com/playwright:v1.55.0-noble` against the local Vite server with
deterministic API fixtures. Six screenshots were generated under `/tmp` for the
three widths and two motion preferences; no screenshot or temporary script is
part of the repository.

## Contract and spec audit

- Added no runtime dependency, request runtime, state store, UI framework,
  icon set, raw component color, or breakpoint.
- Kept `contextSnapshot` unknown at the network boundary and narrowed it once in
  the Review feature.
- Added monotonic load tokens so old project/detail responses cannot overwrite a
  newer route context.
- Updated the frontend code-spec with the structured-snapshot validation contract
  and corrected the existing CSRF request-boundary description.
- Updated `frontend/MANUAL-ACCEPTANCE.md` to the actual account, Requirement,
  project Review index, structured evidence, and three-role workflows.

## Known limitations (by backend contract)

- There is no public Project Knowledge upload/status Controller.
- SCM repository configuration has no GET endpoint; only the safe response from
  the current write can be displayed.
- There is no project-wide PR list; project-wide Review rows cover normal
  discovery, while internal PR id lookup remains a recovery disclosure.
- The Review response has no Finding-to-knowledge-chunk edge, so the UI presents
  the recalled evidence set without claiming a direct citation.

## Delivery state

No commit or push was performed. The next workflow gate is to present the commit
grouping to the user and obtain confirmation before creating commits.
