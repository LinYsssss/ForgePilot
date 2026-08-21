# Directory Structure

The Phase 1 frontend is a deliberately small Vue 3 application. The tree
expresses the application shell and its boundaries; it does not pre-create
business feature modules that are not yet authorized.

## Directory layout

```text
frontend/
├── README.md                  # local commands and boundaries
├── index.html                 # Vite document entry
├── package.json
├── package-lock.json
├── vite.config.ts             # Vite and Vitest configuration
├── tsconfig.json              # project reference
├── tsconfig.app.json          # strict application/test compiler options
├── scripts/
│   └── lint.mjs               # foundation policy checks
├── src/
│   ├── main.ts                # application bootstrap and global styles
│   ├── App.vue                # root component
│   ├── env.d.ts               # Vue SFC ambient declarations
│   ├── app/
│   │   ├── router.ts          # router factory
│   │   └── routes.ts          # approved paths and top-level navigation
│   ├── components/
│   │   └── AppShell.vue       # document landmarks and navigation shell
│   ├── lib/
│   │   └── http.ts            # same-origin request boundary
│   ├── styles/
│   │   ├── tokens.css         # shared theme and reusable visual values
│   │   └── base.css           # global foundation and responsive rules
│   └── views/
│       └── FoundationPlaceholderPage.vue
└── tests/
    ├── routes.spec.ts         # route and semantic shell contract
    ├── http.spec.ts           # request boundary contract
    └── motion.spec.ts         # reduced-motion contract
```

`Dockerfile`, `.dockerignore`, and `nginx.conf` are deployment files at the
frontend root; they are not imported by application code. Static assets, when
needed, belong in a dedicated `public/` directory and must have an explicit
product use.

## Module organization

- `app/` owns application-wide routing and navigation constants.
- `components/` contains reusable, presentation-focused Vue components. The
  current shell is the only shared component.
- `views/` contains route-level components. Phase 1 views are placeholders and
  must not manufacture business data or actions.
- `lib/` contains framework-neutral utilities and I/O boundaries. `http.ts`
  is the single JSON request entry point.
- `styles/` owns the B Precision Review Console tokens and global foundation
  styles. Components consume semantic custom properties rather than defining
  their own visual scale.
- `tests/` mirrors public contracts, not implementation details.

When an authorized feature is introduced, keep its route view, components,
composables, and types in a bounded feature directory rather than adding an
unrelated top-level menu or utility. Do not create empty business directories
in Phase 1, and do not move the existing shell merely for symmetry.

## Naming conventions

- Vue single-file components use PascalCase (`AppShell.vue`).
- General TypeScript modules use concise lowercase names that describe one
  boundary (`router.ts`, `routes.ts`, `http.ts`); composables use `useX.ts`.
- Tests use the source contract name plus `.spec.ts`.
- Exported immutable collections/constants use descriptive `UPPER_SNAKE_CASE`
  names (`TOP_LEVEL_NAVIGATION`); functions use `camelCase`.
- Use relative imports consistent with the current Vite configuration. Do not
  add path aliases or barrel files without a concrete module-boundary need.

## Reference examples

- [AppShell.vue](../../../frontend/src/components/AppShell.vue) demonstrates
  the document shell and navigation boundary.
- [routes.ts](../../../frontend/src/app/routes.ts) is the source of truth for
  the seven approved Phase 1 paths.
- [http.ts](../../../frontend/src/lib/http.ts) is the only request utility.
