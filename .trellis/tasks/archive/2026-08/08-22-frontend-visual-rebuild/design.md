# Official frontend visual rebuild — design

## Design intent

The result is a reference-informed dark evolution of the existing Precision
Review Console, not a port of the reference application. It uses the official
Vue/TypeScript application as the behavioral source and treats the reference
only as a visual study.

The hierarchy remains operational:

```text
application / project context
  -> route-specific records and evidence
    -> role-scoped action or human decision
```

## Foundations

### Theme

`frontend/src/styles/tokens.css` remains the only raw-value theme source. It
will define one dark color scheme with:

- a deep navy canvas;
- surface, elevated, and muted surface layers;
- subtle/default/strong borders;
- primary cyan-blue accent and a sparing violet secondary accent;
- WCAG-usable text, muted text, focus, and semantic state roles;
- spacing, radius, shadow, and motion tokens required by the rebuilt system.

The task updates `.trellis/spec/frontend/design-contract.md` because changing
the accepted lightness scheme is an intentional contract change requested by
the reference-frontend rebuild. No runtime theme switch or saved preference is
introduced.

### Motion

Only short opacity, border, background, and at most one-pixel lift transitions
are used for interactive feedback. There is no continuous animation, canvas,
particle field, cursor following, parallax, or route choreography. Existing
reduced-motion CSS disables the remaining transitions.

### Shared CSS primitives

Keep the dependency-free global system and extend its semantic primitives:

- app shell, brand mark, navigation pod, account/session chip;
- page intro, eyebrow, action row, panel, record, status badge;
- form controls, primary/quiet/danger actions, alert/empty/loading surfaces;
- metadata grid, bounded table, code/evidence block, section rail;
- responsive layout utilities used by route views.

No raw colors appear outside `tokens.css` and no inline style objects are used.

## Shell and authentication

`AppShell.vue` retains the skip link, `header`, labelled `nav`, `main`, exactly
three links, real session account, and logout call. The visual layout becomes a
compact three-zone header: brand, centered navigation pod, session action.

The login route uses the same shell but hides product navigation. Its content
becomes a two-column access panel: a concise product/causal-chain introduction
and the existing real login/register form. It contains no role presets,
telemetry, demo passwords, or invented runtime status.

## Route surfaces

### Projects and members

- Projects use a contextual intro, an honest create surface, and a responsive
  card grid. Each card exposes only name, project status, current user's role,
  creation time, and the four existing route actions.
- Members use a project context header, leader-only add form, and dense member
  cards. Role and SCM identity forms remain explicit and labelled.

### Project settings

Settings stays three honest sections: SCM, unavailable project knowledge, and
Requirement quality. SCM registration/update may visually split into cards,
but credential fields remain password inputs and are cleared after writes.
GitHub/GitLab defaults and webhook guidance remain driven by current code.

### Requirements

- The list keeps the project selector and leader-only creation form. Records
  gain a main area plus separate status rail; no quality score, PR count, or
  review activity is invented.
- Detail emphasizes stable identity, current revision content, separate
  status/assignment actions, editable content, and revision history. It does
  not synthesize associated PRs or guidance absent from the API.

### Reviews

- The list keeps project selection and PR-id retrieval because that is the
  real server surface. Review execution and Decision remain distinct columns.
  Requirement activity stays a separate downstream section.
- Detail presents PR association and Review identity first, then AC verdicts,
  coverage, Findings, and immutable snapshot. The final human Decision remains
  its own visible zone with all blockers. This keeps DOM reading order aligned
  with the product contract even when desktop CSS uses columns.
- Finding cards retain four separate marks, evidence, metadata, role-scoped
  actions, and event history. Suppressed Findings remain ordinary records with
  explicit continuity; no hidden or automatic semantic merge is introduced.

## State and data flow

No API, session, router, or state ownership changes are planned. Existing view
refs/computed values and `requestJson<T>` remain authoritative. Visual counts
may only be computed from arrays already loaded by the view and must be labelled
as such; no new polling, cache, retry, or optimistic update is added.

## Responsive and accessibility behavior

- Desktop: centered content with optional two-column sections where DOM order
  remains logical.
- Tablet: shell wraps; wide forms and metadata grids reduce columns; tables
  scroll within their panel.
- Mobile: shell stacks, navigation scrolls locally, cards and forms become one
  column, all controls remain at least the existing target height, and code/
  paths wrap or scroll locally.
- Focus is always visible. All decorative effects use pseudo-elements with
  `pointer-events: none` and are hidden from the accessibility tree by having
  no DOM semantics.

## Compatibility and rollback

Behavioral compatibility is protected by retaining route/API modules and
test-sensitive hooks. The work can be rolled back in two layers:

1. revert route-view markup and shared shell changes;
2. restore the previous `tokens.css`, `base.css`, and design contract.

No migration, backend, dependency, or persisted browser state is involved.
