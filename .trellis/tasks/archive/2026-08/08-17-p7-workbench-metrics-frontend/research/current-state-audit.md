# P7 Current-State Audit

> Audit date: 2026-08-17. Scope: workbench, metrics, route consolidation, Knowledge and AI Logs migration, legacy-shell retirement.

## 1. Frontend migration state

The visual direction is already frozen by `08-12-frontend-guofeng-cyber-redesign`: A「墨境书院」. P7 must reuse the existing Ink tokens, shell, navigation model and feature-first structure; it must not reopen visual-direction discovery.

Current routes in `frontend/src/router.js`:

| Route | Current component | Shell | P7 disposition |
|---|---|---|---|
| `/dashboard` | `InkDashboardPage` | Ink | Replace generic counts with workbench queues and risk/recent activity |
| `/projects` | `InkProjectsPage` | Ink | Keep |
| `/repository` | `InkRepositoryPage` | Ink | Expand with Pull Request section |
| `/requirements` | `InkRequirementsPage` | Ink | Keep; workbench links here |
| `/quality` | `InkQualityPage` | Ink | Keep; workbench links here |
| `/pull-requests` | `PullRequestsView` | legacy | Merge into Repository; retain compatibility redirect |
| `/knowledge` | `KnowledgeView` | legacy | Rebuild as `InkKnowledgePage` with same composable/API semantics |
| `/reviews` | `ReviewsView` | legacy | Merge into the intelligent-review Ink workspace |
| `/agent` | `AgentView` | legacy | Replace with the existing `InkAtelierPage` vertical slice, expanded to include interactive reviews |
| `/ai-logs` | `AiLogsView` | legacy | Merge detailed log browser into `/metrics` AI section; retain redirect |
| `/ink` | `InkAtelierPage` | Ink | Temporary prototype route; conflicts with the final target IA unless normalized |

`frontend/src/features/shell/inkNav.js` still exposes both “墨境工作台” (`inkAtelier`) and “总览” (`dashboard`) plus five legacy navigation entries. Once all remaining pages migrate, `AppShell.vue` and the legacy branch in `App.vue` become unreachable and can be deleted after reference tests prove zero consumers.

P4b explicitly deferred `/reviews`, `/agent`, and `/pull-requests` migration to P7. Therefore P7 is broader than the seed PRD: it owns all remaining legacy routes, not only Knowledge and AI Logs.

## 2. Workbench data feasibility

### My development tasks

Source: `requirement`.

- `RequirementEntity` has `projectId`, `assigneeId`, `status`, `priority`, `createdAt`, `updatedAt`.
- Exact queue: current user is assignee and status is not `DONE/CANCELED`.
- Detail target: `/requirements` plus `requirementId` query/selection.

### Findings assigned to me

Source: `agent_finding` joined through `agent_run.projectId`.

- `Finding` has assignee, severity, lifecycle, createdAt, fix SHA and resolution suggestion.
- Exact queue: current user is assignee and lifecycle is not `CLOSED/REJECTED`.
- Detail target: `/quality` plus finding selection.

### Pull requests awaiting review

Source: `pull_request`.

- There is no per-PR reviewer assignment column.
- Available facts are project, status, reviewState, PR number/title/branches/head SHA and timestamps.
- A genuinely user-assigned “待我审查” queue cannot be produced without adding a new assignment domain.
- Recommended truthful projection: for LEADER/REVIEWER show project PRs with `status=OPEN` and `reviewState in (PENDING, CHANGES_REQUESTED)`; for DEVELOPER show an empty/role explanation. Do not claim per-user assignment.

### Risk and recent activity

Available facts:

- `AgentRun.gateVerdict` gives PASS/WARN/BLOCK;
- findings give severity/lifecycle;
- Review reports give risk and issue count;
- Agent/Review/Requirement/Finding timestamps support a bounded recent-activity feed.

A dedicated workbench endpoint is preferable to loading every existing page list through `refreshAll`: it avoids unbounded client joins and guarantees the same predicates are reused by cards and detail links.

## 3. Metrics feasibility

Recommended endpoint window presets: 7/30/90 days, default 30. “All time” should not be the default because current repositories include unbounded list methods and several tables only have single-column project indexes.

### R&D quality

