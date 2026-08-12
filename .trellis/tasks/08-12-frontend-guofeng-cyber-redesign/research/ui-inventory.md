# UI inventory（规划基线）

## 技术基线

- Vue 3.5 + Vite 6 + Vue Router 4 + Element Plus 2.14。
- 状态主要由模块级 composable 管理，当前不引入 Pinia。
- `App.vue` 负责认证门禁；`AppShell.vue` 提供侧栏、顶栏、用户区和刷新入口。
- 现有 `tokens.css` 是 R6 “Precision Workbench”浅色青灰令牌系统，应作为迁移输入而非直接删除。
- 当前 960px 以下侧栏转为顶部导航；新方案需补齐 390/768/1440 三档行为合同。

## 页面与重构重点

| 页面 | 当前职责 | 重构重点 | 阶段 |
| --- | --- | --- | --- |
| Dashboard | 全局概览与指标 | 风险优先的叙事入口 | 扩面 |
| Projects | 项目列表与入口 | 密度、筛选、空/错状态 | 扩面 |
| Repository | 仓库/提交上下文 | 长内容、代码与元数据层级 | 扩面 |
| PullRequests | PR 工作流 | 状态、责任人与动作优先级 | 扩面 |
| Knowledge | 知识库管理 | 表单、检索、权限和长内容 | 扩面 |
| Reviews | 审查记录与报告 | finding 分组、证据与 Diff 可读性 | 纵向切片关联面 |
| Agent | Agent 运行工作台 | 时间线、风险、证据、操作与恢复 | **首个纵向切片** |
| AI Logs | 模型调用日志 | 技术密度、筛选和异常定位 | 扩面 |

## 共享能力

- 全局：AppShell、导航、用户芯片、刷新、认证门禁。
- Agent：阶段/时间线、任务摘要、状态与操作组件。
- 基础：Element Plus 表格、表单、对话框、通知、分页与图标能力。
- 数据：API utilities、认证与业务 composables；默认保持接口和数据形状不变。

## 已知缺口与风险

- 当前视觉偏标准企业管理台，品牌识别不足，无法表达 RepoSage 的“AI 审查/守门”性格。
- 文本式导航符号需替换为一致的 SVG 图标体系，并保持 label 与 focus-visible。
- 深色/半透明主题需重新验证代码、表格、弹层的对比度与层级。
- 鼠标跟随和虚化若跨入信息层，会损害 Diff 阅读、点击稳定性和低端设备性能。
- 本地 Node 24 不符合项目引擎约束；正式 QA 使用 Node 22。

## 状态覆盖

- 交互：default / hover / active / focus-visible / disabled / loading。
- 数据：empty / error / success / permission / offline / long-content。
- 环境：390 / 768 / 1440；mouse / keyboard / touch；normal / reduced motion；支持/不支持 backdrop-filter。

## Stage 1 待补证

- 用真实后端数据逐页记录内容密度、最长字段、分页和错误恢复。
- 补充当前界面截图、键盘路径、控制台/网络基线和关键布局尺寸。
- 确认 Agent → Reviews 主路径与审批动作是否满足现有权限和 API 合同。
