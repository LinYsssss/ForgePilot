# Frontend

这里将承载 ForgePilot V2 的 Vue 3 前端。

Vue 3 + TypeScript + Vite 应用采用用户确认的 **Precision Review Console / 精密审查台**。
2026-08-22 的视觉重建以 `ForgePilot-Frontend/` 设计稿为参考，使用单一深色分层界面，
但所有数据、权限、路由与错误状态仍以本目录的真实 API 实现为准，不引入设计稿中的 mock 数据。

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
