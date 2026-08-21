# Quality Guidelines

Frontend quality is enforced by a small, reproducible command set and by the
B Precision Review Console design contract. Phase 1 checks the shell and its
high-risk boundaries; it does not add low-value tests for framework behavior or
placeholder getters.

## Forbidden patterns

- Business workflows, fake records, authentication UI, review conclusions, or
  extra top-level navigation in Phase 1.
- `axios`, `pinia`, Tailwind, a UI component suite, charting libraries, or a
  second request/state runtime without an approved design change.
- Raw color literals outside `src/styles/tokens.css`, arbitrary visual values,
  page-wide horizontal overflow, or styles that bypass semantic tokens.
- Implicit retries, fabricated response envelopes, swallowed HTTP errors, or
  logging credentials/tokens.
- `any`, unchecked casts in views, inaccessible clickable containers, and
  color-only status communication.
- Ambient/per-frame animation or motion that ignores
  `prefers-reduced-motion`.

## Required patterns

- Use semantic landmarks, a visible keyboard focus indicator, and the global
  skip link; preserve the three approved top-level entries and seven approved
  product paths.
- Use `<script setup lang="ts">`, strict TypeScript, typed props/events, and
  `requestJson<T>` for JSON I/O.
- Use token-backed styles and keep DOM order aligned with operational reading
  order. Pair status color with text or another non-color cue.
- Keep empty, error, disabled, and focus states explicit. Do not imply that a
  foundation placeholder is a completed product feature.
- Keep changes bounded to the relevant frontend/module and update the design
  contract when a new token or interaction rule is genuinely required.

## Validation commands

Run from `frontend/` in this order:

```bash
npm ci
npm run lint
npm run typecheck
npm run test -- --run
npm run build
```

`lint` runs `scripts/lint.mjs`, which checks whitespace, raw colors outside
`tokens.css`, and forbidden dependencies. `typecheck` runs `vue-tsc`; the
production build repeats that check before `vite build`. The focused tests
cover route/shell semantics, the same-origin HTTP boundary, and reduced motion.
Do not default to an unrelated full-repository test run.

## Design drift checklist

For every visual or component change, manually inspect the selected direction
at 1440, 768, and 390 CSS pixels and record the result when the change is
substantial:

- [ ] B console hierarchy still reads as `context/index → evidence → human
      decision`; no KPI-first or composite-risk replacement.
- [ ] Long titles, file paths, line locators, and diffs remain readable without
      page overflow.
- [ ] Empty, error, disabled, focus, hover, and keyboard-only states are
      visible and semantically labelled.
- [ ] Reduced-motion mode removes non-essential movement while preserving
      content and focus order.
- [ ] Statuses have text/shape in addition to color; contrast and focus remain
      usable.
- [ ] No new reusable color, spacing, radius, shadow, breakpoint, or motion
      value was added without a named token.
- [ ] Browser console/network checks show no new errors or accidental retries.
- [ ] No fourth top-level menu, business data, or Phase 2 capability slipped
      into the shell.

## Code review checklist

Reviewers should inspect the actual diff, run the five commands above, verify
route and dependency boundaries, and confirm that any new API type preserves
the HTTP status/body error contract. A change is not complete when only a
summary says it passed; the command output and affected files must be
available as evidence.
