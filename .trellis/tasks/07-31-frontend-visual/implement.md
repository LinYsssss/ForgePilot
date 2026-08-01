# Implement：前端美化 — Observatory 升级

> 每 V 一提交；每步 `npm run build` + 关键页截图；最终走查矩阵。

- [ ] V1 氛围层：glass token + aurora 背景 + 玻璃侧边栏/顶栏/模态 + panel glow（styles.css）
- [ ] V2 微交互：按钮/卡片反馈、路由过渡（App.vue transition）、toast 升级、骨架屏统一、focus-visible
- [ ] V3 可视化：useCountUp + DashboardStats 数字滚动；DashboardViz donut/柱动画与渐变
- [ ] V4 明细质感：diff 行号/hover/sticky（RepositoryView + PatchDiffViewer）、issue 卡片层次 + 置信度动画、Timeline 升级
- [ ] V5 响应式/可达性：≤900px 图标栏、亮色主题核对、prefers-reduced-motion、@supports 回退
- [ ] V6 验收：npm test/build/audit；Playwright 走查 + 暗/亮 × 桌面/窄屏截图矩阵落 research/shots-visual/

验证命令：`cd frontend && npm test && npm run build && npm audit --audit-level=high`
风险文件：styles.css（大量追加,分区块注释）;App.vue（transition 包装）。回滚：按提交单点 revert。
