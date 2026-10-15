# 前端项目选择 composable 验证记录（2026-10-14）

## 状态

已实现（提交 `cb48389` + `18fcd1b`）、推送（分支 `fix/review-audit-fixes`）并通过 CI 全量验证，任务归档。

## 本地门

`npm run typecheck`、`npm run lint`、`npm run build` 全部通过；`grep -rn "function projectPath" src` 计数 1。本机 Windows 下 vitest 有已知环境问题，行为验证以 CI 为准。

## CI

CI run 38673878678（2026-10-15），frontend gates job：lint、typecheck、18 个测试文件、build 全部通过——覆盖 `workspace.spec`（默认项目规则）、`journey.spec`（按钮可见性与跳转）及 `requirement*`/`projectArchive`/`scm`/`calibration`/`knowledgePolling`/`routes` 等对迁移页面的行为断言。同 run 其余三个 job 全绿。

## 内容

- 新增 `composables/useProjectSelection.ts`（`useProjectList` + `useProjectSelection`）；六个页面迁移，行为对照表见 `research/current-behaviour.md`（已逐项勾销）。
- `projectPath` 收敛至 `lib/http.ts`（三份删除）；31 个无导入导出摘除；`workspaceRoute` 删除（迁移后无引用）。
- `changeType` 在展示层按两家 Provider 词表中文化（新增/删除/修改/重命名，未知值原样显示）。
- `.trellis/spec/frontend/directory-structure.md` 与 `state-management.md` 已登记新 composable。

## 边界

- 行为零变化：切换一律 push，默认选中仅工作台且仅在缺 query 时 replace；各页数据加载、错误 ref 与权限判断仍归页面，未新增任何请求语义。
- 未部署：部署身份仍是 `54e2953`，本分支含未部署改动。
