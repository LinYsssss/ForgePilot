# Phase 1 frontend and evaluation research

## Conclusion

Phase 1 should create two independent foundations, without implementing any business flow:

1. A minimal Vue 3 shell that proves routing, the request boundary, design tokens, basic components, accessibility, reduced-motion behavior, and build/type-check viability. All product routes may resolve to one static foundation placeholder; there must be no login, project, requirement, SCM, review, or finding behavior.
2. A versioned evaluation contract plus a Python-standard-library deterministic scorer adapted from Legacy. The scorer should recompute a 10–15 case development-only quick set from checked-in synthetic/reference predictions. It must not call a Review Engine and must not expose or run holdout in Phase 1.

The old RepoSage frontend must not be restored. Its Agent/Patch/Workspace/Metrics pages are explicitly `DROP`; its historical visual comparison, accessibility work, and drift tests are useful only as `REFERENCE`. Evaluation is different: the migration matrix explicitly permits selective `KEEP DATA` for the corpus and `KEEP TOOL / ADAPT` for the scorer.

## Evidence and status legend

- **Confirmed** means the current six authoritative V2 documents, current repository state, or the frozen Legacy baseline states the fact.
- **Recommendation** means the smallest implementation shape inferred from those facts.
- **Deferred** means Phase 1 planning or the user must choose it; it is not yet a repository decision.

### Current authority

| Evidence | Confirmed fact |
| --- | --- |
| `docs/v2/IMPLEMENTATION-PLAN.md` Phase 1 | Vue routing, request layer, tokens, basic components, three visual directions, selected visual/motion contract, evaluation contract/scorer skeleton, and a 10–15 case development quick set are in scope. No business UI or business entities are allowed. |
| `docs/v2/ARCHITECTURE.md` §6 | Top-level navigation is exactly Projects, Requirements, Reviews; the route set is `/projects`, `/projects/:id/members`, `/projects/:id/settings`, `/requirements`, `/requirements/:id`, `/reviews`, `/reviews/:id`. AI confidence, Finding status, Review Decision, Requirement status, and review activity must remain visually distinct. |
| `frontend/README.md` | The target is Vue 3 and the same three-item information architecture. |
| `evaluation/README.md` | Phase 1 establishes a versioned contract and deterministic scorer only. The paper compares three arms and reports Precision, Recall, false/miss rates, requirement-violation recall, AC verdict, structure failure, token, and latency. Holdout is not run before Phase 8. |
| `.trellis/spec/frontend/*.md` | The six existing frontend specs are templates marked `To be filled`; no current coding convention has been established. |
| Current filesystem | `frontend/` contains only `README.md`; `evaluation/` contains only `README.md` and `results/.gitkeep`. There is no package manifest, application source, visual artifact, corpus, or scorer in the current tree. |
| `docs/v2/LEGACY-MIGRATION-MATRIX.md` | Legacy frontend Agent/Patch/Workspace/Metrics pages and composables are `DROP`. `evaluation/cases` + `manifest.json` are `REWRITE/KEEP DATA`; `evaluation/tools/score.py` is `KEEP TOOL / ADAPT`. |

### Legacy evidence inspected after the migration-matrix gate

Frozen Legacy baseline: `96137dd3b43e14c5e8881c99688663afd979cf4e`.

| Evidence at the frozen commit | Reusable evidence, not current authority |
| --- | --- |
| `evaluation/manifest.json` | `schemaVersion=evaluation-manifest-v2`, 38 cases, fixed split of development 26 / holdout 12, with Requirement, AC, expected Finding, nonFinding, and AC truth data. |
| `evaluation/tools/score.py` | Python stdlib-only; `--selftest`; deterministic `d3-v1` case-sensitive path/category/line-overlap matching; greedy sorted 1:1 matching; AC verdict scoring; zero-denominator `n/a`; `notRun`; metadata checks. |
| `.trellis/tasks/archive/2026-08/08-12-frontend-guofeng-cyber-redesign/research/visual-directions.md` and `.../assets/visual-directions-comparison.png` | A prior three-direction comparison used genuinely different structures, not palette swaps. It compared a light editorial workspace, a dark observatory console, and a mechanical HUD. This demonstrates a useful comparison method, but RepoSage's selected “Ink Review Atelier” is not a ForgePilot V2 decision. |
| Same task's `research/qa-report.md` and `frontend/tests/ink.test.mjs` | Proven checks include 1440/768/390 plus breakpoint-boundary rendering, no page-level overflow, local Diff overflow, focus transfer/return, drawer `inert`/`aria-hidden`, 44px touch targets, reduced motion, token-only colors, and bounded ambient motion. Reuse the checks selectively; do not copy the visual runtime. |
| `frontend/package.json` | Legacy used Vue 3, Vite, Vue Router and Node 22, but plain JavaScript and Element Plus were RepoSage choices. They are evidence of a working toolchain, not binding V2 choices. |

