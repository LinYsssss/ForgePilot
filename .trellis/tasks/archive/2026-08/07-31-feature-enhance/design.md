# Design：功能增强 — backlog 收尾 + 对比审查

## A1 PR 行直达 Agent Run（纯前端）

`useWorkspace.openAgentRunForPr(pr)`：goto('agent') → `agent.loadAgentRuns()` → 在 runs 里按 `headSha === pr.headSha` 取最新一条；命中则设 `agentRunId` + `loadAgentWorkspace()`，未命中 toast「该 PR 尚无 Agent Run」并停在列表空态。PullRequestsView 的 PR 行加"Agent Run"小按钮（@click.stop）。

## A2 citation 定位与证据抽屉增强（纯前端）

前置阅读 AgentFindings.vue / PatchDiffViewer.vue 现状（已有 evidence-drawer details + data-evidence-path 锚 + ?evidence= 聚焦）。增强：
- 证据条目带 file:line 时点击 → 若 Patch diff 已渲染,滚动到对应行并高亮（PatchDiffViewer 行元素加 `data-diff-line`,AgentView 内提供 `locateDiffLine(path,line)`）;无 diff 时回退现有锚点聚焦。
- RAG 类证据（source#chunk）在抽屉里展示摘录全文（现有字段,若有截断样式优化）。
- 抽屉支持 Esc 关闭（details 元素 + keydown）。

## A3 长列表与键盘导航（纯前端,范围修正）

A4 分页落地后所有列表单页 ≤100 条,虚拟滚动失去前提（PRD 的 500 条验收随之修正为"分页 + 键盘导航"）。实现：commit 列表与 AI 日志列表容器支持 ↑/↓ 移动选中、Enter 确认（利用现有 .list-row button 天然可聚焦,加 @keydown 委托做 roving focus）。

## A4 分页收尾（后端 + 前端）

对 Track A 遗留三端点补 `PageResponse` 信封（复用 `sanitizePage/sanitizeSize`）：
1. `GET /agent-runs/project/{projectId}`（AgentRunController）
2. `GET /projects/{projectId}/agent-runs/{runId}/findings`
3. `GET /ai/logs`（现 limit≤200）
动手前先读控制器与现有测试;新形状用 `PageResponse.of(...)`,MockMvc 测试同步改并在 PaginatedEndpointsTest 补三项;确认三端点已在 ObjectLevelAuthorizationMatrixTest 覆盖（是既有 ID 端点,预期已覆盖,若缺则补）。前端：这三处 api 调用包 `unwrapPage`（其对裸数组/信封双兼容,可先发前端）;AI 日志页加「上一页/下一页」轻 pager（page/size state 进 useAiLogs）。

## B 对比审查（前端编排优先）

1. **幂等约束核查**（实现первым步）：ReviewTaskService 对同 commit 的幂等语义——若键仅 commitId,同一提交两次创建会返回同一任务,前端编排不可行 → 后端最小调整：幂等键纳入 documentIds 集合哈希（或提供 `compare=true` 逃生门）。读代码后择小者实现,新逻辑必须有测试。
2. 交互：ReviewsView「对比审查」按钮 → `useReviews.createCompareReview()`：取全部 INDEXED 文档 → 创建任务 A(全选) + 任务 B(空) → 轮询完成 → `comparePair` 状态记录两报告 id → 展示 `CompareView` 面板（Reviews 页内嵌区块,不新增路由）。
3. 对比算法（utils/compareReports.js 纯函数 + 单测）：Finding 归一化键 = `filePath + '::' + normalize(title)`（去空白/标点/大小写）;三桶：仅带知识库 / 共有 / 仅不带知识库。知识库信号高亮：issue 文本(description/evidence/suggestion)命中已上传文档文件名或 `BUG-\d+|INC-\d{4}-\d{2}` 模式。
4. 视觉：三列(或三区)布局,带知识库侧新增问题用 accent 高亮边;每条可展开看详情;顶部汇总(「知识库多发现 N 个问题,其中 M 条引用了文档」)。

## A5 质量门详情页

**明确降级为未实施**：评测数据无 API 面,本机语料跑分属 Docker 动态验收范畴,演示价值/成本比低。README/docs 不宣称。

## 验证

- 前端：npm test(新增 compareReports 与 openAgentRunForPr 相关单测) + build + audit。
- 后端：容器化 `mvn test`（含新分页/幂等测试）;yaml 未动无需校验。
- 端到端：本机 dev 起后端(H2 + mock AI + inline) + demo-repos 本地路径,实走对比审查并截图。

## 回滚

前端各项独立提交;后端分页一提交、幂等语义一提交,可单点 revert。
