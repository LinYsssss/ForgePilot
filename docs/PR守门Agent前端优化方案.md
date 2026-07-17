# PR 守门 Agent 前端优化方案

## 目标

让审查者在一个工作区内完成 Agent Run 状态判断、证据核验、Patch 验证状态查看和人工审批；所有展示都以当前 head SHA、持久化 Finding/Evidence 和 Patch approval 状态为准，不在前端推断安全结论。

## 第一批落地

- 增加 Run 阶段、完成步骤、Finding 数、Patch 状态和 head SHA 摘要。
- Timeline 使用可读状态、失败/等待/成功视觉层级和输出摘要。
- Finding 展示严重性、置信度、证据数量和 citation；无证据时明确不可阻断。
- Patch 审批继续由当前 head、apply、build、test、scan 和目标消失校验共同约束。
- 保持现有 API、人工审批和 SCM 发布安全边界。

## 后续迭代

1. 已接入项目 Agent Run 列表、最新 head 默认选择和非终态自动刷新；后续从 PR 行直接打开对应 Run。
2. citation 点击定位 diff 行和 RAG 来源，增加 evidence drawer。
3. 增加 shadow/legacy/langchain4j 质量门详情页。
4. 长列表虚拟滚动、键盘导航和移动端布局。

## 验收

- 前端测试与生产构建通过。
- 不可批准条件由前后端双重维护。
- 浏览器不执行模型、工具、Sandbox 或 SCM 发布操作。
