# Design：前端美化 — Observatory 升级

## 分层落法（全部零依赖）

1. **styles.css 扩展**（主战场，预计 +400~600 行，按区块注释组织）：
   - `:root` 增 token：`--glass-bg`/`--glass-border`/`--blur`（双主题各自定义）、`--grad-accent`（渐变）、aurora 色组。
   - `body::before/::after` aurora 层：2~3 个大半径 radial-gradient 斑块 + `@keyframes aurora-drift`（120s 级慢速位移/色相微调，opacity 0.5 以下）；`prefers-reduced-motion` 下静止。
   - `.sidebar`/`.topbar`/`.modal`：`background: var(--glass-bg); backdrop-filter: blur(var(--blur)); border: 1px solid var(--glass-border)`；注意亮色主题的透明度需单独调。
   - `.panel:hover` border-glow：`box-shadow` 过渡 + `border-color` 提亮（仅 hover-capable 设备,`@media (hover:hover)`）。
   - 按钮/卡片微交互：`transform: translateY(-1px)` hover、`scale(0.98)` active、统一 `transition: var(--t-fast) var(--ease-out)`。
   - `.toast` 重设计：图标伪元素 + 底部进度条动画（3.2s 与 useToast 时长一致,动画纯视觉不控制逻辑）。
   - 骨架屏 `.sk-row` 微光扫过动画统一化。
   - focus-visible 统一环：`:focus-visible { outline: 2px solid var(--accent-ring) }`。
   - 窄屏：`@media (max-width: 900px)` 侧边栏收成 64px 图标栏（文字隐藏,brand 只留 logo,user-chip 只留头像）；`.grid.three/four` 降列。
   - `@media (prefers-reduced-motion: reduce)`：关 aurora/数字滚动交给 CSS 侧的都停,JS 侧动画读取 matchMedia 直达终态。
2. **路由过渡**：App.vue `<router-view v-slot="{ Component }"><transition name="page" mode="out-in"><component :is="Component" /></transition></router-view>`；`.page-enter/leave` 用 opacity+8px 位移，时长 `--t-base`。
3. **数字滚动**：新增 `src/utils/useCountUp.js`（小 composable：watch 目标值,rAF 缓动到位,reduced-motion 直达）；DashboardStats 使用。
4. **DashboardViz 升级**：donut `stroke-dashoffset` 入场动画（CSS transition,mounted 后置位）；SVG `<defs><linearGradient>` 渐变描边；活动柱入场 scaleY 动画（per-index delay）；新增严重度堆叠条（纯 div flex,数据从 reports.issueCount 现有字段来——注意:报告列表只有 issueCount 无严重度构成,则堆叠条改为"报告风险构成"复用 riskDistribution,不造假数据）。
5. **diff 查看器**：RepositoryView 与 PatchDiffViewer 的 `.diff-body` 增行号列（CSS counter 或模板 index）、`.diff-line:hover` 高亮、`.diff-file-head` sticky;只动模板结构与样式,不动 diffLines 逻辑。
6. **报告问题卡片**：`.issue` 已有 sevbar-* 色带,增强层次(标题区/正文区分隔、hover 微抬、置信度条 `width` transition 动画由 0→值)。
7. **Agent Timeline**：`.timeline` 节点圆点 + 连线渐变、当前活跃步骤 pulse（reduced-motion 静止）。

## 主题与回退

- 所有新 token 在 dark/light 两个块各给值；backdrop-filter 无支持时（旧内核）玻璃层回退为实色 surface（`@supports not (backdrop-filter: blur(1px))`）。
- 动画只做 opacity/transform/box-shadow（合成层友好），不动 layout 属性。

## 验证

- 每个大块落地后 build + 截图对比；最终复用 walkthrough 脚本 + 双主题双视口截图矩阵（4 组）。

## 回滚

- styles.css 区块化追加 + 组件模板小改，每块独立提交可单点 revert。
