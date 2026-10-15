# 五个页面 + ProjectsPage 项目选择现状（迁移前对照，2026-10-14）

零行为变化的勾销表。每迁一页后对照本表逐项核对。

## 共同点（六个页面）

- `onMounted` 里 `listProjects()`，失败写各自的错误 ref；列表加载本身没有可见的 loading 状态（ProjectsPage 除外，见下）。
- `projectId` 来自 `parseId(route.query.project)`；模板的 `projectId === null` 分支与提示顺序不变。

## 逐页差异

| 项 | Workspace | Requirements | Reviews | Knowledge | Repository | Projects |
|---|---|---|---|---|---|---|
| 列表失败写 | `error` | `loadError` | `projectError` | `error` | `error` | `loadError` |
| 无 query 默认选第一个 | **是**（replace `workspaceRoute(first)`，仅列表非空） | 否 | 否 | 否 | 否 | — |
| 切换项目 | `@change="selectProject"` → push `{name:"workspace"}` | `v-model="selection"` → push `requirementsRoute(id)` | `v-model="projectSelection"` → push `reviewsRoute(id)` | `@change="selectProject"` → push `{name:"knowledge"}` | `@change="choose"` → push `{name:"repositories"}` | — |
| selectedProject 变量名 | `selected` | `selectedProject` | `selectedProject` | `selectedProject` | `selected` | — |
| isLeader | 无 | 有 | 无（canTriggerReview 直接用 hasProjectRole） | 有 | 有 | — |
| 数据加载 | `watch(projectId, load, {immediate:true})`，无 token | watch + `requirementsLoadToken` | watch + `projectLoadToken` | `watch(projectId, load, {immediate:true})` + `listToken` | `watch(projectId, load, {immediate:true})`，无 token | — |
| 数据 loading ref | `loading` | `loading` | `projectLoading` | `loading` | `loading` | `loading`（初值 true，onMounted 后 false） |
| 数据失败写入 | 同一 `error` | 同一 `loadError` | 同一 `projectError` | 同一 `error` | 同一 `error` | — |

## 必须保持的不变量

- 切换一律 push；默认选中一律 replace，且只在 URL 缺 project query 时触发（`workspace.spec.ts` 锁的行为）。
- 空/非法 id 时不发数据请求；Requirements 的 watch 在 id 为 null 时不清 `loadError`，其余页的 load 开头会把数据与错误一起清空。
- Reviews 的 pullRequestId watch/token 与本轮无关，原样保留。
- Repository 的 load 无 token；不顺手补（零行为变化）。
- ProjectsPage 的 `loading` 初值 true，模板 `v-if="loading"` 依赖它。

迁移后勾销：Workspace ✓ Requirements ✓ Reviews ✓ Knowledge ✓ Repository ✓ ProjectsPage ✓（2026-10-14 完成；本地 typecheck/lint/build 通过，行为由 CI 前端门验证）
