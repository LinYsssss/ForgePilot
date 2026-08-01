# PRD：前端美化 — Observatory 升级 + 现代创意视觉

> 复杂任务。父任务：07-31-tri-improve。前置：07-31-frontend-split（已完成归档）。
> 用户取向：保持 Observatory 设计语言为底座，叠加"好看、有创意、紧跟当下趋势"的视觉，目的是**加分**（演示观感权重高）。

## 目标

在不改任何业务行为的前提下，把界面观感从"整洁可用"提升到"令人记住"：进场即有氛围（aurora + 玻璃质感），交互处处有回应（微交互/动效），数据页有仪器感（图表/diff/时间线质感），双主题与窄屏同等成立。

## 需求

- V1 **氛围层**：aurora / gradient-mesh 动态背景（纯 CSS，慢速微动、低对比不抢内容）；侧边栏/顶栏/模态玻璃拟态（backdrop-blur + 半透明 surface）；面板 hover 时 border-glow 微亮。
- V2 **微交互**：按钮/卡片 hover 抬升与按压反馈（用现有 `--spring`/`--ease-out` token）；路由切换过渡（fade+slide，`<router-view>` transition）；Dashboard 统计数字滚动动画；骨架屏统一（现 `.skeleton` 仅个别页有，扩展到列表加载态）；toast 升级（图标 + 自动消失进度条）。
- V3 **数据可视化升级**（DashboardViz 打磨非重写）：环形图入场 draw-in 动画 + 渐变描边；活动柱升级为渐变面积/柱混合并带入场动画；新增"问题严重度构成"横向堆叠条。
- V4 **明细质感**：diff 查看器（行号列、行 hover、文件头 sticky、增删色带更清晰）；报告问题卡片视觉分层（严重度色带 + 卡片内层次）；置信度条动画填充；Agent Timeline 节点连线视觉升级。
- V5 **一致性/响应式/可达性**：≤900px 窄屏布局（侧边栏折叠为图标栏或顶部条）；亮色主题逐项核对（所有新效果双主题成立）；`prefers-reduced-motion` 全局尊重（动画降级为直达终态）；focus-visible 环统一。

## 约束

- 零新依赖（纯 CSS/SVG/Vue transition；不引入动画库/图表库）。
- 行为不变：不改 composable/API/路由逻辑；组件只动模板与样式层。
- 所有颜色经由 token（styles.css 变量），不出现裸色值散落组件内（组件内允许引用 var()）。

## 验收标准

- [ ] `npm test && npm run build` 通过；`npm audit --audit-level=high` 仍 0。
- [ ] Playwright 走查（复用 Step 2 脚本）零页面错误；截图（暗/亮 × 桌面/900px 窄屏）落 research/，与升级前对比有明显观感提升。
- [ ] 双主题下无对比度/可读性回退（人工核验截图）。
- [ ] `prefers-reduced-motion: reduce` 下无持续动画（代码审查 + CSS media 检查）。
- [ ] 行为回归：走查脚本全流程通过（登录/路由/主题/退出）。

## 不做

- 不改品牌色体系与整体布局结构；不做移动端专属交互（触控手势等）；不引入组件库/图表库。
