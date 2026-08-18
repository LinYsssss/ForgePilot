# Dashboard Projection Contracts

> P7 记录（2026-08-18）：工作台、固定窗口度量、任务级 AI 日志和 raw JDBC projection 的跨层实现约束。

## Scenario: Project-scoped workbench/metrics projections

### 1. Scope / Trigger

- Trigger: 新增或修改项目级聚合 REST 端点、raw JDBC projection、固定窗口指标，或把任务级日志嵌入项目页面。
- Why: 这类改动同时跨越授权、数据库类型、API 响应和前端项目生命周期；只通过 service mock 测试无法捕获 H2 enum/check 约束或项目切换后的旧响应覆盖。

### 2. Signatures

- `GET /api/projects/{projectId}/workbench?limit=6`
- `GET /api/projects/{projectId}/metrics?window=30d`
- `GET /api/ai/logs?projectId={projectId}&taskId={taskId}&page=0&size=100`（taskId 可省略；存在 taskId 时仍必须带 projectId）
- Frontend project-scoped loader contract: `load(projectId, ...)` captures the project id and a monotonically increasing request generation.

### 3. Contracts

- `workbench` 和 `metrics` 在 service 层调用 `ProjectAuthorization.requireRead(projectId, userId)`；路径带项目 id 的端点必须进入对象级授权矩阵。
- `workbench.limit` 固定收敛到 `1..12`，默认 `6`；`metrics.window` 只接受 `7d/30d/90d`，默认 `30d`。
- AI task scope is the intersection of project and task: the client sends both ids; the server authorizes the task's project and rejects a mismatched pair with `400`.
- Raw JDBC queries must cast enum-backed or Hibernate check-constrained columns to `varchar` before mixing them with fallback literals (`UNKNOWN`) or unioning different enum columns as one `state` field. This keeps PostgreSQL/Flyway and H2/`ddl-auto=create-drop` behavior aligned.
- `useWorkspace.resetForProject()` resets AI logs, workbench, and metrics in addition to the pre-existing domain teardown order.
- A stale success, error, or `finally` from project A must not mutate loading/data/error state after project B becomes active. Stale errors must not be rethrown into the global toast path.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Anonymous workbench/metrics request | `401` |
| Stranger project id | `403` or `404`, never `2xx` |
| Project member request | `200` and a bounded response; empty data is valid |
| `limit < 1` or `limit > 12` | sanitize to `1`/`12` |
| unknown metrics window | `400 BAD_REQUEST` |
| taskId belongs to another project | `400`; do not render the task under the active project |
| malformed historical coverage/report JSON | exclude the row and increment `excludedRecords`; do not return `500` |
| response resolves after project switch | ignore stale result and stale error/finally |

### 5. Good/Base/Bad Cases

- Good: `ObjectLevelAuthorizationMatrixTest` drives stranger `403`, anonymous `401`, and owner `200` requests for both projections; the owner path executes the raw JDBC queries against the H2 test schema.
- Good: frontend composable behavior tests switch project while AI/workbench/metrics requests are pending and assert that only project B data remains.
- Base: no rows, no samples, or no active project produces an explicit empty/unavailable state rather than fabricated zeros or a stale project response.
- Bad: service-only Mockito coverage with no owner MockMvc path; H2 enum/check failures remain invisible until the dashboard returns `500`.
- Bad: `GET /api/ai/logs?taskId=...` without `projectId`, or a loader that commits a response solely because its promise resolved.

### 6. Tests Required

- Backend service tests: role predicates, window/limit validation, fail-soft JSON, percentiles, truncation, and zero-sample semantics.
- Backend MockMvc authorization matrix: stranger/anonymous/owner assertions for every project-scoped projection; owner assertions must exercise both endpoints and their raw JDBC queries.
- Frontend composable tests: task AI-log URL contains both ids; project switch/reset invalidates prior generations; stale success/error/finally cannot overwrite current state.
- Final verification: backend focused tests plus full verify, `npm test`, `npm run build`, `pwsh scripts/verify-local.ps1 -SkipSmoke`, and `git diff --check`.

### 7. Wrong vs Correct

#### Wrong

```sql
select coalesce(gate_verdict, 'UNKNOWN')
from agent_run;

select status as state from requirement
union all
select status as state from agent_run;
```

```js
const data = await api(`/ai/logs?taskId=${taskId}`)
if (data) aiLogs.value = data
```

#### Correct

```sql
select coalesce(cast(gate_verdict as varchar), 'UNKNOWN')
from agent_run;

select cast(status as varchar) as state from requirement
union all
select cast(status as varchar) as state from agent_run;
```

```js
const generation = ++requestGeneration
const projectId = activeProject.value?.projectId
const data = await api(`/ai/logs?projectId=${projectId}&taskId=${taskId}`)
if (generation === requestGeneration && activeProject.value?.projectId === projectId) {
  aiLogs.value = data
}
```

## Design Decisions

- Keep the dashboard projection additive and read-only; do not introduce a second fact table or a browser-side full-list join.
- Treat H2/MockMvc owner happy paths as contract tests for raw SQL portability, not merely smoke coverage.
- Keep project identity explicit in task-level AI-log requests even though the backend can derive it from taskId; this prevents stale route state from silently rendering another project's task under the active project.
