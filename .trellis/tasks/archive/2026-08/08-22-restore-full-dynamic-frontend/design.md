# Design: full dynamic frontend experience

## Boundary

The change is presentation-only. The official Vue router, session bootstrap,
feature views, API modules, domain types, and backend remain authoritative.
The untracked `ForgePilot-Frontend/` directory supplies visual algorithms and
CSS motion vocabulary only.

## Motion architecture

### Global composition

`App.vue` owns a fixed, pointer-transparent ambient layer behind `AppShell`:

1. a CSS grid and scanline texture;
2. three independently timed neon gradient orbs;
3. a `CyberParticleField` canvas;
4. the existing application shell above those decorative layers.

`AppShell.vue` renders `RouterView` through a keyed Vue transition so navigation
gets the reference cyber fade/translate/scale choreography while preserving the
existing landmarks, skip link, navigation, and session controls.

### Particle engine

Framework-neutral helpers under `src/components/motion/` define typed particle
state, bounded device-pixel ratio, responsive particle count, creation,
stepping, color, and glow. The Vue component owns canvas setup and lifecycle.
It uses `requestAnimationFrame`, pointer coordinates without reactive per-frame
Vue updates, passive listeners, `visibilitychange`, and complete cleanup.

Normal motion keeps the reference floating, pulsing, connected neon particles.
Reduced-motion media queries prevent the engine from starting. A live media
query listener starts/stops it if the preference changes.

### CSS motion vocabulary

Named tokens define animation durations and easings. Global keyframes provide:

- `fp-float-orb`, `fp-pulse-glow`, `fp-radar-sweep`;
- `fp-laser-scan`, `fp-laser-sweep`, `fp-shimmer-wave`;
- `fp-border-flow`, `fp-light-sweep`, and `fp-page-enter`.

Existing semantic selectors receive effects rather than new business wrappers:
panels/cards gain animated border/glow behavior; buttons and navigation gain
light sweeps; status markers pulse; loading surfaces shimmer; review evidence
gets a restrained scanning line; login gets dedicated radar and laser decor.
All decorative pseudo-elements are pointer-transparent and below real content.

## Compatibility and accessibility

- No raw theme colors outside `tokens.css`; new color/glow values become named
  tokens.
- Decorative layers use `aria-hidden="true"` and never alter DOM reading order.
- Focus indication stays above visual effects.
- Reduced-motion disables animations and transitions, and the canvas stays
  blank/stopped.
- Narrow layouts reduce particle density and orb size but retain the full
  motion family.
- The canvas is DPR-capped and particle count is bounded.

## Verification

Unit tests cover particle math/lifecycle-facing helpers and the CSS/component
motion contract. Existing route, session, feature, HTTP, and accessibility
tests prove that business behavior remains unchanged. Production deployment is
rebuilt only after all frontend commands pass.

## Rollback

The motion layer is isolated to new motion files plus shell/style integrations.
It can be removed without changing API modules or business views. Compose can
roll back to the previous frontend image without database changes.