## Frontend foundation

### Confirmed scope and non-scope

In scope:

- The seven confirmed routes and exactly three top-level navigation entries.
- One request boundary, one design-token source, a minimal app shell, and one reusable foundation placeholder.
- A visual-direction artifact outside production source, followed by a user choice and spec freeze.
- Type-check, build, minimal interaction/accessibility validation.

Out of scope:

- Login/session/CSRF behavior, project data, member management, Requirement data, knowledge upload, SCM data, Review/Finding data or actions.
- Real API calls beyond an optional infrastructure health probe.
- Pinia, a component framework, charting, animation libraries, a dark-mode system, localization, analytics, or a second theme.
- Restoring any Legacy business page, Agent/Patch concept, compatibility redirect, or legacy API envelope.

### Recommended minimal file boundary

TypeScript is recommended because Phase 1 requires a frontend type check and this is a greenfield boundary. It is not yet a confirmed product decision; the planning artifacts should record it explicitly.

```text
frontend/
├── package.json
├── package-lock.json
├── tsconfig.json
├── tsconfig.app.json
├── vite.config.ts
├── index.html
├── src/
│   ├── env.d.ts
│   ├── main.ts
│   ├── App.vue
│   ├── app/
│   │   ├── routes.ts              # the seven confirmed paths + root/not-found behavior
│   │   └── router.ts              # Vue Router construction only
│   ├── pages/
│   │   └── FoundationPlaceholderPage.vue
│   └── shared/
│       ├── api/
│       │   ├── client.ts          # generic requestJson<T>; no business endpoint
│       │   └── HttpError.ts       # status/code/message/traceId
│       ├── theme/
│       │   ├── tokens.css         # selected semantic tokens, the only raw-value source
│       │   └── base.css           # reset, typography, focus, reduced-motion baseline
│       └── ui/
│           └── AppShell.vue       # three-item nav + router outlet
└── tests/
    ├── routes.spec.ts             # exact route/nav contract
    └── foundation.spec.ts         # request error shape and reduced-motion/token guard
```

Recommendations:

- Production dependencies: only `vue` and `vue-router` unless the selected design proves another dependency is necessary.
- Development dependencies: Vite/Vue plugin, TypeScript, `vue-tsc`, and one small test runner. Do not add Pinia or Element Plus without a demonstrated Phase 1 need.
- The root route should redirect to `/projects`. All seven product routes may render the same static `FoundationPlaceholderPage`; route metadata provides the label. That proves the route contract without creating seven fake business pages.
- `AppShell` may display the three top-level entries, product name, current placeholder title, and a clear “Phase 1 foundation — feature not enabled” message. It must not contain fake tables, CRUD forms, login controls, metrics, or review decisions.
- `client.ts` should set the API base and `credentials: 'include'`, normalize network/HTTP errors into `HttpError`, and preserve `traceId` when present. Do not invent a success envelope, retries, auth refresh, CSRF logic, or response fallbacks before Phase 2/API contracts require them.
- CSS custom properties should be semantic (`--fp-surface`, `--fp-text-primary`, `--fp-focus`, `--fp-danger`, `--fp-diff-added`, etc.). Components consume semantic tokens only.

Deferred choices:

- Exact Node, Vite, TypeScript, and test-runner versions. Pin compatible versions in `package.json`, `.nvmrc` or equivalent, and the lockfile when implementation starts; do not inherit versions merely because Legacy used them.
- Whether the browser smoke uses Playwright. It is justified only if already available in CI or needed to verify the selected interactive artifact; otherwise type/unit/build plus a recorded manual browser pass is sufficient for this scaffold.
- Component library. The minimal recommendation is none in Phase 1.

