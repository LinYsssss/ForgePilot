# 前端项目选择逻辑抽取为共享 composable

## Goal

`KnowledgePage`、`RequirementsPage`、`ReviewsPage`、`RepositoryPage`、`WorkspacePage` 各自实现同一套逻辑：加载项目列表、按 `project` 查询参数选中、切换时写回查询参数、计算 `isLeader`、处理加载与错误状态；`ProjectsPage` 复用其中的项目列表加载。相关声明在六个页面里共 26 处。另外 `projectPath()` 在 `knowledge/api.ts`、`review/api.ts`、`scm/api.ts` 各写了一份，约 30 个只在本文件内使用的类型或常量带着多余的 `export`。

## Requirements

- 新增 `composables/useProjectSelection.ts`：输入路由与查询键，输出 `projects`、`selectedProject`、`selectedProjectId`、`isLeader`、`loading`、`error`、`select(projectId)`、`reload()`；选中变化时写回 `project` 查询参数，与现有页面行为一字不差。
- 五个带项目选择的页面改为使用该 composable；`ProjectsPage` 只复用项目列表加载部分。
- `projectPath()` 收敛为一处（建议放在 `lib/http.ts`，它是唯一的请求边界），三份删除。
- 去掉未被任何其他模块或测试导入的 `export`；测试仍在导入的（`PRODUCT_ROUTE_PATHS`、`DPR_MAX`、`PARTICLE_MAX`、`PARTICLE_MIN` 等）保留。
- 行为零变化：所有页面的请求序列、查询参数读写、按钮可见性保持不变。

## Constraints

- `.trellis/spec/frontend/directory-structure.md` 目前把 `composables/` 描述为"存放那一个共享生命周期原语"，新增第二个 composable 必须同步更新该规范（`trellis-update-spec`）。
- `state-management.md` 允许"窄范围 composable"，禁止通用 store；本 composable 只封装页面内状态，不做跨页面缓存。
- 本机 vitest 无法运行（环境问题，与本任务无关），`npm run lint` 与 `npm run typecheck` 可在本机跑；`journey.spec`、`workspace.spec`、`routes.spec` 等由 CI 执行。

## Acceptance Criteria

- [ ] 五个页面与 `ProjectsPage` 的模板与行为不变；`frontend/tests` 全部通过（CI）。
- [ ] `npm run lint`、`npm run typecheck`、`npm run build` 通过。
- [ ] `grep -rn "function projectPath" frontend/src` 只剩一处。
- [ ] `directory-structure.md` 与 `state-management.md` 已登记新 composable。
- [ ] 去掉的 `export` 不含任何测试导入项（以 `tests/*.spec.ts` 的 import 列表为准）。

## Notes

- 复杂任务：`design.md` 与 `implement.md` 已备，经人工审阅后再 `task.py start`。
- 优先级低于 A 组其余任务：它是冗余治理，不是缺陷修复。
