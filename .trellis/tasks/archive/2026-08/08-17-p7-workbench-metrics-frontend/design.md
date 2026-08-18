# P7 工作台、研发度量与前端收尾 — Technical Design

> 基线：父任务 `08-16-forgepilot-upgrade/design.md` §12、`implement.md` P7；
> 当前状态审计：`research/current-state-audit.md`；用户于 2026-08-17 确认严格遵循原计划。

## 1. Scope and delivery boundary

P7 是一个跨后端聚合、前端信息架构和旧壳收尾的完整 Phase，但不改变 P1–P6 的领域事实。实现分为五个可审查纵切：

1. Workbench projection；
2. Metrics projection + `/metrics`；
3. Knowledge 与 AI Logs 迁移；
4. Repository/PR、Agent/Reviews 合并与兼容路由；
5. Legacy shell 删除与全站 QA。

后端新增 `com.example.codereview.dashboard` 领域包，平铺放 Controller、Service、DTO 和只读 projection/query。前端沿用 feature-first + 模块级单例 composable；页面不直接发 HTTP。

不新增事实表。Flyway V36 只允许添加由最终查询形状需要的复合索引。

## 2. Final information architecture

最终导航只保留八项：

| Key | Label | Canonical route | Composition |
|---|---|---|---|
| dashboard | 工作台 | `/dashboard` | 我的任务、我的 Finding、待审 PR、风险与动态 |
| projects | 项目 | `/projects` | existing Ink project management |
| requirements | 研发任务 | `/requirements` | existing Requirement/AC/check/assistant |
| repository | 代码仓库 | `/repository` | repository commits/diff + Pull Request workflow |
| agent | 智能审查 | `/agent` | Agent run workspace + interactive Review/Report archive |
| quality | 质量中心 | `/quality` | Finding lifecycle |
| knowledge | 知识库 | `/knowledge` | documents/index/search |
| metrics | 研发度量 | `/metrics` | four metric groups + AI logs |

Compatibility routes:

```text
/ink            -> /dashboard
/reviews        -> /agent?section=reviews
/pull-requests  -> /repository?section=pull-requests
/ai-logs        -> /metrics?section=ai
/agent-evidence=:location -> /agent?evidence=:location
```

Redirects preserve relevant query parameters. `/agent` owns the route name `agent`; the existing InkAtelier component is renamed/reframed as the canonical intelligent-review page. `useAgentWorkspace` active-page predicate becomes `agent` only.

## 3. Workbench API

### Signature

```http
GET /api/projects/{projectId}/workbench?limit=6
```

- `limit`: sanitized to 1–12, default 6.
- Authorization: `ProjectAuthorization.requireRead(projectId, userId)`.
- Response is a bounded aggregate object, not PageResponse.

### DTO

```text
WorkbenchResponse
  generatedAt
  role
  requirements[]
  findings[]
  pullRequests[]
  riskSummary
  recentActivity[]
```

Queue predicates:

- requirements: `projectId + assigneeId=userId + status not in DONE,CANCELED`, newest updated first;
- findings: join finding→agent_run, `projectId + assigneeId=userId + lifecycle not in CLOSED,REJECTED`, severity then created time;
- PRs: only LEADER/REVIEWER receive items; `status=OPEN + reviewState in PENDING,CHANGES_REQUESTED`, newest updated first. DEVELOPER gets an empty list plus role-derived reason; no per-PR assignment claim.

Risk summary:

- latest bounded Agent runs gate distribution;
- active HIGH/CRITICAL Finding count;
- unresolved coverage warning count from persisted coverage JSON;
- latest high-risk Review report count.

Recent activity is built from bounded projections of Requirement updates, Finding creation/verification, Agent terminal updates and Review reports. It carries a typed target (`REQUIREMENT`, `FINDING`, `PULL_REQUEST`, `AGENT_RUN`, `REVIEW_REPORT`) and object ID; frontend maps target to canonical route/query.

## 4. Metrics API

### Signature

```http
GET /api/projects/{projectId}/metrics?window=30d
```

Allowed windows: `7d`, `30d`, `90d`; default `30d`. Unknown values return BAD_REQUEST. Response contains `window`, `from`, `to`, `generatedAt`, per-group `sampleCount`, and global `excludedRecords`/`truncated` metadata.

### R&D quality

```text
GateMetric { pass, warn, block, unknown }
FindingMetric { totalVerified, active, activeHighCritical, terminal, closureRate, bySeverity, byLifecycle }
CoverageMetric { covered, notFound, atRisk, excludedRecords }
```

Coverage JSON parsing is fail-soft. Invalid historical rows increase excludedRecords.

### Requirement quality

```text
RequirementMetric { total, byStatus, totalAcs, averageAcs, checkedRequirements, checkCoverageRate }
RequirementCheckMetric { latestReports, itemsByDimension, itemsBySeverity, excludedRecords }
```

Latest report means highest round per Requirement inside the project. No numeric score is introduced.

### Processing efficiency

```text
DurationMetric { sampleCount, averageMs, p50Ms, p95Ms, minMs, maxMs }
EfficiencyMetric { interactiveReview, agentTurnaround, findingVerification }
```

- Review: finishedAt-startedAt for terminal rows with both timestamps;
- Agent: terminal run updatedAt-createdAt;
- Finding: verifiedAt-createdAt.

Percentiles are calculated from bounded duration projections in Java to keep H2/PostgreSQL behavior identical. `app.metrics.max-samples` caps each distribution; exceeding it sets `truncated=true`.

### AI metrics

```text
AiMetric { calls, successes, failures, successRate, totalTokens, averageLatencyMs, p95LatencyMs, byRequestType[] }
```

