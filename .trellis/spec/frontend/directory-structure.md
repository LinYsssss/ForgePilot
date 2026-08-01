# Frontend Directory Structure

> RepoSage frontend (Vue 3 + Vite, plain JS, no TS/Pinia). Updated 2026-07-31 after the App.vue split.

```text
frontend/src/
├─ main.js                  # createApp + router
├─ router.js                # hash-mode routes (8 views) + legacy #agent-evidence= redirect + nav registration
├─ nav.js                   # tiny navigation seam: composables use nav.name()/query()/push(), never vue-router directly
├─ App.vue                  # login/app split, nav dispatch, CSRF bootstrap, global teardown (~60 lines — keep it thin)
├─ styles.css               # the whole "Observatory" design system (tokens + themes + components)
├─ views/                   # one file per routed page; template + thin script that pulls singletons
├─ components/              # shared presentational components (AppShell chrome, dashboard widgets, agent/*)
├─ composables/             # module-singleton state + actions, one file per domain (see state-management.md)
├─ api/                     # client.js (fetch wrapper: envelope, CSRF, 401 funnel), page.js, apiError.js
└─ utils/                   # pure functions only (format.js, labels.js)
```

## Rules

- **Views hold no state.** A view's `<script setup>` only imports singletons/utils and destructures. New page = new `views/XxxView.vue` + route in `router.js`.
- **Cross-domain actions live in `useWorkspace.js`**, never inside a domain composable. Allowed domain-to-domain imports (acyclic, do not extend casually): `pullRequests → reviews/knowledge`, `reviews → repository/knowledge`.
- **Composables never import `router.js`** (it drags the whole .vue view tree and breaks node-based tests). Use the `nav` seam.
- **Relative imports carry explicit `.js`/`.vue` extensions** so modules load under plain node ESM (tests) as well as Vite.
- `utils/` must stay side-effect-free and DOM-free (labels.js currently touches nothing but strings).