## Three-direction visual comparison and visual contract

### Artifact boundary

The comparison should be a static, dependency-free, interactive research artifact, not a production Vue feature:

```text
.trellis/tasks/08-20-phase-1-foundation/research/visual-comparison/
├── index.html
├── styles.css
├── app.js
├── README.md
└── evidence/                    # selected screenshots/browser notes after review
```

It should render the same deterministic Review-detail sample in all directions so structure, density, hierarchy, and personality can be compared fairly. Static sample content may show Requirement + AC, selected project-knowledge evidence, Diff, one Finding, AI confidence, Finding lifecycle state, and human Review Decision, but it must be labelled a visual fixture and must not become production business code.

Required controls:

- Direction A/B/C switch.
- Desktop/tablet/mobile viewport preview or clearly separated responsive panels.
- Normal/reduced-motion switch; reduced mode must remain fully informative.
- At least one focus/keyboard path and one long-Diff/overflow example.
- No save, approve, request-changes, upload, or other fake working business action.

### Recommended directions

These are candidates for user selection, not a preselected answer.

| Direction | Structure and personality | Commercial fit and density | Typography and semantic color roles | Memory point | Forbidden mode |
| --- | --- | --- | --- | --- | --- |
| **A — Evidence Ledger / 证据案卷** | Editorial reading flow: slim project/requirement context rail, wide evidence paper, compact human-decision rail. Calm, trustworthy, long-reading oriented. | Strong fit for thesis demonstration and Reviewer evidence reading; medium density. | Neutral sans body + restrained serif only for short headings; warm surface, dark ink, one cool interactive color, red only for risk/terminal negative decisions. | A Finding reads like a traceable annotation connecting AC, knowledge and code evidence. | Scenic “ancient style”, realistic scrolls, ink over code, excessive seals, decorative motion, or treating the Legacy Ink theme as already approved. |
| **B — Precision Review Console / 精密审查台** | Dense engineering console: top context bar, filterable Finding grid, stable code/evidence split, bottom/side decision zone. Crisp and operational. | Strong fit for daily engineering use and high information throughput; highest density. | System sans + mono code; cool neutral surfaces; teal/blue for interaction, amber/red/green reserved for warning/failure/success. | A consistent evidence locator that keeps file, line, AC and knowledge source visible while navigating. | Generic admin dashboard, default component-library appearance, neon HUD, KPI-first layout, or merging confidence/status/decision into one “risk score”. |
| **C — Causal Trace Workspace / 因果链工作台** | Product-specific three-source composition: Requirement/AC, Project Knowledge, and Diff form stable source panes feeding a central Finding/evidence conclusion. Analytical and explanatory. | Best fit for communicating ForgePilot's unique causal chain; medium-high density and strongest differentiation. | Highly legible sans + mono; each source gets a subtle non-status identity marker, while true semantic statuses retain separate accessible colors and labels. | The “why this Finding exists” trace is visible without opening an internal Agent graph. | Node-graph interaction, canvas panning, flowchart-as-navigation, animated pipelines, or exposing internal orchestration/Agent concepts. |

The comparison must document for each direction: product fit, density, font roles, semantic color roles, one memorable element, responsive transformation, and explicit prohibited patterns. The user must select one direction before its values enter production tokens/specs.

### Spec outputs after selection

Confirmed R2 planning requires the selected result to become shared Trellis guidance:

- Add `.trellis/spec/frontend/design-contract.md`.
- Add `.trellis/spec/frontend/motion.md`.
- Replace the placeholders in `directory-structure.md`, `component-guidelines.md`, `hook-guidelines.md`, `state-management.md`, `quality-guidelines.md`, and `type-safety.md` with actual Phase 1 conventions.
- Update `.trellis/spec/frontend/index.md` to link the two new files and mark the existing files as filled.
- Put the design-drift checklist in `quality-guidelines.md`.
- Keep purely visual decisions in frontend specs, not `docs/v2/DECISIONS.md`, unless they alter a product/architecture boundary.

## Accessibility, reduced-motion, and drift checklist

### Accessibility contract

