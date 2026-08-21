# ForgePilot frontend design contract

## Selected direction

Phase 1 selected **B — Precision Review Console / 精密审查台** on 2026-08-20.
This is a visual and interaction contract only; it adds no route, business
state, or second navigation surface.

### Lightness scheme

What shipped adopts direction B's information structure and its teal/mint hue
family, but with a **light** lightness scheme: canvas `#f1f5f7`, panel
`#ffffff`, body text `#17242e`, accent `#176d70`. The B comparison fixture the
user saw at the selection gate
(`.trellis/tasks/08-20-phase-1-foundation/artifacts/visual-directions/styles.css`,
`.fixture.console`) is dark: canvas `#0f151b`, panel `#141d26`, body text
`#d9e3e8`, accent `#5ec4a2`.

This difference was not recorded when the tokens landed on 2026-08-20. On
2026-08-21 the user confirmed keeping the light scheme, so `tokens.css` stays
`color-scheme: light` and the light values above are the contract. Any move to
a dark or dual scheme is a new contract decision, not a drift fix.

## Information architecture

- Top-level navigation remains Projects, Requirements, and Reviews only.
- Review screens follow `context/index → selected evidence → human decision zone`.
- Finding lifecycle, AI confidence, Requirement status, Review Decision, and
  review activity remain separate labels and containers; never merge them into
  one risk badge or composite score.
- Phase 1 route views are foundation placeholders and contain no product action.

## Tokens and typography

`frontend/src/styles/tokens.css` is the sole source for raw theme colors, font
families, and reusable spacing, radius, shadow, and motion values. Components
use semantic custom properties rather than local hex values or arbitrary
reusable spacing. `base.css` may keep one-off structural dimensions, fluid
ratios, mathematical constants, and the approved media-query boundary because
those are layout mechanics rather than reusable component tokens. The console
uses cool neutral surfaces, compact bordered panels, system sans interface
text, and `--fp-font-mono` for paths, line locators, and code.

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
- No fourth top-level menu or Workbench/Knowledge/Agent/Patch/Metrics page.
