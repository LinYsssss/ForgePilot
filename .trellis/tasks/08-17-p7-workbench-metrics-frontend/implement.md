# P7 工作台、研发度量与前端收尾 — Implementation Plan

> 生产实现已完成并通过本轮质量命令；按用户要求于 2026-08-17 暂停，等待主会话执行 Trellis check、spec promotion、commit/archive。

## Phase A — Backend workbench projection

- [x] 新建 dashboard DTO/Controller/Service 与受限 projection queries。
- [x] 实现我的 Requirement、我的 Finding、角色真实的待审 PR 三队列。
- [x] 实现风险摘要与 typed recent activity。
- [x] 加入 limit 校验、项目 read 授权与对象归属测试。
- [x] 前端新增 useWorkbench、工作台数据态和详情定位 query。
- [x] 重构 DashboardPaper 为三队列 + 风险 + 最近活动。

**Gate A：** 工作台每条数据可在对应详情页用相同谓词定位，DEVELOPER 不显示伪造“待我审查”数据。

## Phase B — Backend metrics and `/metrics`

- [x] 实现 `7d/30d/90d` window enum 和 metrics response metadata。
- [x] 实现研发质量、需求质量、处理效率、AI 四组聚合。
- [x] 为 coverage/report JSON 实现 fail-soft parser 与 excluded count。
- [x] 实现 duration percentile + max-sample truncation。
- [x] 根据最终查询增加 V36 非重复复合索引与迁移测试。
- [x] 新增 useDevelopmentMetrics 与 InkMetricsPage 四区展示。
- [x] 将 useAiLogs 日志浏览器嵌入 AI section，支持 taskId 深链。

**Gate B：** 四组指标在固定窗口下可解释、可抽查，零/无样本/排除/截断状态不混淆。

## Phase C — Knowledge and AI Logs route migration

- [x] 创建 InkKnowledgePage/KnowledgePaper，复用 useKnowledge 全部动作。
- [x] 对齐上传、重建、删除、搜索的后端角色权限。
- [x] 将 `/knowledge` 切到 Ink shell，补状态与响应式测试。
- [x] `/ai-logs` 改为 `/metrics?section=ai` 兼容跳转并保留 taskId。
- [x] 更新 openProjectAiLogs/openTaskAiLogs 到 canonical metrics 路由。

**Gate C：** Knowledge 功能零损失，Review/Agent 打开的 task AI 日志仍定位正确。

## Phase D — Repository + Pull Request consolidation

- [x] 给 InkRepositoryPage 增 URL-synced commits/pull-requests section。
- [x] 从旧 PullRequestsView 提取展示层，复用 usePullRequests 与现有动作/确认流程。
- [x] 支持 pullRequestId 定位、工作台跳转和 back/forward。
- [x] `/pull-requests` 改兼容跳转并保留相关 query。
- [x] 通过后删除无引用 PullRequestsView。

**Gate D：** 仓库提交/Diff 与 PR 工作流同页可用，旧 URL 和动作语义不回归。

## Phase E — Agent + Reviews consolidation and strict `/ink` normalization

- [x] 将现有 InkAtelierPage 正式迁/重命名为 canonical `/agent` 智能审查页。
- [x] 导航 active key 与 useAgentWorkspace 活跃谓词切换为 `agent`。
- [x] 增 agent/reviews section，迁移 ReviewsView 的任务、报告、coverage 和知识选择能力。
- [x] 支持 runId/evidence/reviewTaskId/reportId query 定位。
- [x] `/reviews` 跳 `/agent?section=reviews`；`/ink` 严格按原计划跳 `/dashboard`。
- [x] 验证历史 agent-evidence 深链。
- [x] 通过后删除无引用 AgentView/ReviewsView。

**Gate E：** Agent SSE/轮询、Review 轮询、报告/coverage、Evidence 定位和旧 URL 全部正常。

## Phase F — Eight-zone nav and legacy-shell retirement

- [x] 将 INK_NAV 收敛为八区唯一真源，移除 atelier/pullRequests/reviews/aiLogs 重复项。
- [x] 所有 canonical 路由标记 Ink shell。
- [x] 删除无引用 KnowledgeView/AiLogsView 及其重复表现层。
- [x] 证明旧壳无路由消费者后删除 AppShell/LoginView 和 App.vue 旧分支。
- [x] 搜索并清理重复 CSS/组件；共享业务组件保留或迁位。
- [x] 更新 frontend redesign 任务的 UI inventory/QA 进度，避免两个 Trellis 任务对同一页面给出矛盾状态。

