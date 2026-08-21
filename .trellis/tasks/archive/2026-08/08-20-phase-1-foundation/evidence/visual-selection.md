# Visual direction selection evidence

- Date: 2026-08-20
- Selected: **B — Precision Review Console / 精密审查台**
- Source: explicit user selection after the three interactive comparison
  artifacts were created and verified.
- Scope: visual language only; no route, menu, business state, or workflow was
  added by this decision.
- Artifacts: `artifacts/visual-directions/index.html`, `app.js`, `styles.css`,
  and `verification.md`.

Production tokens and frontend specs now follow B. The comparison artifacts stay
research-only and are not imported into production components.

## Lightness-scheme divergence (recorded 2026-08-21)

What landed in `frontend/src/styles/tokens.css` on 2026-08-20 adopts direction
B's information structure and its teal/mint hue family, but uses a **light**
lightness scheme:

| Role | Production token (light) | B fixture as shown at the gate (dark) |
|------|--------------------------|----------------------------------------|
| Canvas | `#f1f5f7` | `#0f151b` |
| Panel/surface | `#ffffff` | `#141d26` |
| Body text | `#17242e` | `#d9e3e8` |
| Accent | `#176d70` | `#5ec4a2` |

The fixture values are those in `artifacts/visual-directions/styles.css` under
`.fixture.console`, which is what the user compared before selecting B.

This lightness difference was not recorded when the tokens landed on
2026-08-20. It was identified on 2026-08-21; on that date the user confirmed
keeping the light scheme, so `tokens.css` is unchanged and remains
`color-scheme: light`. No statement is made here about whether the light
scheme was reviewed at the original selection gate — the record only shows that
it was not documented then and was confirmed afterwards.
