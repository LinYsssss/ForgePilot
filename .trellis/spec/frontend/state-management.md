# Frontend State Management

> No Pinia. State is held in **module-level singletons** exported through `useXxx()` composables. Updated 2026-07-31.

## The pattern

```js
// composables/useThing.js — state at module scope, one instance for the whole app
import { ref, reactive } from 'vue'
const items = ref([])
const form = reactive({ name: '' })
async function loadItems() { /* ... */ }
export function useThing() { return { items, form, loadItems } }
```

- Destructuring keeps reactivity: refs are shared instances; `reactive` forms are mutated via `Object.assign`, **never replaced** (template bindings would detach).
- Every domain owning per-project state exposes `reset()`; `useWorkspace.resetForProject()` calls them all in the original teardown order (repository → reviews → pullRequests → agent → knowledge selections).

## Domain map

| File | Owns |
|---|---|
| useSession | authenticated / me / projects / activeProject |
| useBusy | shared `busy` flags + `run(action, key)` wrapper (toast on error, silent on 401) |
| useConfirm | confirm modal state; domains call `ask({title, body, onConfirm})` |
| useProjects / useRepository / useReviews / useFeedback / usePullRequests / useKnowledge / useAgentWorkspace / useAiLogs | per-domain state + API actions |
| useWorkspace | cross-domain orchestration: refreshAll, selectProject, selectCommit→review prefill, openReport, logout, all navigation |

## Hard rules

- 401 handling is centralized: `api/client.js` funnels into `setUnauthorizedHandler` (registered once in App.vue) and `useBusy.run` swallows 401s. Never toast a 401 per call site.
- Review polling completion is injected (`useReviews.setCompletionHandler`) by useWorkspace — do not import useWorkspace from a domain (cycle).
- The agent SSE lifecycle (one EventSource per run, 15s poll back-off, debounce, teardown in `reset()`) is pinned by `tests/composables.test.mjs` — change it only with the tests.
- Session credentials live in the HttpOnly cookie only; **no localStorage/sessionStorage** outside useTheme (smoke test enforces this).
