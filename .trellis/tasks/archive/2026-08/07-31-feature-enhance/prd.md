# PRD：功能增强 backlog 与对比审查

> 复杂任务（需 design.md + implement.md，进入实现前补齐）。父任务：07-31-tri-improve。
> 前置：07-31-frontend-split 完成（新功能落在拆分后的 views/composables 结构上）；其中后端分页项不依赖前端拆分，可穿插。

## 目标

完成项目文档中已规划未实现的功能 backlog，并新增"带/不带知识库对比审查"演示功能——把 RAG 的价值从"口头解释"变成"一眼可见"。

## 需求 A：文档既有 backlog

> 来源：《PR守门Agent前端优化方案.md》"后续迭代"（原文已归档至 docs/archive/，此处为无损转录）：
> 1. 已接入项目 Agent Run 列表、最新 head 默认选择和非终态自动刷新；后续从 PR 行直接打开对应 Run。
> 2. citation 点击定位 diff 行和 RAG 来源，增加 evidence drawer。
> 3. 增加 shadow/legacy/langchain4j 质量门详情页。
> 4. 长列表虚拟滚动、键盘导航和移动端布局。
> 另源：《TrackA交接说明.md》第六节遗留——Agent Run / Finding / AI Call Log 分页。

- A1 **PR 行直达 Agent Run**：PR 列表行提供"查看 Agent Run"入口，跳转 Agent 视图并按 PR/head SHA 预选中对应 Run；无对应 Run 时给出明确空态。
- A2 **citation 定位与证据抽屉**：Finding 的 citation 可点击 → 定位/高亮 diff 对应行；RAG 来源（source#chunk）在抽屉（drawer）中展示证据摘录、类型、置信贡献；抽屉支持键盘关闭。
- A3 **长列表虚拟滚动与键盘导航**：AI 日志与 commit 列表在数百条数据下滚动流畅（虚拟滚动或分页加载），列表支持上下键导航；移动端布局项与 07-31-frontend-visual 合并处理，不在本任务重复。
- A4 **分页收尾**（Track A 遗留，后端 + 前端）：Agent Run 列表、Finding 列表、AI Call Log 列表补 `PageResponse` 信封（复用 `sanitizePage/sanitizeSize`），前端适配分页 UI；行为与既有四个分页端点一致（默认 20、上限 100、越界空数组）。
- A5（可选，视数据可得性）**质量门详情页**：shadow/legacy/langchain4j 对比数据展示；若评测数据在本机不可得则明确降级为"未实施"，在 README/docs 如实标注。

## 需求 B：对比审查演示功能

- B1 审查页对同一 commit 支持一键发起"对比审查"：自动创建两个审查任务（关联全部已 INDEXED 知识文档 vs 不关联）。
- B2 两个任务完成后可进入"对比视图"：并排展示两份报告的 Finding；差异高亮：仅知识库侧发现的问题、双方共有问题、仅无知识库侧的问题。
- B3 知识库信号高亮：Finding 中引用了知识文档/历史事故（对应演示素材 B/C 类缺陷）的条目有显著视觉标识。
- B4 实现取向：优先前端编排（复用既有创建审查 API + 轻量对比视图读取两份报告）；仅当"报告未记录关联文档集合"导致无法区分两侧时，才考虑最小后端补充（新 Flyway 版本接在实测最大版本号后，不动已执行段）。

## 约束

- 新/改 ID 型端点必须纳入 ObjectLevelAuthorizationMatrixTest；错误响应遵循 ErrorCode + traceId；列表遵循 PageResponse 信封。
- 冻结契约与已执行 Flyway 迁移不可改。
- 新前端依赖须过 npm audit high + trivy 门禁（虚拟滚动优先手写或零依赖实现）。

## 验收标准

- [ ] A1：从 PR 行一键到达对应 Agent Run，联动状态正确；无 Run 有空态。
- [ ] A2：citation 点击可定位 diff 行；证据抽屉展示完整证据链信息。
- [ ] A3：AI 日志/commit 长列表（≥500 条模拟数据）滚动无明显卡顿；键盘可导航。
- [ ] A4：三个列表返回分页信封且前端正常分页；反向授权矩阵覆盖新端点；`mvn verify` 全绿。
- [ ] B：demo 仓库走一次对比审查，对比视图能直观呈现知识库带来的 Finding 差异（对照缺陷对照表 M1~M10 中 B/C 类命中）。
- [ ] 前端 `npm test && npm run build` 通过；A5 若未实施，文档如实标注。