- Use semantic landmarks (`header`, `nav`, `main`) and a visible-on-focus skip link.
- Preserve a single logical heading hierarchy and DOM order that matches visual order.
- Every interactive element is reachable and operable by keyboard; focus is always visible and never trapped outside an intentional modal/drawer.
- Text contrast meets WCAG AA (4.5:1 normal text, 3:1 large text); focus indicators and meaningful non-text boundaries target 3:1.
- Status is never color-only: icon/shape plus visible text. AI confidence, Finding lifecycle status, Review Decision, Requirement status, and review activity retain separate labels and containers.
- Touch targets used on mobile are at least 44×44 CSS px.
- At 200% zoom and narrow width, content remains available. Code/Diff may scroll locally; the page itself must not acquire horizontal overflow.
- Long titles, long file paths, empty/error/loading/disabled/focus-visible states are checked before freezing a component pattern.
- Decorative content is `aria-hidden` and has no pointer events. Removing it must not change layout or information.

### Reduced-motion contract

- Motion cannot be the only way to reveal status, hierarchy, or success/failure.
- `@media (prefers-reduced-motion: reduce)` disables continuous animation, parallax, particle/ambient loops, large movement, and animated scrolling; essential one-shot feedback becomes immediate state change or a short opacity fade.
- If the selected direction uses JavaScript animation, one shared policy must stop it for reduced motion, coarse pointers, hidden documents, and unfocused windows. Do not update Vue reactive state every animation frame.
- Content surfaces, code, tables, forms, focus rings, and primary actions never drift, blur, scale continuously, or participate in ambient motion.
- Phase 1 should prefer CSS-only motion and may ship no ambient JavaScript at all. A manual “static mode” is optional and should be added only if the selected direction actually includes persistent ambience.

### Design-drift gate

- No new color, font family, type step, spacing step, radius, shadow, blur, z-index, breakpoint, or motion duration appears in a component before it is added to `design-contract.md`/`motion.md` and represented by a token.
- Raw color values are allowed only in `tokens.css`; Vue components and other CSS consume variables.
- No fourth top-level navigation item and no Workbench/Knowledge/Repository/Metrics/Agent/Patch/AI Logs top-level page.
- No component may merge AI confidence, Finding state, Review Decision, Requirement state, or review activity into one badge/score.
- The same deterministic fixture is captured at 1440, 768, and 390 widths; also capture any exact breakpoint boundary introduced by the selected design. These sizes are recommended from proven Legacy QA, not yet frozen V2 breakpoints.
- Capture normal and reduced-motion states, keyboard focus, local Diff overflow, empty/error/disabled states, and the absence of console/network errors.
- Review every visual diff against the selected artifact; a screenshot delta is not automatically a defect, but unexplained token/structure drift blocks acceptance.
- Do not add broad screenshot snapshots for every component. Keep one shell/representative-detail baseline and targeted behavioral checks for the high-risk contracts above.

## Evaluation foundation

### Confirmed reusable boundary

- The frozen Legacy corpus is 38 cases with an immutable 26 development / 12 holdout split.
- Phase 1 selects 10–15 cases only from development. It must not call a model/Review Engine or score holdout.
- The mature reusable scorer core is deterministic path/category/line matching, sorted greedy 1:1 assignment, AC verdict scoring, explicit zero denominators, `notRun`, and metadata consistency.
- Legacy runtime-envelope discovery, Agent/Patch fields, five-arm runtime flags, LangChain runtime metadata, expected Patch results, backend task IDs, and compatibility wrappers are not part of V2.

### Recommended file boundary

```text
evaluation/
├── README.md
├── manifest.json                         # adapted V2 corpus; fixed 38-case split
├── case-sets/
│   └── phase1-quick.json                 # versioned 10–15 development IDs only
├── contracts/
│   ├── manifest.schema.json
│   ├── run.schema.json
│   ├── score-report.schema.json
│   └── metrics.md
├── cases/                                # selectively imported data/fixtures
├── fixtures/
│   ├── phase1-reference-run.json         # synthetic normalized outputs, not model evidence
│   └── phase1-reference-score.json       # exact expected deterministic report
├── tools/
│   ├── score.py                          # stdlib-only adapted scorer
│   └── category-aliases.json
└── results/
    └── .gitkeep                          # generated results remain ignored
```

Recommendation: import/adapt the complete 38-case data corpus so the fixed split remains auditable, but make `phase1-quick.json` the only executable case set in Phase 1. Remove `expectedPatch` and Patch/Agent/runtime fields during the import. The scorer's corpus validator must reject missing fixtures and any quick-set ID whose split is not `development`.

