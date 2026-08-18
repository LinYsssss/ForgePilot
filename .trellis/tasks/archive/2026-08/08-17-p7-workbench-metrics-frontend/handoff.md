# P7 handoff — 2026-08-17 pause

## Current status

P7 production implementation is present in the shared worktree and the requested `pwsh scripts/verify-local.ps1 -SkipSmoke` run completed successfully. Per the user's pause instruction, no Trellis check, spec promotion, commit, push, archive, or further implementation was started afterward.

## Delivered

### Backend

- New `com.example.codereview.dashboard` domain:
  - `DashboardController`
  - `DashboardService`
  - `DashboardQueryRepository`
  - `DashboardDtos`
  - `MetricsWindow`
- Added authorized endpoints:
  - `GET /api/projects/{projectId}/workbench?limit=6`
  - `GET /api/projects/{projectId}/metrics?window=30d`
- Workbench queues use bounded server-side projections for assigned Requirements, assigned Findings, and role-honest pending PRs.
- Metrics include development quality, requirement quality, processing efficiency, and AI facts from `ai_call_log`.
- Historical coverage/report JSON parsing is fail-soft and reports excluded records.
- Duration and AI distributions are bounded by `app.metrics.max-samples`, with percentile and truncation metadata.
- Added V36 additive composite indexes.
- Added `DashboardServiceTest` for role behavior, window validation, fail-soft parsing, percentiles, and truncation.

### Frontend

- Dashboard now consumes the workbench endpoint; it no longer joins existing full lists in the browser.
- Added Metrics page/composable with 7d/30d/90d windows and embedded task-scoped AI logs.
- Migrated Knowledge to an Ink page with LEADER/DEVELOPER upload and LEADER-only reindex/delete controls matching backend policy.
- Consolidated Pull Requests into `/repository?section=pull-requests`.
- Consolidated Agent Runs and interactive Reviews/Reports into `/agent` sections while preserving Agent SSE/polling and Review polling ownership.
- Final navigation is exactly eight zones.
- Compatibility routes preserve relevant query parameters:
  - `/pull-requests` → `/repository?section=pull-requests`
  - `/reviews` → `/agent?section=reviews`
  - `/ai-logs` → `/metrics?section=ai`
  - `/ink` → `/dashboard`
  - historical `agent-evidence` → canonical `/agent` evidence query
- Removed routed legacy views plus `AppShell`, `LoginView`, and the temporary `InkAtelierPage` after zero-reference tests.
- Added P7 route/reset/target/metrics/legacy-removal tests.
- Browser QA found and fixed one mobile issue: flex shrink reduced the nav button below 44px; it now measures exactly 44×44px.

## Validation results

- `backend/mvn -s .mvn/settings.xml verify`: **PASS**
  - 720 tests passed, 6 skipped, 0 failures/errors.
- `frontend/npm test`: **PASS**
  - 83 tests passed, 0 failed.
- `frontend/npm run build`: **PASS**
  - CSS 269.00 kB (39.24 kB gzip)
  - JS 628.78 kB (217.79 kB gzip)
  - Existing Rollup warning: main chunk exceeds 500 kB.
- `pwsh scripts/verify-local.ps1 -SkipSmoke`: **PASS**
  - Backend tests PASS (211s)
  - Frontend tests PASS (5s)
  - Frontend build PASS (15s)
  - Backend smoke skipped by request
  - Docker availability skipped because `docker` command is not installed
- Browser QA: **PASS** at 390×844, 768×1024, 1440×1000.
  - No page-level horizontal overflow in the tested P7 pages.
  - Clean final compatibility-route sweep: no console warnings/errors or page errors.
  - Keyboard first Tab reaches the visible skip link with focus outline.
  - Reduced-motion stabilization leaves no persistent running animations.
  - QA screenshots: `research/qa/*.png`.
- Temporary files removed: Vite PID/logs, test-output logs, backend verify log.

## Main changed path groups

- Backend production: `backend/src/main/java/com/example/codereview/dashboard/`
- Backend config/migration: `backend/src/main/resources/config/app-agent.yml`, `backend/src/main/resources/db/migration/V36__dashboard_metrics_indexes.sql`
- Backend test: `backend/src/test/java/com/example/codereview/dashboard/DashboardServiceTest.java`
- Frontend routes/lifecycle: `frontend/src/router.js`, `frontend/src/App.vue`, `frontend/src/composables/useWorkspace.js`, `useAgentWorkspace.js`, `useAiLogs.js`
- New frontend state: `useWorkbench.js`, `useDevelopmentMetrics.js`
- New/updated Ink pages: Dashboard, Repository, Agent, Knowledge, Metrics, Requirements, Quality
- New feature surfaces: dashboard queues/targets, metrics cards/logs/model, Knowledge paper, PR paper, Reviews paper
- Shell/nav: `features/shell/inkNav.js`, `useInkNavigation.js`, `shared/theme/ink-base.css`
- Removed legacy shell/views: `AppShell.vue`, `LoginView.vue`, `AgentView.vue`, `ReviewsView.vue`, `PullRequestsView.vue`, `KnowledgeView.vue`, `AiLogsView.vue`, `InkAtelierPage.vue`
- Frontend tests: `ink.test.mjs`, `ink-workspace.test.mjs`, `smoke.test.mjs`, `p7.test.mjs`
- QA/progress: P7 research assets and the existing frontend redesign implement/QA report.

## Preserve unrelated work

The worktree also contains pre-existing/unrelated changes under `.agents/`, `.codex/`, `.trellis/.template-hashes.json`, the parent task, and P8/P9 task directories. Do not revert, clean, or include/exclude these casually; inspect ownership before any commit.

## Remaining work for tomorrow

1. Main session dispatches the required Trellis full-scope check; verify every finding against actual code before changing anything.
2. Address confirmed check findings only, then rerun affected focused tests.
3. Run final `git diff --check` after any follow-up edits. (It passed earlier in the implementation, but the final pause occurred after later QA/documentation edits.)
4. Consider adding a direct H2/MockMvc happy-path test for the raw JDBC workbench/metrics queries; current P7 backend coverage is service-level plus the repository-wide Spring/Flyway verify suite.
5. Promote stable backend/frontend contracts through `trellis-update-spec` from the main session.
6. Review the existing >500 kB bundle advisory; it is non-blocking for P7 but remains a performance follow-up.
7. Commit intentionally, update journal, archive P7, and run `/trellis:finish-work` only after user approval.

## Do not do tonight

- Do not run Trellis check/spec update/commit/archive automatically.
- Do not modify P8/P9 or unrelated generated Trellis/Codex files.
- Do not reintroduce `/ink` as a second workbench or restore the legacy shell.