**Gate F：** 全站只有一套壳层、一份导航和一份业务状态逻辑，所有旧 URL 可达但不维护旧页面。

## Phase G — Full quality gate and finish

- [x] Backend focused tests + `mvn -s .mvn/settings.xml verify`。
- [x] Frontend `npm test` + `npm run build`。
- [x] `pwsh scripts/verify-local.ps1 -SkipSmoke`。（2026-08-17：Backend tests PASS 211s；Frontend tests PASS 5s；Frontend build PASS 15s；Backend smoke 按 `-SkipSmoke` 跳过；Docker command 不可用而跳过。）
- [x] Browser QA：390/768/1440、键盘、焦点、触控、reduced motion、旧 URL/deep links、console/network/overflow。
- [x] 记录 QA 证据到 P7 research 或既有 frontend redesign `research/qa-report.md`，不建立两份相互冲突的报告。
- [x] Trellis full-scope check，修复确认 finding。（2026-08-18：授权矩阵、H2 raw JDBC 可移植性、项目切换旧响应与 AI 日志 project/task 边界。）
- [x] 更新稳定 backend/frontend specs。（新增 `backend/dashboard-projection-contracts.md`，并补充 frontend project-scoped async loader 规则。）
- [ ] 提交、归档 P7、记录 journal。

## Risky files / rollback points

- `frontend/src/router.js`、`inkNav.js`、`App.vue`：路由/壳层变更最后合并，先保证兼容重定向测试。
- `useAgentWorkspace.js`：SSE/poll 生命周期只能换 active route predicate，禁止重写机制。
- `useWorkspace.js`：新增 workbench/metrics reset 和 canonical links 时保持原 reset 顺序。
- `ContextBuilder`、Gate/Finding/Requirement 状态机：P7 只读，不修改领域语义。
- metrics JSON parsing：历史坏数据只能计 excluded，不能把异常吞成零，也不能 500。
- old view deletion：每个迁移页面验证完成后再删除；Phase F 前保留 rollback 点。

## Validation commands

```powershell
cd backend
mvn -s .mvn/settings.xml verify

cd ../frontend
npm test
npm run build

cd ..
pwsh scripts/verify-local.ps1 -SkipSmoke
git diff --check
```

## Explicitly forbidden

- 不新增 PR reviewer assignment。
- 不把 Requirement.updatedAt 称为交付周期。
- 不发明 Requirement 综合分。
- 不在前端 join 全量列表生成工作台/指标。
- 不查询 Prometheus 作为 UI 第二事实源。
- 不保留 `/ink` 作为第二工作台；严格跳转 `/dashboard`。
- 不在业务页面复制 navigation、HTTP、401/CSRF 或领域状态逻辑。


## 2026-08-17 pause checkpoint

- Backend full verify: **PASS** — 720 tests, 0 failures/errors, 6 skipped.
- Frontend tests: **PASS** — 83 tests, 0 failures.
- Frontend production build: **PASS** — existing Rollup advisory remains for the 628.78 kB main chunk (>500 kB).
- Local verification: **PASS** for every requested non-smoke gate; backend smoke was intentionally skipped and Docker was unavailable.
- Browser QA: **PASS** at 390/768/1440 with canonical and compatibility routes, query/deep-link preservation, keyboard focus, 44×44 mobile nav target, reduced motion, console/page-error and page-level overflow checks. Evidence is under `research/qa/` and the shared frontend redesign QA report.
- Temporary Vite/test output files were removed before the pause.
- Not run by this implementer after the pause request: Trellis check, spec updates, commit, archive, journal.


## 2026-08-18 quality follow-up

- Trellis full-scope check completed. Confirmed and fixed:
  - Added `workbench`/`metrics` stranger, anonymous, and owner happy-path cases to `ObjectLevelAuthorizationMatrixTest`.
  - Cast enum/check-constrained status columns to `varchar` in mixed raw JDBC `COALESCE`/`UNION` expressions; the owner MockMvc path exposed an H2-only 500 that PostgreSQL-shaped service tests missed.
  - Added project id to task-scoped AI-log requests, reset AI logs on project switch, and guarded workbench/metrics/AI-log loaders against stale project responses/errors/finally blocks.
- Added regression coverage in `frontend/tests/composables.test.mjs`; frontend suite now passes 84 tests.
- Focused backend check passes 44 tests (0 failures/errors), including the new raw JDBC owner paths.
- `pwsh scripts/verify-local.ps1 -SkipSmoke` passes on 2026-08-18; Docker remains skipped because the command is unavailable.
- `git diff --check` passes. Commit, archive, and journal remain gated on user confirmation.
