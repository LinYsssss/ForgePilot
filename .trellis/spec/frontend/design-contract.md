# ForgePilot frontend design contract

## Selected direction

The official application uses **Precision Review Console / 精密审查台** as its
information and interaction direction. On 2026-08-22 its visual expression was
rebuilt from the user-provided `ForgePilot-Frontend/` study: deep layered
surfaces, restrained glass panels, cyan/blue emphasis, compact metadata, and a
denser evidence workspace. D017 later added the approved product routes, and
D018 places them in one centered top application bar without creating a second
business-state or navigation runtime.

### Lightness scheme

The selected scheme is now a single **dark** console theme. `tokens.css` uses
`color-scheme: dark` and is the authoritative source for its canvas, surface,
text, accent, semantic, and glow values. This intentional change supersedes the
Phase 1 light scheme after the user requested the official frontend adopt the
reference study's visual effect.

There is no theme toggle, persisted preference, or second token runtime. A
future light/dual scheme would be another contract decision rather than a local
component change.

## Information architecture

- Top-level navigation is Workspace, Projects, Requirements, Project Knowledge,
  Repository Integration, and Reviews. Desktop uses a top application bar with
  the lockup on the left, six links centered, and account actions on the right.
  At `64rem` it becomes two rows with a horizontally scrollable navigation.
- `/account` is an authenticated contextual page reached from the account menu,
  not a seventh top-level entry. It owns display-name editing, password change,
  and the current user's labelled SCM identities. The account menu itself
  carries no forms: it shows the account summary and links to `/account`.
- Member management identifies people by display name, username, and platform
  ID; LEADER selects existing accounts, may batch-add them, and edits role
  sets. The directory is a compact filterable table, not one card per person;
  role editing and Leader transfer live in a per-row disclosure. Choosing the
  member's own SCM identity is a single-instance panel, not a per-row form,
  while a LEADER may approve or reject a pending binding without choosing it
  for them.
- `Workspace` always has a project context: entering it without a project
  query adopts the first listed project via `router.replace`. The context lives
  only in the URL query, so a manual switch is never overridden and no client
  storage is introduced.
- A surface displays one visible Logo: the signed-in Shell uses the lockup,
  Login uses the app icon, and the app icon remains the favicon.
- Workspace is a read-only composition of real project APIs. It may summarize
  real records and link to workflows, but never invent telemetry or automate work.
- Contextual AI is deliberately prominent as Requirement Quality, structured
  Knowledge-enhanced Guidance, and the single Review Engine. Knowledge surfaces
  show real chunk/profile/index metadata and semantic-recall labels, never raw
  vectors or synthetic scores.
- Requirement detail keeps structured Revision/AC and uploaded Requirement
  documents as two complementary sections. Members may read/download `.txt` and
  `.md`, LEADER alone uploads, and structured content exports to Markdown in the
  browser. V1 renders uploaded Markdown as wrapped source text rather than HTML.
- Review screens follow `context/index → selected evidence → human decision zone`.
- Finding lifecycle, AI confidence, Requirement status, Review Decision, and
  review activity remain separate labels and containers; never merge them into
  one risk badge or composite score.
- Approved route views expose only implemented, role-authorized workflows and
  never manufacture data or actions for a missing endpoint.

## Tokens and typography

`frontend/src/styles/tokens.css` is the sole source for raw theme colors, font
families, and reusable spacing, radius, shadow, and motion values. Components
use semantic custom properties rather than local hex values or arbitrary
reusable spacing. `base.css` may keep one-off structural dimensions, fluid
ratios, mathematical constants, and the approved media-query boundary because
those are layout mechanics rather than reusable component tokens. The console
uses deep cool-neutral surfaces, compact translucent bordered panels, system
sans interface text, and `--fp-font-mono` for paths, line locators, ids,
revisions, hashes, and code. Decorative motion remains subordinate to content
but is intentionally vivid in normal-motion mode. The approved vocabulary
includes cursor-responsive particles, drifting grid and scanline layers,
floating neon orbs, radar and laser sweeps, pulse glow, holographic border flow,
shimmer, and route entrance choreography. These effects are presentation-only:
they never synthesize telemetry, state, records, or controls, and they preserve
the reduced-motion and lifecycle rules in `motion.md`.

The approved responsive boundaries are `64rem` for collapsing dense desktop
grids and `42rem` for the mobile stack. Do not introduce a third breakpoint
without updating this contract and checking all route surfaces at the new
boundary.

Stable evidence locators keep file, line, AC, and source visible while moving
through a finding. The layout is dense and operational, not KPI-first.

## Component and drift rules

- Use semantic `header`, `nav`, `main`, visible skip link, native links/buttons,
  and keyboard-visible focus.
- Keep DOM order aligned with operational reading order; code/diff may scroll
  locally, but the page must not acquire horizontal overflow.
- Status is never color-only; pair semantic color with visible text or shape.
- New colors, spacing, radius, shadow, breakpoint, or motion values require a
  named token and an intentional contract update.
- At 1440, 768, and 390 CSS px, check long titles/paths, empty/error/disabled/
  focus states, reduced motion, and console/network errors.
- No top-level entries beyond the six approved by D017; no general Assistant,
  Agent, Patch, Metrics, AI Logs, or repository-browser page.