Can be computed without new fact tables:

- gate verdict distribution (PASS/WARN/BLOCK) from `agent_run`;
- verified findings by severity;
- active high/critical findings;
- lifecycle terminal rate (`CLOSED/REJECTED` vs all verified findings);
- AC coverage verdict counts parsed from `agent_run.coverage_json`.

### Requirement quality

Available:

- Requirement status distribution;
- Requirement count, AC count and average AC per Requirement;
- check coverage: Requirements with at least one quality report;
- latest report issue counts by six dimensions/severity, parsed from the persisted `report_json` shared DTO.

No numeric “requirement score” exists. P7 must not invent one.

### Processing efficiency

Accurate existing timestamps support:

- interactive review execution time: `ReviewTask.startedAt -> finishedAt`;
- Agent end-to-end time: `AgentRun.createdAt -> terminal updatedAt`;
- Finding verification time: `Finding.createdAt -> verifiedAt`.

Requirement cycle time is not reliable: `Requirement.updatedAt` changes for edits and assignment, and there is no transition history. P7 should not label it as delivery cycle time unless a new history table is explicitly approved.

### AI metrics

`ai_call_log` contains request type, provider/model, prompt/response chars, prompt/completion/total tokens, latency, status and createdAt.

UI source of truth should be `ai_call_log`:

- total calls;
- success rate;
- total tokens;
- average/P95 latency;
- request-type distribution;
- detailed paginated logs (existing `/api/ai/logs`).

Prometheus remains the operational cross-check, not a second UI data source. `AiMetrics` currently records chat-review timers/tokens only, whereas `ai_call_log` covers requirement checks, coverage, embedding and assistant calls.

## 4. Backend shape

Recommended new domain package: `dashboard/` (Controller, Service, DTOs, query repository/projections).

Additive endpoints:

```text
GET /api/projects/{projectId}/workbench?limit=6
GET /api/projects/{projectId}/metrics?window=30d
```

Both require project read authorization. Responses are aggregate objects, not `PageResponse`; embedded lists are explicitly bounded by server-side limit. Detailed AI logs continue to use the existing paginated endpoint.

Likely Flyway V36 contains query-support indexes only, not new fact tables. Candidate composite indexes:

- requirement(project_id, assignee_id, status, updated_at)
- agent_run(project_id, created_at)
- pull_request(project_id, status, review_state, updated_at)
- ai_call_log(project_id, created_at)
- agent_finding(assignee_id, lifecycle_status, created_at) plus join through agent_run

Exact indexes must follow actual query plans and H2/PostgreSQL-compatible syntax.

## 5. Frontend product structure

Recommended final primary navigation:

1. 工作台 (`dashboard`)
2. 项目 (`projects`)
3. 研发任务 (`requirements`)
4. 代码仓库 (`repository`, includes PR)
5. 智能审查 (`agent`, includes Agent run + interactive review/report)
6. 质量中心 (`quality`)
7. 知识库 (`knowledge`)
8. 研发度量 (`metrics`, includes AI logs)

Recommended new modules:

- `composables/useWorkbench.js`
- `composables/useDevelopmentMetrics.js`
- `pages/InkKnowledgePage.vue`
- `pages/InkMetricsPage.vue`
- feature papers under `features/dashboard`, `features/metrics`, `features/knowledge`

Existing `useKnowledge`, `useAiLogs`, `usePullRequests`, `useReviews` and `useAgentWorkspace` remain data/action sources; migration must not duplicate their business logic.

## 6. Risks and gates

- Route consolidation must retain old URLs as redirects; external `#/agent?evidence=` links must still locate evidence.
- Workbench predicates must match detail-page predicates; shared backend query methods or projection services should be used instead of similar-looking duplicate filters.
- Dashboard must not call every list endpoint and join in the browser.
- Metrics labels must state window, sample count and unavailable states; zero is not the same as unavailable.
- Parsing historical `coverage_json` and Requirement report JSON must fail soft and expose excluded-record counts rather than fail the whole metrics endpoint.
- Deleting AppShell/legacy views is allowed only after router/import/reference tests prove they have no consumers.
- Full frontend QA still needs 390/768/1440 layouts, keyboard/focus, empty/error/permission/long-content and reduced-motion checks.
