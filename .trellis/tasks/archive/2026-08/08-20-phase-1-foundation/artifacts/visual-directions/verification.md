# Visual artifact verification (2026-08-20, re-run 2026-08-21)

## Automated checks completed

- `node --check app.js` — passed.
- Static source inspection confirmed three different fixture roots and layout
  systems:
  - A: three-column editorial ledger (`context rail / paper / decision rail`);
  - B: console grid (`finding index / evidence / decision strip`);
  - C: four-source causal composition (`requirement / conclusion / knowledge / diff`).
- A JSDOM interaction probe loaded the real HTML and script, then verified:
  - all three direction switches render their own DOM composition;
  - wide, tablet, and phone viewport classes apply;
  - reduced-motion mode applies;
  - long-diff mode reveals local overflow content;
  - the evidence-locator control receives keyboard focus.
- A local `python3 -m http.server` probe returned HTTP 200 for `index.html`,
  `styles.css`, and `app.js`.

## Long-diff line-break defect and re-collected evidence (2026-08-21)

The 2026-08-20 probe only asserted that long-diff mode appends content; it never
asserted how many rendered lines the appended content produces. That blind spot
hid a real defect: `applyLongDiff` joined and prefixed the generated rows with
`"\\n"` (an escaped backslash plus `n`) instead of `"\n"`. Because `.diff` uses
`white-space: pre`, all 18 appended rows collapsed onto a single rendered line
containing literal `\n` characters.

`app.js` was corrected on 2026-08-21 (two occurrences, `"\\n"` → `"\n"`), and
the JSDOM probe was re-run with an added assertion on rendered line count. The
output below is evidence re-collected after that fix, not the original
2026-08-20 run.

```text
direction roots: {"ledger":"fixture ledger","console":"fixture console","causal":"fixture causal"}
viewport classes: ["preview-frame viewport-wide","preview-frame viewport-tablet","preview-frame viewport-phone"]
reduced-motion body class: reduced-motion
focused element: BUTTON.locator
long-diff toggle label: Hide long diff
frame long-diff class: true
base diff lines: 3
expanded diff lines: 21
expanded diff span count: 21
literal backslash-n present: false
assert expandedLines >= 18: true
first appended line: "+  supporting evidence line 01 · same fixture"
last appended line: "   supporting evidence line 18 · same fixture"
```

New assertion: the expanded long diff must render at least 18 lines. It now
renders 21 (3 base rows plus 18 appended rows) with no literal `\n` text. The
same probe run against a pre-fix copy of the artifact reported
`expanded diff lines: 3` and `literal backslash-n present: true`, which is the
recorded failing baseline.

## Browser evidence gap

No Chromium/Chrome executable is installed on the target host, and Phase 1
explicitly avoids adding Playwright solely for this comparison artifact.
Therefore no screenshot or real rendering-engine evidence is claimed yet. The
user can open the artifact before selecting a direction; any rendering defect
found there blocks the selection gate and must be fixed before production
tokens are frozen. The JSDOM probe asserts DOM text content and structure only;
it does not evaluate `white-space: pre` layout in a real rendering engine.

## Selection status

**Selected: B — Precision Review Console / 精密审查台 (2026-08-20).**

The selected contract is recorded in `.trellis/spec/frontend/design-contract.md`
and `.trellis/spec/frontend/motion.md`. The comparison artifact remains a
research fixture and is not imported into production source.
