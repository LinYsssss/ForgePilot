# Implement：前端重构 — 拆分 App.vue

> 每个 T 一个提交；每步后跑 `cd frontend && npm test && npm run build`，绿了才继续。

## 检查清单

- [ ] T1 依赖与路由骨架：`npm i vue-router@4` → `npm audit --audit-level=high`（必须 0）；新增 `router.js`（8 路由 + 兜底）、main.js 挂载；App.vue 暂以 `tab` 同步路由（过渡态，行为不变）。
- [ ] T2 utils/labels.js + useBusy + useConfirm 抽出；App.vue 引用替换。
- [ ] T3 AppShell.vue（sidebar/topbar/toast/confirm modal）+ LoginView.vue；App.vue 分流装配。
- [ ] T4 低耦合视图：ProjectsView + useProjects；DashboardView；AiLogsView + useAiLogs。
- [ ] T5 中耦合视图：RepositoryView + useRepository；KnowledgeView + useKnowledge。
- [ ] T6 高耦合视图：ReviewsView + useReviews + useFeedback；PullRequestsView + usePullRequests；useWorkspace（refreshAll/reset/afterLogin/logout 编排）。
- [ ] T7 AgentView + useAgentWorkspace（SSE/轮询原样迁移）；`#agent-evidence=` 锚点适配 hash 路由并实测定位。
- [ ] T8 App.vue 收尾 ≤150 行；删除过渡态 tab 同步；全局搜索确认无残留 `tab.value`。
- [ ] T9 composable 行为测试 ≥6 个（useBusy 错误/401、useReviews 轮询启停、useAgentWorkspace SSE 互斥与清理、useKnowledge 选择集裁剪、useConfirm、labels 纯函数）。
- [ ] T10 手工走查 PRD 验收清单（dev server + 后端可用时全流程；后端不可用时至少登录页/路由/主题/构建产物预览），记录到 research/。

## 验证命令

```bash
cd frontend && npm test && npm run build && npm audit --audit-level=high
bash scripts/verify-local.sh --frontend-only
```

## 风险文件与回滚点

- 高危：App.vue（每步保持可构建）、useAgentWorkspace.js（SSE 生命周期）。
- 回滚：每 T 一提交，单点 revert；T1 审计不过 → 放弃 router（改动限 T1 revert）。
