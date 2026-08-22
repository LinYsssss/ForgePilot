# Reference frontend audit

## Sources inspected

- `ForgePilot-Frontend/PRODUCT_BRIEF.md`
- `ForgePilot-Frontend/DESIGN.md`
- `ForgePilot-Frontend/DESIGN_DECISIONS.md`
- `ForgePilot-Frontend/UI_INVENTORY.md`
- All reference route views, the application/header shells, shared status tag,
  theme/auth composables, mock data, motion implementation, tokens, package
  manifest, and the two brand PNG assets.
- The tracked frontend route/session/API/label modules, every route view,
  `FindingCard.vue`, global styles, manual acceptance guide, lint policy, and
  test selectors/contracts.

## What should transfer

- Deep navy canvas with visibly stepped surface/elevated layers.
- Thin translucent borders and restrained cyan/blue edge emphasis.
- Compact mono metadata for ids, hashes, paths, providers, revisions, and
  evidence locators.
- A centered three-entry navigation surface with a strong active state.
- Page intros that establish context without KPI-first dashboards.
- Dense cards with a main narrative region and a separate status/action rail.
- Review detail organized as context/identity, evidence/coverage/findings, then
  a clearly bounded human decision zone.
- Local scrolling for tables, evidence, snapshots, and long paths.

## What must not transfer

- Fake metrics such as latency, token cost, webhook rate, vector chunk counts,
  decision percentages, or project statistics absent from the API.
- Fake Requirement quality scores. The real contract intentionally has no
  score or overall verdict.
- Mock repositories, members, requirements, Reviews, Findings, diffs, engine
  logs, knowledge chunks, or buttons backed by no endpoint.
- Claims about Agent runtimes, Milvus/Pinecone, automatic patching, neural
  gateways, or other products outside the ForgePilot V2 boundary.
- Canvas cursor particles, radar sweeps, scanlines, animated orbs, perpetual
  glows, emoji-heavy controls, role hot-switching, or ambient JavaScript
  animation.
- Tailwind, Lucide, theme state, a second auth abstraction, or the reference's
  mock-data runtime.

## Brand asset decision

The 198x178 app mark is visually usable, but importing a bitmap is unnecessary
for the rebuild and would create another asset/presentation dependency. The
244x110 lockup also contains the phrase `NEURAL CODE REVIEW ENGINE`, which is
not the product's authoritative positioning. Keep the official accessible
text brand and render a refined token-backed mark in CSS rather than copying
either untracked bitmap.

## Authoritative implementation gaps to keep honest

- There is no SCM repository read endpoint; settings may only display the
  current write response and must explain why reload starts blank.
- There is no project knowledge HTTP surface; show the current honest
  unavailable state and no upload button.
- Requirement quality check exists, but implementation guidance does not have
  a frontend/API path in the tracked application.
- Reviews are listed by PR record id because there is no project-wide Review
  list endpoint.
- Review detail has coverage, AC verdicts, Findings, events, and an opaque
  context snapshot; it does not receive raw patches or generated log streams.
- Finding confidence is not stored and must remain `未记录`.

## Constraint mapping

| Reference effect | Official adaptation |
|---|---|
| Cyber glass cards | Restrained dark surfaces with thin borders and one hover lift |
| Neon cyan/purple | Semantic cyan/blue accent plus sparing violet decoration |
| Animated grid/orbs | Static CSS background gradients; no continuous motion |
| KPI hero dashboards | Contextual page intro and real count only when derived locally |
| Mock diff inspector | Evidence excerpt, locator, coverage manifest, and context snapshot |
| Role hot switcher | Real authenticated account plus project-role labels |
| Theme toggle | Single deliberate dark contract; no persistence or shared state |

## Test-sensitive contracts

The rebuild must retain the existing ids/classes/data attributes used as
semantic test hooks, including login form ids, `.nav-link`, `.session-area`,
`.requirement-status`, `.review-row`, `.review-status`, `.review-decision`,
`.review-current`, `.review-activity`, `.finding`, the four Finding mark
classes, coverage classes, decision gate/stale classes, and action/decision
data attributes. Presentation classes may be added around them.
