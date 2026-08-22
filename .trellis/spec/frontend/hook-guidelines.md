# Hook Guidelines

In Vue terminology, stateful hooks are composables. Phase 1 has no custom
composable because the shell has no business state. Vue Router's `useRoute`
and `useRouter` are used where route context is required; keep those calls in
`<script setup>` or a composable called from setup.

## Composable pattern

Create a composable only when it owns reusable reactive or lifecycle behavior,
not merely to hide a few lines of presentation code. Name the file and
factory `useX` (`useReviewFilters.ts`, for example), return a documented typed
object, and keep it in the owning feature directory. A composable must not
mutate another feature's state or import a view component.

Prefer this shape for future authorized work:

```ts
import { computed, ref } from "vue";

export function useExample() {
  const value = ref<string | null>(null);
  const hasValue = computed(() => value.value !== null);

  return { value, hasValue };
}
```

Do not introduce a generic `useApi`, `useEverything`, or lifecycle wrapper
without a concrete repeated contract. Plain deterministic helpers belong in
`src/lib/`, not under a composable name.

## Data fetching

`src/lib/http.ts` exposes the single `requestJson<T>(path, options)` boundary.
It uses native `fetch`, sends `Accept: application/json`, adds a JSON content
type when a body is present, and always uses `credentials: "same-origin"`.
Every non-2xx response becomes an `HttpError` carrying the numeric status and a
body, where the body is the parsed JSON when the payload is JSON, the raw text
when it is not (an HTML gateway error page, for example), and `undefined` when
the response has no body or uses a bodiless status (204, 205, 304). A failing
response never turns into a parse error that loses the status. Successful
responses are parsed as JSON, and a bodiless success resolves to `undefined`.
The boundary does not retry, synthesize an envelope, or call an AI/SCM service.

Callers provide the expected response type and own loading/error presentation;
they should not duplicate credential or header setup. A future runtime schema
may validate an external payload at this boundary, but Phase 1 intentionally
has no validation dependency.

When CSRF protection is introduced, the token belongs in `options.headers` of
`requestJson` — that is the single intended injection point, alongside the
existing `Accept`/`Content-Type` handling. Phase 1 does not implement CSRF and
`http.ts` contains no token logic; do not add a parallel request path for it.

## Naming and lifecycle

- Composable factories use `use` + PascalCase and are synchronous unless their
  contract explicitly performs I/O.
- Keep `onMounted`, watchers, and cleanup next to the state they manage.
- Stop watchers/listeners when the owning component is unmounted; do not leave
  global listeners from a view.
- Keep per-frame canvas updates outside Vue reactivity. Ambient motion follows
  the reduced-motion, visibility, focus, bounded-work, and cleanup contract in
  `motion.md`.

## Common mistakes

- Adding Pinia, Axios, React-style hook conventions, or a query/cache library
  before a real shared-state requirement exists.
- Catching `unknown` errors as `any` or discarding `HttpError.status` and its
  parsed body.
- Retrying requests implicitly, changing same-origin credentials, or inventing
  a response envelope in a feature composable.
- Creating a composable solely to avoid a clear local `ref`/`computed`.
