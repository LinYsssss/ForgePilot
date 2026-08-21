# Phase 1 visual direction comparison

Open `index.html` in a browser (or serve this directory with
`python3 -m http.server`) to compare the three proposed ForgePilot V2 visual
languages. The artifact is a research fixture, not production UI and contains
no working product actions.

Controls deliberately stay visual-only:

- switch between Direction A, B, and C;
- preview wide, tablet, and phone transformations;
- toggle reduced motion;
- focus the evidence locator and reveal a deliberately long diff sample.

The same neutral review-detail fixture is used in every direction. The DOM
composition, density, typography roles, and interaction memory point differ by
direction; palette changes alone are not the comparison.

## Candidates

### A — Evidence Ledger / 证据案卷

- Editorial reading flow with a context rail, evidence paper, and human
  decision rail.
- Medium density; neutral sans body with restrained serif headings.
- Memory point: a traceable annotation that connects AC, knowledge, and code.
- Avoid: scenic ancient motifs, scroll/ink metaphors, decorative seals, or
  treating any Legacy visual theme as selected.

### B — Precision Review Console / 精密审查台

- Dense operational console with a finding index, stable evidence split, and
  bottom decision zone.
- Highest density; system sans plus monospace code.
- Memory point: a persistent evidence locator (file, line, AC, source).
- Avoid: generic admin dashboard, KPI-first cards, neon HUD, or a merged risk
  score that conflates confidence, finding state, and human decision.

### C — Causal Trace Workspace / 因果链工作台

- Three source panes (requirement, project knowledge, diff) feed a central
  causal conclusion.
- Medium-high density; legible sans plus monospace evidence.
- Memory point: “why this finding exists” remains visible without an Agent graph.
- Avoid: node graphs, canvas panning, animated pipelines, or exposing internal
  orchestration concepts.

The main session must receive an explicit user choice before production tokens
or long-lived frontend specs are changed.
