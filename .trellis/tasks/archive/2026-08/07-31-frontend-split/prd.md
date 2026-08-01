# PRD：前端重构 — 拆分 App.vue

> 复杂任务（prd + design + implement）。父任务：07-31-tri-improve。前置：07-31-cleanup-baseline。

## 目标

把 `frontend/src/App.vue`（1540 行：8 个 tab 的模板 + 约 860 行集中式脚本）拆分为可维护的 views / 领域 composables / 壳组件结构，并引入 vue-router；**用户可见行为完全不变**。这是后续美化（07-31-frontend-visual）与功能增强（07-31-feature-enhance）的公共前置。

## 需求

1. **视图拆分**：`src/views/` 下 9 个视图（Login、Dashboard、Projects、Repository、PullRequests、Knowledge、Reviews、Agent、AiLogs）+ `AppShell`（侧边栏/顶栏/toast/confirm 模态）。App.vue 收敛为壳装配（目标 ≤150 行）。
2. **领域 composable**：`src/composables/` 新增 useProjects、useRepository、usePullRequests、useKnowledge、useReviews（含完成轮询）、useFeedback、useAgentWorkspace（SSE + 轮询退避 + 取消/重试）、useAiLogs、useConfirm、useBusy（共享 busy + run 错误包装）。沿用现有 useSession/useToast/useTheme 的**模块级单例**风格（跨视图共享状态：commits/documents/tasks/reports 被多个视图消费）。不引入 Pinia。
3. **引入 vue-router**：8 个 tab → 路由（如 `/dashboard`、`/projects`、`/repository`…），刷新保持页面、可分享链接；跨视图跳转（选项目→仓库页、选提交发起审查→审查页、打开报告→审查页、AI 日志入口）改为 `router.push`；SSE/轮询的"仅 agent 页活跃"判断改用当前路由。登录态守卫沿用现有 authenticated 判断（未登录渲染 LoginView，不做复杂 guard）。
4. **行为不变清单**（迁移时逐项保真）：401 全局失效只处理一次；`resetReviewState` 的跨域清理顺序与范围；SSE 具名事件监听（agent-run/agent-step）+ 300ms 防抖 + 15s 内轮询退避 + onerror 立即交还轮询；审查完成轮询 2.5s；`repoForm._bound/_tokenConfigured` 内部标志不外发；`#agent-evidence=` hash 定位；CSRF 引导先于 loadMe；分页 `unwrapPage` 适配。
5. **测试加强**（P1-14 残留）：为 useReviews（轮询启停）、useAgentWorkspace（SSE/轮询互斥与清理）、useBusy（错误→toast、401 短路）等关键 composable 写行为测试（node:test + 轻量 DOM/EventSource stub）；保留现有 smoke 测试。
6. **依赖门禁**：新增 vue-router@4 后 `npm audit --audit-level=high` 必须为 0；若意外拦截则回退 tab 状态方案（不引入 router）。

## 验收标准

- [ ] App.vue ≤150 行；9 个视图 + AppShell 落位；无遗留死代码。
- [ ] 全部页面与交互和拆分前一致（手工走查清单：登录→项目 CRUD→仓库绑定/commit/diff→PR 登记/审查/动作→知识库上传/检索/重建→审查创建/轮询/报告/反馈/导出/删除→Agent 加载/SSE 刷新/取消/重试→AI 日志分组/详情→主题切换→退出）。
- [ ] 刷新浏览器停留在当前路由；跨视图跳转全部经 router。
- [ ] `npm test` 通过且新增 ≥6 个 composable 行为测试；`npm run build` 通过；`npm audit --audit-level=high` 0 告警。
- [ ] SSE / 轮询在切换项目、退出登录、组件卸载时全部正确清理（无泄漏定时器/连接）。

## 不做

- 不改视觉样式（styles.css 仅允许因结构调整的选择器微调）——美化在 07-31-frontend-visual。
- 不引入 Pinia / TypeScript；不改后端。
