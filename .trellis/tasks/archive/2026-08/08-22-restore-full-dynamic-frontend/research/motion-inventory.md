# Reference motion inventory

## Source

The untracked `ForgePilot-Frontend/` study is the user-approved visual source.
The official `frontend/` remains the product implementation.

## Required mapping

| Reference effect | Reference location | Official target |
| --- | --- | --- |
| Floating neon orbs | `src/App.vue` | Global fixed ambient layer in official `App.vue` |
| Grid and scanline | `src/App.vue` | Global fixed background beneath `AppShell` |
| Interactive connected particles | `components/motion/CyberParticleField.vue` and `cyberParticles.js` | Typed official motion component/helper |
| Route cyber fade | `src/App.vue` | Keyed `RouterView` transition in `AppShell.vue` |
| Rotating glow border | `styles/tokens.css` | Official panels/cards and important action regions |
| Pulse glow | shared keyframe and status selectors | Brand/status/accent markers with real text retained |
| Radar sweep | shared keyframe and `LoginView.vue` | Decorative login radar behind the real auth form |
| Laser scan/sweep | `LoginView.vue` and `ReviewDetailView.vue` | Login decor and real review evidence/diff surfaces |
| Shimmer wave | shared keyframe and login controls | Loading/active decorative highlights and primary actions |
| Page fade/translate | shared `.page-enter` | All official routed feature pages |
| Hover lift/glow | common buttons/cards | Existing `.button`, panel, list-card, and nav selectors |

## Integration rules

- Copy algorithms and motion language, not reference data, telemetry, routes,
  roles, controls, or business markup.
- Keep animation decorative and pointer-transparent.
- Preserve the official semantic tokens and extend them with named values.
- Honor reduced motion and stop frame loops for hidden documents/unmount.
- Add no dependency: native Canvas, CSS animation, Vue transition, and browser
  media/visibility APIs are sufficient.
