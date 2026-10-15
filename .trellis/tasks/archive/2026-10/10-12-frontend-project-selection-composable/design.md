# 技术设计：前端项目选择 composable

## 边界

- 新增 `frontend/src/composables/useProjectSelection.ts`；改动五个页面的 `<script setup>`，模板不动。
- `features/project/api.ts` 的 `listProjects` 与 `ProjectSummary` 类型是数据来源，不改。
- `lib/http.ts` 新增并导出 `projectPath(projectId)`；三个 `api.ts` 改为 import。

## 契约

```ts
export function useProjectSelection(options?: { queryKey?: string }): {
  projects: ShallowRef<ProjectSummary[]>;
  selectedProjectId: ComputedRef<number | null>;   // 来自路由查询参数，parseId 解析
  selectedProject: ComputedRef<ProjectSummary | null>;
  isLeader: ComputedRef<boolean>;                   // selectedProject.myRoles 含 LEADER
  loading: Ref<boolean>;
  error: ShallowRef<unknown | null>;
  select(projectId: number | null): Promise<void>;  // router.replace 写回查询参数
  reload(): Promise<void>;
}
```

- 查询参数键默认 `PROJECT_QUERY_KEY`（`app/routes.ts`），与现有页面一致。
- 首次加载：若查询参数缺失且项目列表非空，是否自动选中第一个项目，**按各页面现状逐一核对后决定**；不同页面当前行为若不一致，保持各自现状，用 `autoSelectFirst` 选项表达，而不是统一改变。
- 请求竞态：沿用页面里已有的请求序号模式（`KnowledgePage` 与 `ReviewDetailPage` 已用），composable 内部对 `reload()` 做序号保护。

## 数据流

```text
route.query[project] ──parseId──▶ selectedProjectId ──▶ selectedProject / isLeader
listProjects() ──────────────────▶ projects
select(id) ──router.replace───────▶ route.query[project]（上游）
```

## 取舍

1. **composable 而非 store。** 规范禁止通用 store；composable 每页一份实例，不跨页面缓存，刷新语义与现状一致。
2. **`projectPath` 放 `lib/http.ts`。** 它是"唯一 JSON 请求边界"，路径拼装与之相邻；放进 `app/routes.ts` 会把 API 路径和产品路由混在一起。备选：新建 `lib/paths.ts`，但那要再加一个模块并更新目录规范，收益不足。
3. **去 export 只删修饰符，不动类型本身。** `vue-tsc` 会在任何遗漏的跨模块引用处报错，这是本项唯一需要的安全网。

## 兼容性

- 无 API 变化、无路由变化、无样式变化。
- 测试只导入公开契约（`routes.spec` 的 `PRODUCT_ROUTE_PATHS`、粒子常量等），不受去 export 影响。

## 发布与回滚

- 纯前端重构，随前端镜像发布；回退提交即可。
