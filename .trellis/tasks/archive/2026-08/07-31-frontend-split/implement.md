# Implement：前端重构 — 拆分 App.vue

> 每个 T 一个提交；每步后跑 `cd frontend && npm test && npm run build`，绿了才继续。

## 检查清单（全部完成 2026-07-31）

- [x] T1 依赖与路由骨架（vue-router@4，audit 0；提交 692b98b）
- [x] T2 labels/useBusy/useConfirm 抽出（8260a88）
- [x] T3 AppShell + LoginView（5e1da9a）
- [x] T4~T7 领域单例迁移（c056fc8）+ 8 视图拆出与路由挂载（296b0a7）
  - 实际执行与原计划的偏差：为避免跨域函数在逐视图迁移中反复搬家，改为
    "一次性状态迁移(模板不动) → 机械拆视图模板" 两大步,每步全量验证,更安全。
- [x] T8 App.vue 收尾（60 行,无 tab 残留）
- [x] T9 行为测试 7 个 + nav 接缝（a73cd88）
- [x] T10 Playwright 自动走查通过（见 research/walkthrough-result.md）

## 验证命令

```bash
cd frontend && npm test && npm run build && npm audit --audit-level=high
bash scripts/verify-local.sh --frontend-only
```

## 风险文件与回滚点

- 高危：App.vue（每步保持可构建）、useAgentWorkspace.js（SSE 生命周期）。
- 回滚：每 T 一提交，单点 revert；T1 审计不过 → 放弃 router（改动限 T1 revert）。
