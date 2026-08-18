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

## Project-scoped async loaders

P7 固定窗口 projection、workbench 和 task-scoped AI logs 都是模块级单例；它们可能在项目切换后仍有旧请求未完成。

- Loader must capture `activeProject.value.projectId` and increment a request generation before calling `api()`.
- `reset()` increments the same generation before clearing data/loading/error state.
- Success, error, and `finally` may mutate state only when both the generation and current active project id still match. Stale errors return silently instead of entering `useBusy.run`'s toast path.
- AI task logs always request both `projectId` and `taskId`; a task id alone is not a valid project-page scope.
- `useWorkspace.resetForProject()` must call `useAiLogs().reset()`, `useWorkbench().reset()`, and `useDevelopmentMetrics().reset()` so route/query or delayed responses cannot leak across projects.

Good pattern:

```js
const projectId = activeProject.value?.projectId
const generation = ++requestGeneration
const data = await api(`/projects/${projectId}/metrics?window=30d`)
if (generation === requestGeneration && activeProject.value?.projectId === projectId) {
  metrics.value = data
}
```

Bad pattern: commit every resolved promise, or reset data without invalidating the generation.
