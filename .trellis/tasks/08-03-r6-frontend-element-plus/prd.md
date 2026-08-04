# 前端美化：Element Plus + design tokens

> 用户已确认：**替换** `07-31-frontend-visual` 的 Observatory 手写视觉（aurora/玻璃拟态），不是叠加。单库 Element Plus，明确不引入 Naive UI、不混用。实施走 `ui-ux-promax` skill 的设计系统流程。前置 r4；与 r5 可并行。环境要求 Node 22（本机 Windows 需先从 24 切换）。

## Goal

21 个视图全部迁移到 Element Plus + 自定义 design tokens 的统一视觉体系：专业工作台风，不是"Element 默认蓝白模板脸"，任何时刻应用整体保持可演示。

## Requirements

### R1 设计系统先行（ui-ux-promax 流程，先做后迁）

- design tokens 一次定稿再开始迁页：主色/灰阶/语义色（成功/警告/危险映射到审查风险等级 NONE<LOW<MEDIUM<HIGH 四级，全站唯一映射）、圆角、间距尺、字号阶、阴影层级，落成 CSS 变量覆盖 `--el-*`。
- 暗色模式本期不做（范围控制），tokens 预留可扩展结构即可。
- 交互状态完备：loading / empty / error / disabled 四态在组件层统一，禁止各页自造。

### R2 依赖引入纪律

- 仅新增：`element-plus`、`@element-plus/icons-vue`（如需图表再议，且必须走 dataviz skill）。
- 过 `npm audit --audit-level=high` + trivy 门禁（r1 修复后该门禁已真实运行）；lockfile 保持官方源（r3 之后不得倒退回镜像源）。
- 按需导入（unplugin 自动导入或手动按需），构建产物体积前后对比留档，增幅超 300KB gzip 需说明。

### R3 逐页迁移（一页一提交）

- 迁移顺序按"演示动线优先"：登录 → 项目列表 → 仓库/提交 → 审查任务/报告（含 diff 定位与证据抽屉，最复杂，中段做熟手后攻）→ 对比审查 → PR 工作流 → Agent 工作台/时间线 → 日志页 → 设置/管理。
- 每页提交包含前后截图（存本任务目录 `screenshots/`），行为不变：既有 21 项测试全绿，页内交互（分页、筛选、SSE 时间线刷新）人工复核。
- Observatory 旧样式随页删除，迁完后全局清理残余样式文件与无用资源；期间新旧并存允许，但**单页内禁止新旧混搭**。

### R4 收尾

- `App.vue` / AppShell 层导航与布局统一为 Element Plus 容器组件。
- 响应式检查：1280/1536/1920 三档主流宽度不破版（移动端非目标）。
- 可达性底线：焦点可见、表单 label 关联、对比度过 AA（ui-ux-promax 流程内含）。

## Out of Scope

- 暗色模式；移动端适配；新功能页；后端接口任何改动；图表库引入（除非某页原本就有图表需要等价迁移）。

## Acceptance Criteria

- [ ] tokens 文件唯一且被全部页面消费，无页面级魔法色值（抽查 `grep -r "#[0-9a-fA-F]\{6\}" src/views` 仅允许 tokens 文件命中）。
- [ ] 21 个视图全部迁移，`screenshots/` 有每页前后对比。
- [ ] Observatory 样式资产全部移除（aurora/玻璃拟态相关样式文件零残留）。
- [ ] `npm test`（21 项）+ `npm run build` 绿；构建体积对比留档。
- [ ] `npm audit --audit-level=high` 零命中；CI 全绿。
- [ ] 部署服务器演示动线人工走查通过（风险等级色全站一致、四态完备、三档宽度不破版）。

## Validation

```bash
cd frontend && npm ci && npm test && npm run build
npm audit --audit-level=high
grep -rn "aurora\|glass" src/ --include=*.vue --include=*.css   # 期望零命中（迁移完成后）
```
