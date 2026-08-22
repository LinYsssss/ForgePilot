# Implementation plan: full dynamic frontend experience

## 1. Contract and inventory

- [x] Record every reference motion family and its official selector/component
      mapping in `research/motion-inventory.md`.
- [x] Replace the old ambient-motion prohibition in the frontend design,
      motion, hook, and quality contracts with the approved full-motion rule.

## 2. Motion foundation

- [x] Add typed deterministic particle helpers and focused tests.
- [x] Add `CyberParticleField.vue` with bounded DPR/count, pointer interaction,
      visibility/reduced-motion lifecycle, resize handling, and cleanup.
- [x] Add the global grid, scanline, and animated orb layer in `App.vue`.
- [x] Key the routed view transition in `AppShell.vue`.

## 3. Full visual integration

- [x] Add named motion/color/glow tokens and complete reference keyframes.
- [x] Map glow-border, pulse, sweep, shimmer, light sweep, and entrance motion
      to real shared controls and panels.
- [x] Add login radar/laser/float composition around the existing real form.
- [x] Add context-appropriate motion to project, requirement, review, member,
      settings, and detail screens without changing requests or actions.
- [x] Preserve responsive layout, focus visibility, text status, and bounded
      overflow at the existing `64rem` and `42rem` breakpoints.

## 4. Validation

- [x] Run `npm ci`.
- [x] Run `npm run lint`.
- [x] Run `npm run typecheck`.
- [x] Run `npm run test -- --run`.
- [x] Run `npm run build`.
- [x] Run `git diff --check` and inspect the complete diff for API/route drift.
- [x] Record normal/reduced-motion manual acceptance expectations for 1440,
      768, and 390 CSS pixels; record unavailable browser tooling honestly.

## 5. Deployment and finish

- [x] Rebuild the Compose frontend without exposing public ports.
- [x] Verify frontend, backend, and PostgreSQL health and local origin response.
- [x] Complete the Trellis result/spec review.
- [x] Present the required commit grouping for user confirmation; do not push.
