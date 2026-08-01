# Design：前端重构 — 拆分 App.vue

## 目标结构

```text
frontend/src/
├─ main.js                    # createApp + router 挂载
├─ router.js                  # 新增:8 条路由 + 兜底重定向
├─ App.vue                    # ≤150 行:登录态分流(LoginView / AppShell + <router-view>)
├─ views/
│  ├─ LoginView.vue           # 原 AUTH 块
│  ├─ DashboardView.vue       # 概览(统计卡/可视化/最近报告)
│  ├─ ProjectsView.vue        # 项目 CRUD
│  ├─ RepositoryView.vue      # 仓库绑定 + commit + diff
│  ├─ PullRequestsView.vue    # PR 登记/列表/审查闭环/动作
│  ├─ KnowledgeView.vue       # 上传/文档/重建索引/检索测试
│  ├─ ReviewsView.vue         # 发起审查/任务/报告/问题/反馈(最大视图)
│  ├─ AgentView.vue           # Run 选择/工作台装配(内部继续用 components/agent/*)
│  └─ AiLogsView.vue          # 日志分组 + 详情
├─ components/
│  └─ AppShell.vue            # 新增:sidebar + topbar + toast + confirm modal + <slot>
├─ composables/               # 全部模块级单例,风格同 useSession
│  ├─ useBusy.js              # 共享 busy reactive + run(action,key) 错误包装(401 短路)
│  ├─ useConfirm.js           # confirmModal + ask()/confirmAction()
│  ├─ useProjects.js
│  ├─ useRepository.js        # commits/selectedCommit/diffFiles + demo 填充
│  ├─ usePullRequests.js
│  ├─ useKnowledge.js         # documents/上传/检索 + reviewDocs/prDocs 选择集
│  ├─ useReviews.js           # tasks/reports/reportDetail/mqLogs + 2.5s 完成轮询
│  ├─ useFeedback.js          # openFeedback/feedbackMap/draft/vote
│  ├─ useAgentWorkspace.js    # runs/timeline/findings/patch + SSE+轮询退避 + 取消/重试
│  ├─ useAiLogs.js            # aiLogs/scope/分组 computed/collapsedDates
│  └─ useWorkspace.js         # 跨域编排:refreshAll / resetForProject / logout 清理
└─ utils/labels.js            # statusLabel/prStateLabel/actionLabel/fbLabel/diffLines 等纯函数
```

## 关键决策

1. **状态共享 = 模块级单例 composable**（与 useSession 同构，其注释已预告此拆法）。跨视图共享的原子（commits、documents、tasks/reports、agent 状态、各表单）留在各自领域文件的模块作用域；视图组件只做装配与模板。不引入 Pinia——10 个域的规模下单例文件已足够，且零依赖。
2. **路由**：vue-router@4，`createWebHashHistory`。理由：生产由 Nginx 托管静态文件且已有 `#agent-evidence=` hash 约定，hash 模式无需改 Nginx 的 history fallback，风险最小。路由表：`#/dashboard` 等 8 条 + `/:pathMatch(.*)` → dashboard。`goTab(t)` → `router.push`；需项目的路由在视图内维持现有"disabled/空态"逻辑，不做强 guard（与现状一致）。
   - `#agent-evidence=` 锚点与 hash 路由并存：改造 `focusEvidenceAnchor` 读取 `route.hash` 或保留 query 形式 `#/agent?evidence=path:line`（实现时择一，验收标准是证据定位仍工作——它由 SCM 评论外链使用）。
3. **tab 活跃判断**：`useAgentWorkspace` 内 `tab.value !== 'agent'` → `router.currentRoute.value.name !== 'agent'`；轮询/SSE 生命周期逻辑原样迁移（含 15s 退避、300ms 防抖、onerror 交还轮询、run 切换时重开 SSE 的配对追踪注释）。
4. **busy/run**：单一共享 `busy` reactive 保持现状（模板大量 `busy.xxx` 引用），`run()` 进 useBusy，401 短路逻辑不变。
5. **跨域编排**：`resetReviewState`/`refreshAll`/`afterLogin`/`logout` 这些跨多域的函数进 `useWorkspace.js`，按依赖调用各域暴露的 `reset()`/`load()`；避免域与域互相 import（依赖方向：views → useWorkspace → 各域 → useSession/useBusy/useToast）。`setUnauthorizedHandler` 装配移到 App.vue onMounted（保持"只处理一次"语义）。
6. **模板迁移策略**：模板块逐 tab 原样搬运（含 class/结构零改动），只把 `tab === 'x'` 条件换成路由出口；styles.css 不动。这使 Step 3 美化的 diff 干净可审。

## 兼容与回滚

- 单独小步提交：router 骨架 → AppShell → 逐视图（每视图一提交，`npm test + build` 绿才进下一个）→ App.vue 收尾。任一步可 `git revert` 单点回滚。
- `npm audit` 在加 vue-router 后立即跑；被拦截即执行 PRD 的回退方案（保留 tab ref，不上路由，其余拆分不受影响）。

## 风险

- 最大风险是 SSE/轮询生命周期在迁移中丢失清理路径 → 用新增的 useAgentWorkspace 行为测试钉住（见 implement.md T7）。
- `reactive` 表单被 `Object.assign` 重置的引用语义：迁移时保持"同一实例上 assign"，不得替换对象引用（否则模板绑定断裂）。
