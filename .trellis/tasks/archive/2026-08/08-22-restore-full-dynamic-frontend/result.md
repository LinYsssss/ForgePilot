# Result: restore full dynamic frontend experience

## Outcome

The official frontend now preserves the complete motion vocabulary requested
from the user-provided `ForgePilot-Frontend/` visual study while continuing to
use the official application's real APIs, routes, session, permissions, errors,
loading states, and workflows.

Normal-motion mode includes:

- interactive, connected, pulsing neon canvas particles with pointer repulsion;
- drifting grid, scanlines, a full-screen laser pass, and three floating orbs;
- radar rings/sweep/nodes and laser scanning on the real login surface;
- holographic border flow, pulse glow, shimmer, light sweep, page entrance,
  evidence scan, panel breathing, and keyed route choreography.

No fake telemetry, score, record, control, engine log, route, or business state
was added. The reference directory remains untracked.

## Functional boundaries preserved

- Top-level navigation remains exactly Projects, Requirements, and Reviews.
- The seven approved product routes and their guards are unchanged.
- `frontend/src/lib/`, feature API modules, session implementation, transport
  types, backend, database, and Compose port bindings were not changed.
- No dependency or package-lock change was made.
- Frontend and backend remain loopback-only; Cloudflare Tunnel should target
  `http://127.0.0.1:18081`.
- The completed Phase 8 holdout evaluation was not rerun.

## Motion lifecycle and accessibility

- Particle count is bounded to 36-90 and DPR to 2.
- Canvas work stays outside Vue reactivity.
- Animation frames stop for reduced motion, hidden documents, and unfocused
  windows, and all listeners/frames are removed on unmount.
- Coarse pointers retain ambient particles without pointer interaction.
- `prefers-reduced-motion: reduce` collapses CSS animation/transition durations
  and hides the canvas/global laser while preserving content and focus order.
- Decorative layers are `aria-hidden` and pointer-transparent; existing status
  text, landmarks, skip link, and focus styles remain intact.

## Verification

Executed from `frontend/` in the required order:

```text
npm ci                         PASS (206 packages installed)
npm run lint                   PASS
npm run typecheck              PASS
npm run test -- --run          PASS (8 files, 25 tests)
npm run build                  PASS (70 modules transformed)
```

Additional checks:

```text
git diff --check               PASS
route/API/dependency drift     PASS (no diff in app routes, lib, session, package files)
docker compose up -d --build frontend   PASS
docker compose ps             PASS (postgres/backend/frontend healthy)
GET :18080/actuator/health     PASS (UP)
GET :18081/                    PASS (new JS/CSS hashes served)
deployed CSS marker audit      PASS (particle, radar, laser, shimmer, border flow)
```

No Chromium, Chrome, Firefox, or Playwright binary is installed in this
environment. True rendered inspection at 1440/768/390 CSS pixels is therefore
not claimed. `frontend/MANUAL-ACCEPTANCE.md` now contains explicit full-motion,
reduced-motion, visibility-pause, responsive, console, and network checks for
the user to run through the Cloudflare hostname.

## Files and design knowledge

- Added the typed motion engine under `frontend/src/components/motion/`.
- Integrated motion in `App.vue`, `AppShell.vue`, login, tokens, and base styles.
- Added focused particle and ambient-motion contract tests.
- Updated the frontend design, motion, hook, component, directory, and quality
  specs so future work cannot silently revert to the earlier ambient-motion ban.
- Added no new architecture decision: this changes presentation behavior only.

## Rollback

Revert this task's frontend/spec commit and rebuild the frontend image. There
are no database, backend, API, dependency, or public-network changes to undo.
