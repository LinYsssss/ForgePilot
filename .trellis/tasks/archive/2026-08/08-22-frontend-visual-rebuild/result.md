# Official frontend visual rebuild — result

## Outcome

The tracked Vue frontend now uses one coherent reference-informed dark review
console across authentication, Projects, Members, Settings, Requirements,
Requirement detail, Reviews, Review detail, and Finding cards. The reference
application itself remains untracked and unchanged.

The implementation deliberately transferred visual hierarchy rather than
runtime behavior. No reference mock record, fake metric, fake AI score, fake
diff, fake engine log, knowledge upload control, theme runtime, particle field,
or unavailable endpoint entered the official application.

## Delivered changes

- Replaced the light token set with a single dark semantic theme: deep layered
  canvas/surfaces, restrained translucent panels, cyan/blue emphasis, separate
  semantic state colors, and static decorative gradients.
- Rebuilt the semantic shell into a three-zone header while preserving the
  visible skip link, labelled navigation, exactly three top-level entries,
  session account, and real logout flow.
- Rebuilt login around the real login/register calls plus a concise explanation
  of the product causal chain; no demo credentials or role switching.
- Rebuilt Projects and Members as responsive real-data cards with leader-only
  creation/management and stable SCM identity editing.
- Rebuilt Settings as the same three honest sections: SCM write operations,
  explicitly unavailable Knowledge HTTP surface, and Requirement quality
  checks with no score or overall verdict.
- Rebuilt Requirement list/detail around project scope, status, assignment,
  stable AC keys, editable draft/new revision flow, and immutable history.
- Rebuilt Reviews around the real PR-id query surface, separate execution and
  Decision columns, request-review action, and separate Requirement activity.
- Reordered Review detail into context/identity, AC and coverage evidence,
  Findings, the human Decision gate, and the immutable snapshot.
- Rebuilt Finding cards while keeping human status, cross-round continuity,
  unrecorded AI confidence, and parent Review Decision as four distinct
  labelled containers.
- Fixed the previously documented missing `.button-quiet` presentation and
  updated the manual acceptance note.
- Added a regression assertion for the single dark token contract.

## Boundaries preserved

- Top-level navigation entries: 3 (`/projects`, `/requirements`, `/reviews`).
- Approved product paths: 7; login remains outside the product route count.
- Package dependencies: unchanged; no UI framework, state/request library,
  icon package, charting package, or animation runtime added.
- API and state modules: unchanged.
- GitHub/GitLab selection, public defaults, webhook path guidance, and
  write-only credentials: unchanged.
- `ForgePilot-Frontend/`: still untracked and excluded from every staged path.

## Validation evidence

Baseline before frontend source changes:

```text
npm run lint                                      PASS
npm run typecheck                                 PASS
npm run test -- --run                            20/20 PASS
npm run build                                     PASS
```

Focused gates passed after each slice:

```text
routes + session + motion                         7/7 PASS
SCM + session + three-role journey                8/8 PASS
Requirement + journey + routes                    8/8 PASS
Review journey + routes                           6/6 PASS
```

Final frontend gate after `npm ci`:

```text
npm run lint                                      PASS
npm run typecheck                                 PASS
npm run test -- --run                            21/21 PASS (7 files)
npm run build                                     PASS
dist CSS                                          35.32 kB / 6.56 kB gzip
dist JS                                          162.90 kB / 55.53 kB gzip
```

Static audits:

```text
git diff --check                                  PASS
raw color scan outside tokens.css                 0 matches
reference-only fake/runtime term scan             0 relevant matches
responsive breakpoint inventory                   only 64rem and 42rem
forbidden dependency lint                         PASS
route/menu contract test                          PASS
semantic/labelled-control journey test            PASS
```

Compose deployment:

```text
docker compose up -d --build                      PASS
postgres                                           healthy
backend                                            healthy
frontend                                           healthy
GET :18080/actuator/health                        status=UP
GET :18081/                                       PASS
GET rebuilt CSS asset                             PASS
```

## Manual visual-check availability

The execution environment contains no Chromium, Chrome, Firefox, Playwright,
Puppeteer, or cached browser container image. A real rendered inspection at
1440/768/390 CSS pixels therefore could not be performed here. This is recorded
as an **unverified manual portion**, not converted into an automated pass.

The implementation still provides evidence for the underlying risks:

- only two approved responsive breakpoints (`64rem`, `42rem`);
- every multi-column page layout collapses at `64rem`;
- mobile action/form stacks apply at `42rem`;
- wide Review tables scroll inside `.table-scroll` and have an explicit minimum
  width;
- paths, hashes, Finding evidence, and context snapshots wrap or locally scroll;
- `body` hides page-level horizontal overflow as a final guard;
- skip link, semantic landmarks, native controls, labels, heading checks,
  role-dependent actions, and the four separate marks are covered in tests;
- reduced motion forces negligible transition/animation duration.

The updated `frontend/MANUAL-ACCEPTANCE.md` remains the authoritative real-
browser checklist for a human workstation.

## Deviations and limitations

- The original plan requested rendered viewport inspection “when a browser
  surface is available”; none was available. AC8/AC9's automated and static
  portions pass, but their real-browser visual portion remains unverified.
- The rebuild does not add a project-wide Review list because the server still
  exposes Review history by PR id only.
- Project knowledge upload and implementation guidance remain absent because
  the tracked frontend/backend do not expose those HTTP workflows.
- No raw patch viewer was invented: Review detail shows the real evidence,
  coverage manifest, and opaque immutable context snapshot it receives.

## New decisions and spec updates

No new product or architecture decision. The existing frontend visual contract
was intentionally updated from the old light scheme to the user-requested
single dark reference-informed scheme, including the approved `64rem` and
`42rem` layout boundaries and the explicit ban on ambient reference effects.

## Rollback

Revert the task's frontend/spec commit. There is no database migration, backend
change, dependency addition, or persisted theme state to undo. Compose can then
be rebuilt from the preceding commit.