Use a new V2 contract version such as `forgepilot-evaluation-manifest-v1`; do not retain `evaluation-manifest-v2` if field meaning has changed. Prefer current architecture vocabulary (`acKey`, `findingType`) while preserving the underlying truth data. Any mapping from Legacy categories to `REQUIREMENT` versus `CODE_QUALITY` must be explicit and reviewable, not inferred at score time.

### Recommended normalized run contract

One V2 input shape is enough; do not copy Legacy envelope extraction:

```jsonc
{
  "contractVersion": "forgepilot-evaluation-run-v1",
  "corpusVersion": "...",
  "caseSetVersion": "phase1-quick-v1",
  "arm": "DIFF_ONLY | DIFF_REQUIREMENT_AC | DIFF_REQUIREMENT_AC_KNOWLEDGE",
  "config": {
    "model": "...",
    "temperature": 0,
    "promptVersion": "..."
  },
  "cases": [
    {
      "caseId": "...",
      "status": "COMPLETED | FAILED | NOT_RUN",
      "failureKind": "STRUCTURE | PROVIDER | TIMEOUT | OTHER | null",
      "findings": [
        {
          "findingType": "REQUIREMENT | CODE_QUALITY",
          "category": "...",
          "filePath": "...",
          "lineStart": 1,
          "lineEnd": 1
        }
      ],
      "acVerdicts": [{ "acKey": "AC-0001", "verdict": "COVERED | NOT_FOUND | AT_RISK" }],
      "usage": { "inputTokens": 0, "outputTokens": 0, "latencyMs": 0 }
    }
  ]
}
```

For `phase1-reference-run.json`, mark the file and README clearly as synthetic scorer validation. It is not a model baseline and must not be cited as product quality evidence.

### Deterministic scoring contract

Retain and adapt the proven `d3-v1` core:

```text
hit(predicted, expected) =
  normalized path is equal (slash normalization and leading ./ removal only; case-sensitive)
  AND predicted category belongs to expected category + versioned aliases
  AND predicted/expected line ranges overlap
```

- Missing predicted line information does not match a line-anchored expected Finding.
- Sort expected and predicted Findings by path/range/category, then greedily assign 1:1. Each item participates in at most one match.
- A valid `COMPLETED` case with zero Findings is scored as zero Findings, not `notRun`.
- Missing case output is `NOT_RUN`. A present output with invalid structure is a `FAILED/STRUCTURE` attempt and contributes to structure-failure rate, not to `notRun`.
- A denominator of zero is reported as `null`/`n/a`, never silently changed to zero.
- Do not create a composite quality score.

Metric definitions to freeze in `contracts/metrics.md`:

| Metric | Definition |
| --- | --- |
| Precision | `TP / (TP + FP)` on matched Findings. |
| Recall | `TP / (TP + FN)` on expected Findings. |
| False-report rate / 误报率 | `FP / (TP + FP) = 1 - Precision`; call it `falseReportRate` to avoid confusing it with statistical `FP/(FP+TN)`. |
| Miss rate / 漏报率 | `FN / (TP + FN) = 1 - Recall`. |
| Requirement-violation recall | Recall restricted to truth explicitly marked `findingType=REQUIREMENT`; do not infer it from a display category during scoring. |
| AC verdict | Exact-hit rate plus one-vs-rest precision/recall for `COVERED`, `NOT_FOUND`, `AT_RISK`; missing prediction remains missing and is not guessed. |
| Structure-failure rate | Attempted cases with `failureKind=STRUCTURE` divided by attempted cases (`NOT_RUN` excluded and listed separately). |
| Token and latency | Totals and per-completed-case mean; absent usage is `n/a`, not zero. Later formal evaluation may add percentiles without changing the basic contract. |
| notRun | Count and explicit case IDs/reasons; never included in Finding-rate denominators. |

### Recommended Phase 1 quick set

Freeze a separate versioned list. The following 12-case recommendation stays within development, includes three clean/nonFinding-heavy cases, three knowledge-bearing business cases, two languages beyond Java, architecture/security categories, and enough positive/negative variety to exercise the scorer:

