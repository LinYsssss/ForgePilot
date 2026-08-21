# Type Safety

The frontend uses TypeScript in strict mode with Vue's typed SFC tooling.
`tsconfig.app.json` is the source of truth (`strict: true`, ES2022 target,
bundler resolution, and no emit), and `vue-tsc` checks both `.ts` and `.vue`
files.

## Type organization

- Keep a type next to the boundary that owns it while it is used by one
  feature/module.
- Promote a type to a shared module only when multiple authorized consumers
  share the same API contract; do not create a catch-all `types.ts` in Phase 1.
- Use Vue Router's `RouteRecordRaw` for route definitions and `as const` for
  immutable route/navigation contracts (`PRODUCT_ROUTE_PATHS` and
  `TOP_LEVEL_NAVIGATION`).
- Name interfaces/types for the domain object or transport contract they model,
  not for a component's visual appearance.

## Boundary validation

Phase 1 has no runtime schema library. `requestJson<T>` provides a compile-time
caller contract and parses JSON, but it cannot prove that an external response
matches `T`; the deliberate cast is confined to that I/O boundary. When a
business endpoint is introduced, add explicit runtime validation at the
boundary if its threat or compatibility model requires it, and document the
chosen library/contract before adding it.

Use `unknown` for caught errors and untrusted values, then narrow with a type
guard or an explicit status/body check. Do not silently coerce malformed data
into a valid domain object.

## Common patterns

```ts
export async function requestJson<T>(path: string): Promise<T> {
  // The generic is the caller's declared response contract.
}

const paths = ["/projects", "/requirements", "/reviews"] as const;
```

Prefer inference for values whose type is evident, and explicit return types
for exported functions and I/O boundaries. Use discriminated unions for
mutually exclusive states rather than several booleans that can conflict.

## Forbidden patterns

- `any`, broad untyped object bags, and `@ts-ignore`/`@ts-expect-error` without
  a documented, reviewed reason.
- Non-null assertions and type assertions used to silence a compiler error;
  assertions are acceptable only at a clearly identified boundary such as the
  existing `requestJson<T>` return and must not spread into views.
- Casting API data directly to a richer business type without validation.
- Disabling `strict` or changing compiler options to hide application errors
  (the existing `skipLibCheck` only avoids third-party declaration noise).
- Duplicating string route paths in templates when the route contract already
  exists in `src/app/routes.ts`.

Run `npm run typecheck` before considering a frontend change complete; `npm
run build` repeats the type check as its first step.
