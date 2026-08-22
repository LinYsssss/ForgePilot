# Official frontend visual rebuild

## Goal

Rebuild the tracked `frontend/` into a polished, high-density review console
informed by the visual effect of the untracked `ForgePilot-Frontend/`
reference, while keeping ForgePilot's implemented APIs, permissions, route
surface, and honest missing-capability states authoritative.

## Product requirements

- Adopt the reference's useful visual qualities: deep layered surfaces,
  restrained glass treatment, cyan/blue emphasis, compact metadata, clear
  status zones, and a review-detail workspace optimized for evidence and human
  decisions.
- Keep all content backed by the current application state and APIs. Do not
  introduce mock telemetry, quality scores, generated engine logs, fake
  repository state, fake knowledge documents, or controls for endpoints that
  do not exist.
- Preserve the current local account/session flow, project/member management,
  GitHub and GitLab repository writes, requirement lifecycle and revisions,
  review activity, Review execution/Decision separation, Finding lifecycle,
  coverage manifest, context snapshot, and role-dependent actions.
- Preserve exactly three top-level navigation entries and exactly seven
  approved product paths. Login remains an authentication route, not a fourth
  product destination.
- Preserve project scope in route queries and all current empty, loading,
  error, disabled, and stale states.
- Keep Requirement status, derived review activity, Review execution status,
  Review Decision, Finding human status, Finding continuity, and unrecorded AI
  confidence visually and semantically separate.
- Remain usable at 1440, 768, and 390 CSS pixels with no page-wide horizontal
  overflow. Wide tables, paths, evidence, and snapshots may scroll only inside
  bounded regions.
- Preserve semantic landmarks, heading order, labels, keyboard focus, skip
  navigation, text-backed statuses, and `prefers-reduced-motion` behavior.
- Add no UI framework, state library, request client, animation runtime, chart
  package, or fourth navigation surface.
- Keep `ForgePilot-Frontend/` untracked and unchanged.

## Acceptance criteria

- [x] AC1: The official shell, login, Projects, Members, Settings,
      Requirements, Requirement detail, Reviews, Review detail, and Finding
      surfaces share one coherent reference-informed visual system.
- [x] AC2: `TOP_LEVEL_NAVIGATION` still has exactly three entries and
      `PRODUCT_ROUTE_PATHS` still has exactly seven approved paths.
- [x] AC3: Existing real request paths, request bodies, session behavior,
      project query scope, and role-dependent action visibility remain covered
      by the frontend tests.
- [x] AC4: GitHub/GitLab provider selection and write-only credential behavior
      remain intact; the UI never claims it can query an existing SCM binding.
- [x] AC5: The UI does not display any invented metric, AI score, confidence,
      diff, log, knowledge document, repository state, or unavailable action.
- [x] AC6: Requirement status and review activity remain separate, and the
      four Finding marks remain four separate labelled containers.
- [x] AC7: Loading, empty, error, disabled, current/stale, missing coverage,
      missing AC verdicts, and missing context snapshot states are explicit.
- [ ] AC8: At 1440, 768, and 390 CSS pixels, operational reading order remains
      intact, page-wide horizontal overflow is absent, and dense content uses
      bounded local scrolling or wrapping. Static/responsive rules passed;
      rendered browser inspection is unavailable in this environment.
- [ ] AC9: Keyboard focus, skip link, native controls, form labels, heading
      hierarchy, status text, and reduced-motion behavior satisfy the existing
      accessibility contract. Automated semantics passed; rendered keyboard/
      contrast inspection is unavailable in this environment.
- [x] AC10: `npm ci`, lint, typecheck, all frontend tests, and production build
      pass; route/menu and forbidden-dependency audits remain green.
- [x] AC11: Compose still serves the rebuilt frontend and all three services
      become healthy in the existing deployment topology.
- [x] AC12: `ForgePilot-Frontend/` remains untracked and is not included in any
      staged or committed path.

## Out of scope

- New backend endpoints or business workflows.
- Project knowledge upload, implementation guidance, project-wide Review
  listing, live engine telemetry, charts, theme persistence, cursor particles,
  ambient JavaScript animation, or synthetic demo data.
- A new product route, global store, API client, or UI framework.