1. `java-sql-resource-leak`
2. `typescript-ambiguous-null`
3. `java-broken-build`
4. `biz-fee-rate-hardcoded`
5. `biz-currency-unchecked`
6. `biz-status-machine-bypass`
7. `eng-contract-drift-dual-encode`
8. `eng-transactional-self-invocation`
9. `fp-java-whitelist-order-by`
10. `fp-python-chore-gitignore-cleanup`
11. `miss-template-share-authz`
12. `sec-java-customer-search-sqli`

This exact list was adopted by the Phase 1 planning artifacts: it is the implementation target, not a license to substitute another case. No holdout case may enter it and no holdout result may be generated.

### Scorer self-test matrix

The adapted `--selftest` should cover only high-value deterministic behavior:

- Exact path/category/range match, path slash normalization, category alias, and disjoint ranges.
- Missing line does not match.
- Sorted greedy 1:1 behavior with duplicate predictions.
- A clean case with an unexpected Finding counts as false report.
- AC exact hit, wrong verdict, invalid verdict, missing verdict, and zero denominator.
- Present structural failure versus absent/not-run output.
- Metadata/corpus/case-set mismatch.
- Quick-set validation rejects duplicate, unknown, or holdout IDs.
- Reference-run recomputation byte-for-byte matches the checked-in reference score after excluding generated timestamp/output path fields.

## Recommended validation commands

These are proposed implementation commands; exact script names should be made real in `package.json`/`score.py` rather than left as documentation-only wishes.

### Frontend

```bash
cd frontend
npm ci
npm run typecheck
npm test
npm run build
```

Visual artifact/browser evidence:

```bash
python3 -m http.server 4178 --bind 127.0.0.1 --directory .trellis/tasks/08-20-phase-1-foundation/research/visual-comparison
```

Record a targeted browser pass for 1440/768/390, selected breakpoint boundaries, keyboard focus, reduced motion, local Diff overflow, console errors, and network errors. If a browser test script is added, expose it as a single `npm run test:browser` command and keep it limited to this contract.

### Evaluation

```bash
python3 evaluation/tools/score.py --validate-corpus \
  --manifest evaluation/manifest.json \
  --case-set evaluation/case-sets/phase1-quick.json

python3 evaluation/tools/score.py --selftest

python3 evaluation/tools/score.py \
  --manifest evaluation/manifest.json \
  --case-set evaluation/case-sets/phase1-quick.json \
  --runs evaluation/fixtures/phase1-reference-run.json \
  --out /tmp/forgepilot-phase1-reference-score.json

python3 evaluation/tools/score.py --compare-report \
  /tmp/forgepilot-phase1-reference-score.json \
  evaluation/fixtures/phase1-reference-score.json
```

Repository-wide final checks relevant to this slice:

```bash
git diff --check
git status --short
```

## Risks, assumptions, and deferred decisions

- **Risk — accidental Legacy restoration:** the old visuals are attractive and mature, but the selected RepoSage style and pages belong to a different product shape. Reuse only the comparison method, accessibility evidence, and narrow drift-test ideas.
- **Risk — fake business UI in a “scaffold”:** seven route components, mocked CRUD, fake review actions, or a login page would cross the Phase 1 boundary. One shared placeholder is enough.
- **Risk — evaluation leakage:** importing holdout data is permitted as frozen corpus data, but Phase 1 tooling must only select development quick-set IDs and must not produce holdout scores. Holdout execution remains a Phase 8 gate.
- **Risk — misleading reference score:** synthetic predictions validate the scorer, not ForgePilot quality. Label and store them under `fixtures/`, never `results/` as a claimed baseline.
- **Risk — metric-name ambiguity:** “false positive rate” is commonly `FP/(FP+TN)`, while the proven corpus scorer reports `FP/(TP+FP)`. Freeze the latter as `falseReportRate` and report Precision separately.
- **Assumption:** TypeScript is preferable for a new scaffold because type-check is a Phase 1 acceptance requirement. User/task approval should make this explicit before implementation.
- **Deferred:** final visual direction, token values, typography, breakpoints, motion budget, and optional component/browser libraries. The 12 development quick-set IDs are frozen by the Phase 1 planning artifacts above.
- **Boundary:** none of these recommendations authorizes Phase 2, a real Review Engine, real model calls, login, or any production business page.
