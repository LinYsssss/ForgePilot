# Design：前端 Element Plus 迁移

## 主题策略（避免"Element 默认脸"的具体做法）

- 全局覆盖 `--el-color-primary` 等品牌变量 + `--el-border-radius-*` + 字号阶，收敛在单一 `tokens.css`；组件级微调用 Element 官方 CSS 变量接口，禁止 `::v-deep` 硬改内部结构（升级即碎）。
- 风险等级色是本产品的核心语义色：NONE/LOW/MEDIUM/HIGH 四级映射到 info/success 之外的自定义梯度，全站唯一定义在 tokens，报告页/列表徽标/对比视图/Agent 时间线共用。
- 引入方式：unplugin-vue-components + unplugin-auto-import 按需（构建体积可控、模板免手动 import）。

## 迁移模式（每页固定四步）

1. 结构映射：现有手写布局 → Element 容器（`el-container/el-card/el-table/el-form/el-drawer/el-timeline`），列表统一 `el-table` + `el-pagination`（对接现有分页信封 默认20/上限100）。
2. 状态四态：`v-loading` / `el-empty` / `el-alert`(error) / disabled，删除页内自造实现。
3. 删除该页 Observatory 样式与无用类名。
4. 截图对比 + `npm test` + 人工交互复核 → 单页提交。

## 特殊页面处理

- **报告页 diff 定位与证据抽屉**：最复杂交互，diff 渲染保持现有实现（非 Element 职责），外壳换 `el-drawer`/`el-descriptions`；citation 行锚定逻辑不动。
- **Agent 时间线（SSE）**：`el-timeline` 外壳，SSE 刷新逻辑不动；注意 loading 态与流式追加的并存表现。
- **对比审查三栏**：`el-row/el-col` 栅格重排，信号徽标用 `el-badge/el-tag`。

## 依赖与体积

新增仅 `element-plus`、`@element-plus/icons-vue`、两个 unplugin（devDeps）。基线体积 202.52 kB（审计实测），迁移后 gzip 增幅预算 300KB 内，超出需在任务档案说明。

## 回滚

单页提交粒度，任何页面出问题 revert 该页提交即回退到 Observatory 版本（旧样式随页删除，revert 自动恢复）。
