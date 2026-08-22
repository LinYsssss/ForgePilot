# Restore full dynamic frontend experience

## Goal

Restore the complete cyber-motion language from the user-provided
`ForgePilot-Frontend/` visual study in the official `frontend/` application,
while preserving every real route, API call, permission boundary, loading
state, error state, and business workflow already implemented.

## Requirements

- Treat `ForgePilot-Frontend/` as the visual and motion reference, not as a
  replacement application or a source of fake business data.
- Restore all reference motion families in normal-motion mode: interactive
  canvas particles, animated background orbs, grid/scanline atmosphere,
  rotating holographic borders, pulse glows, radar sweeps, laser scans,
  shimmer waves, button/surface light sweeps, and route/page entrance motion.
- Apply page-specific motion to the real login, projects, requirements,
  reviews, detail, member, and settings surfaces without changing their data
  ownership or actions.
- Preserve exactly three top-level navigation entries and seven product routes.
- Preserve status text and business semantics; animation remains decorative
  and must never fabricate telemetry, scores, records, or engine activity.
- Keep the same-origin HTTP boundary and all backend authorization behavior
  unchanged.
- Retain an accessible reduced-motion mode. When the operating system requests
  reduced motion, continuous and large movement stops while content, focus,
  status, and actions remain unchanged.
- Pause per-frame work when the document is hidden and clean up all global
  listeners and animation frames when the motion component unmounts.
- Add no animation runtime, UI framework, state library, chart library, or
  second request client.
- Keep the reference directory untracked and modify only the official
  application and its task/spec documentation.

## Acceptance Criteria

- [ ] The official shell visibly renders animated orbs, scanline/grid layers,
      and an interactive neon particle field in normal-motion mode.
- [ ] Route changes use the cyber page transition without replacing business
      state outside normal Vue Router behavior.
- [ ] Login includes radar/laser/shimmer/float effects mapped onto its real
      authentication form and existing state messages.
- [ ] Project, requirement, and review list/detail/settings/member surfaces use
      pulse, sweep, glow-border, shimmer, and entrance effects where the
      corresponding real content exists.
- [ ] All existing API modules, route constants, session behavior, permissions,
      visible loading/error states, and workflows remain intact.
- [ ] No fake telemetry, AI score, record, control, log, or unavailable route is
      introduced.
- [ ] Particle behavior is deterministic-testable, DPR and particle count are
      bounded, and hidden-document/unmount cleanup prevents background work.
- [ ] `prefers-reduced-motion: reduce` disables continuous and large movement
      without hiding content or changing focus order.
- [ ] Frontend lint, strict typecheck, complete tests, production build, and
      `git diff --check` pass.
- [ ] Rebuilt Compose frontend and backend are healthy; the frontend origin
      responds successfully at `http://127.0.0.1:18081` for Cloudflare Tunnel.

## Explicit Non-goals

- Replacing official views with the reference project's mock views.
- Adding routes, endpoints, business state, telemetry, charts, or dependencies.
- Exposing backend or frontend ports directly on a public network interface.
- Re-running the completed Phase 8 holdout evaluation.