UI aggregation source is `ai_call_log`. Prometheus is an operational cross-check only. Detailed rows remain on existing `/api/ai/logs` PageResponse endpoint.

## 5. Query and indexing strategy

Repositories add bounded projection queries rather than loading full entities and joining in the browser. Use JPQL DTO/interface projections where portable; use native SQL only when the query cannot be expressed safely and mirror it in H2/Postgres tests.

V36 candidate indexes, finalized against actual repository queries:

```sql
requirement(project_id, assignee_id, status, updated_at)
agent_run(project_id, created_at)
pull_request(project_id, status, review_state, updated_at)
ai_call_log(project_id, created_at)
agent_finding(assignee_id, lifecycle_status, created_at)
```

Existing indexes must be searched before adding duplicates. Flyway remains forward-only.

## 6. Workbench frontend

New `useWorkbench.js` owns response state, loading key, `loadWorkbench()` and `reset()`. `useWorkspace.refreshAll` should not blindly load all workbench details; the dashboard page explicitly loads its bounded projection when activeProject changes.

`DashboardPaper` becomes a task-oriented layout:

- top risk strip;
- three queue cards with counts and max six rows;
- recent activity timeline;
- role-aware PR empty explanation;
- direct navigation through `useWorkspace.openWorkbenchTarget`.

The page distinguishes: no project, loading, empty queue, unavailable group, request error and legitimate zero.

## 7. Metrics frontend

New `useDevelopmentMetrics.js` owns window, metrics, loading/error and reload. `InkMetricsPage` contains four semantic sections and URL-synced `section`/`window` query state.

Charts use accessible native CSS/SVG primitives:

- numeric summary cards;
- labelled horizontal bars/distributions;
- no color-only encoding;
- table/list fallback is always present in the DOM.

AI section mounts existing detailed log workflow through `useAiLogs`; `taskId` query loads task-scoped logs. Metrics summary and logs have independent loading/error states.

## 8. Knowledge migration

Create `InkKnowledgePage` + `KnowledgePaper`. The Paper is presentational; it receives documents/search results/permissions and emits actions. Existing `useKnowledge` remains the single API/state owner.

Permission projection from `activeProject.myRole`:

- upload/reindex: follow backend roles verified during implementation;
- delete: LEADER only if backend remains leader-only;
- search/read: all project members.

The UI must not expose an action the backend forbids, but backend authorization remains authoritative.

## 9. Repository and PR consolidation

`InkRepositoryPage` uses URL-synced `section=commits|pull-requests`, default commits. Existing repository content remains one section; a new presentational Pull Request paper reuses `usePullRequests` and current review-action components/policies.

Old `/pull-requests` redirects to the PR section. Selection query (`pullRequestId`) is supported so workbench entries locate the row/detail. No duplicate PR API client or state store is created.

## 10. Intelligent review consolidation

Canonical `/agent` page retains the existing Ink Agent Run vertical slice and adds `section=agent|reviews`, default agent. Interactive review task/report content is migrated from `ReviewsView` by reusing `useReviews`, shared report components, KnowledgeDocPicker and existing actions.

Queries:

- `runId` / `evidence` select Agent mode;
- `reviewTaskId` / `reportId` select Reviews mode;
- old `/reviews` redirects with `section=reviews`;
- existing evidence links target route name `agent`.

The page may split large papers into feature components, but Agent SSE/poll lifecycle remains owned by `useAgentWorkspace` and Review polling remains owned by `useReviews`.

## 11. Legacy retirement

After all canonical pages are Ink-native:

1. update router and `INK_NAV` to eight entries;
2. prove old routes redirect and all canonical routes carry `meta.shell=ink`;
3. remove imports/references to `PullRequestsView`, `KnowledgeView`, `ReviewsView`, `AgentView`, `AiLogsView`;
4. delete those views only after equivalent Ink surfaces pass tests;
5. remove `AppShell` and `LoginView` when no route reaches the legacy branch;
6. simplify `App.vue` to global CSRF/session/401 lifecycle + router view;
7. search before deleting shared components/styles; retain anything consumed by new Ink pages.

## 12. Error, compatibility and observability

- Workbench/metrics endpoints return existing ErrorCode/ApiResponse conventions.
- JSON parse exclusions are surfaced as counts, not silent zeros.
- Metrics logs never expose AI error messages in aggregate; detailed logs retain existing authorized response.
- Old URLs remain functional redirects; no stored browser link should 404.
- All project changes call new domain `reset()` methods in the established reset order.
- Metrics endpoint logs only failures/slow query warnings; it does not emit one log per row.

## 13. Testing and QA

Backend:

- workbench predicate tests per role;
- anonymous/stranger/cross-project authorization;
- window validation and time boundaries;
- gate/finding/coverage/requirement/check/duration/AI aggregation fixtures;
- percentile and max-sample truncation;
- malformed historical JSON fail-soft;
- V36 migration fresh + upgrade contexts.

Frontend:

- route/redirect/query preservation;
- eight-item nav single source;
- workbench target mapping and role empty states;
- metrics window/section and zero vs unavailable;
- PR/Review/AI task deep links;
- composable reset on project switch;
- no duplicated HTTP logic;
- legacy view/shell zero-reference checks.

Browser QA:

- 390/768/1440;
- keyboard order, focus return, focus-visible, 44px targets;
- normal/reduced/static motion;
- loading/empty/error/permission/long-content;
- refresh, back/forward, direct old URL, evidence deep link;
- console/network/overflow and bundle-size comparison.

## 14. Rollback

- Backend APIs and indexes are additive; older frontend ignores them.
- Route migration is reversible per canonical route by restoring the previous component while keeping redirects.
- V36 indexes may remain after code rollback without behavior impact.
- Legacy views are deleted only in the final slice; the commit immediately before deletion is the rollback point.
