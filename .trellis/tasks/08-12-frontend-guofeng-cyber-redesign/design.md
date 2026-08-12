# Design：古风赛博前端重构技术方案（规划版）

> 本文定义实施边界与架构。视觉令牌、组件规格和动效细节在用户选择方向并批准原型后写入 `research/ui-design.md`；此前不得批量改写页面。

## 迁移策略

采用“保留业务骨架、重建表现层、纵向切片先行”的渐进迁移：

1. 保留 Vue Router、认证门禁、API utilities、composables 与现有接口数据形状。
2. 保留 Element Plus 作为基础交互与无障碍能力，通过语义令牌和薄封装统一外观。
3. 在新主题根节点下逐步迁移，避免一次性重写八个页面。
4. 首个纵向切片选择 Agent 工作台，并连通 Reviews 的证据/Diff 与复核动作。
5. 切片通过 Stage 7 子集后，按共享壳层 → 高价值页面 → 管理/日志页面扩面。

## 表现层分层

```text
AppShell
├─ AmbientScene（纯装饰，pointer-events:none，可完全关闭）
│  ├─ far field：雾/星图/纹理
│  ├─ mid field：线路/光晕
│  └─ near accent：局部印记/刻度
├─ NavigationSurface（稳定交互层）
└─ WorkspaceSurface（稳定内容层）
   ├─ context / filters
   ├─ primary review plane
   └─ evidence / action rail
```

- `AmbientScene` 不捕获点击、不影响布局。
- `WorkspaceSurface` 使用近实色背景或低透明表面；代码、正文、表格、表单不使用动态 blur。
- z-index、模糊半径、透明度与状态光来自设计令牌，禁止页面自定义魔法值。

## 鼠标与动效架构

- AppShell 维护单一 pointer observer，通过 `requestAnimationFrame` 写入归一化 CSS 变量 `--pointer-x/y`。
- 只有 AmbientScene 消费指针变量；组件 hover/press 使用局部 CSS 状态，不各自注册全局 mousemove。
- 页面隐藏、窗口失焦、reduced motion 或 coarse pointer 时停止 observer 并归零为静态构图。
- 默认使用 CSS transform/opacity；复杂编排依赖需先记录体积、回退与维护成本。
- 每项动效规格包含 purpose、trigger、duration、easing、interruption、reduced-motion fallback。

## 令牌与组件边界

- 延续 primitive → semantic → component 三层令牌，迁移现有 `tokens.css`，不在页面散落主题常量。
- 语义令牌至少覆盖 canvas/surface/elevated、text tiers、border、focus、risk levels、状态色、ambient glow、blur、shadow、motion duration/easing。
- 优先建立 AppShell/NavItem、WorkspacePanel、RiskBadge、MetricCard、Timeline/StageRail、FindingCard、EvidenceViewer、ActionBar 与通用状态组件。
- Element Plus 覆盖集中在主题入口；业务组件禁止依赖其内部 DOM 选择器。

## 响应式策略

- 1440px：三栏工作台，核心审查面拥有最大宽度和稳定阅读行长。
- 768px：双栏或主栏 + 抽屉，次级观测信息可收起但状态摘要常驻。
- 390px：单一任务流；主操作处于安全区，导航、证据和时间线通过语义明确的抽屉/分段进入。
- 断点由内容压力决定，最终值在原型测量后冻结。

## 风险与回退

- 深色主题逐页测量正文、弱文本、边框、代码高亮和状态色对比。
- `backdrop-filter` 提供近实色 fallback，确保禁用时层级仍成立。
- 动效记录无动效基线、正常模式与降级模式；避免连续 layout/paint 热点。
- 实施前建立独立 `codex/frontend-guofeng-cyber-redesign` 分支/工作树；本规划提交仅包含任务文档。
- CI 与正式验收固定 Node 22；Node 24 探索结果不作为验收证据。

## 决策门

- Gate A：用户选择 A/B/C 方向。
- Gate B：Agent 工作台桌面与移动原型获批。
- Gate C：`research/ui-design.md` 足够让另一个实现者无需发明视觉规则。
- Gate D：纵向切片行为、无障碍、响应式与性能证据通过，再批准扩面。
