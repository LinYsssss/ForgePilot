# Frontend

这里将承载 ForgePilot V2 的 Vue 3 前端。

Phase 1 已建立 Vue 3 + TypeScript + Vite 路由外壳，生产样式已固化为用户选择的
**B — Precision Review Console / 精密审查台**。

```bash
npm ci
npm run lint
npm run typecheck
npm run test -- --run
npm run build
```

MVP 一级信息架构：

- 项目
- 研发需求
- 代码审查

知识管理属于项目设置；AI 实现建议属于需求详情；Finding 与人工决策属于审查详情。不要为底层技术能力新增一级菜单。
