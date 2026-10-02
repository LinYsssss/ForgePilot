# Directory Structure

The frontend implements ForgePilot's product workflows in feature-owned Vue
components. The shell, router, shared request boundary and finite polling
composable are shared; there is no separate placeholder-page tree.

## Directory layout

```text
frontend/
├── README.md
├── index.html
├── package.json / package-lock.json
├── vite.config.ts              # Vite and Vitest configuration
├── tsconfig.json / tsconfig.app.json
├── scripts/lint.mjs            # policy checks
├── public/brand/               # application icon and horizontal lockup
├── src/
│   ├── main.ts / App.vue       # bootstrap, global styles and root component
│   ├── env.d.ts
│   ├── app/router.ts / routes.ts
│   ├── components/
│   │   ├── AppShell.vue
│   │   └── motion/             # decorative canvas lifecycle and helpers
│   ├── composables/useFinitePolling.ts
│   ├── features/
│   │   ├── auth/               # login, account settings and shared session
│   │   ├── project/            # projects and member management
│   │   ├── requirement/        # requirements, AC editor, guidance and status
│   │   ├── knowledge/          # documents and ingestion status
│   │   ├── scm/                # repository and identity integration
│   │   ├── review/             # review pages, findings, context and diff
│   │   ├── notification/       # API helper used by repository settings
│   │   └── workspace/          # read-only aggregation of existing APIs
│   ├── lib/http.ts / datetime.ts
│   └── styles/tokens.css / base.css
└── tests/                      # behavior, boundary and journey contracts
```

`Dockerfile`, `.dockerignore`, and `nginx.conf` are deployment files at the
frontend root, not application imports. `dist/` and `node_modules/` are generated
and ignored. Static assets belong in `public/` and need an explicit product use.

## Module organization

- `app/` owns routing, approved product paths and navigation constants.
- `components/` owns reusable presentation; motion stays decorative and bounded.
- `features/<feature>/` keeps each route page, local components, API types and
  helpers together. A feature need not have a page: notification has only an API
  helper and does not introduce another top-level menu.
- `composables/` holds the shared lifecycle primitive; feature-specific state
  stays in its owner rather than in a generic store.
- `lib/http.ts` is the single JSON request boundary. `styles/` owns semantic
  tokens and base rules; views do not define a second visual scale.
- `tests/` exercises public contracts rather than mirroring every source file.

Do not add empty layers or move the existing shell merely for symmetry. Product
paths are defined in `app/routes.ts`, not inferred from directory names.

## Naming conventions

- Vue SFCs use PascalCase (`AppShell.vue`).
- TypeScript modules describe their boundary (`routes.ts`, `http.ts`);
  composables use `useX.ts`.
- Tests use the source contract name plus `.spec.ts`.
- Exported immutable collections use `UPPER_SNAKE_CASE`; functions use camelCase.
- Keep relative imports; no aliases or barrel files without a concrete need.

## Reference examples

- [AppShell.vue](../../../frontend/src/components/AppShell.vue) owns the shell.
- [routes.ts](../../../frontend/src/app/routes.ts) owns product paths and redirects.
- [http.ts](../../../frontend/src/lib/http.ts) owns request semantics.
- [useFinitePolling.ts](../../../frontend/src/composables/useFinitePolling.ts)
  owns bounded polling, not endpoint or page-state decisions.
