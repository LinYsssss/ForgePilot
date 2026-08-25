# 执行计划：前端体验修复

顺序原则：先改**没有测试依赖**的孤立面（R1、R4、R6），再改**测试要跟着动**的面（R5 改密迁移、R2/R3 成员页重构）。每步后跑 `typecheck`，成组后跑全套。

## 步骤

### 1. R1 工作台默认项目
- [ ] `WorkspacePage.vue`：`onMounted` 取到 `projects` 后，`projectId === null && projects.length > 0` 则 `router.replace(workspaceRoute(projects[0].id))`。
- [ ] 无项目时的空态文案改为指向 `/projects` 的引导，不留白。
- [ ] `import { workspaceRoute }` 加进现有 `../../app/routes` 导入列表（该函数已存在，勿重写）。
- 验证：`npm run typecheck`

### 2. R4 长内容定高
- [ ] `RequirementDetailPage.vue` scoped 样式：`.quality-report` / `.guidance-result` 加 `max-height` + `overflow: auto` + `word-break: break-word`。
- [ ] AI 总结段与知识召回摘录加 `.advice-prose`（`white-space: pre-wrap`）。`pre-wrap` 不加到 `<ul>`/`<ol>`。
- 验证：`npm run lint && npm run typecheck`（lint 会挡住误写的裸色值）

### 3. R6 Provider Token 入口
- [ ] `features/scm/api.ts` 加 `providerTokenPage(provider, apiBase): string | null` 与最小权限文案常量。
- [ ] `AccountSettingsPage.vue` 一次性 Token 字段下方：可解析时给外链（`target="_blank"` + `rel="noreferrer"`），不可解析时给路径文本；附 `read:user` / `read_user` 说明；保留「不保存」文案。
- [ ] `RepositoryPage.vue` 访问令牌字段下方同形态，权限说明为 `repo`（公开仓库 `public_repo`）/ `read_api`。
- 验证：`npm run lint && npm run typecheck`

### 4. R5 改密迁入 `/account` + 菜单交互
- [ ] `AccountSettingsPage.vue`：接入改密表单、六个 ref 与 `updatePassword()`，`.account-password-form` 与三个 input id 原样保留。
- [ ] `AppShell.vue`：删除表单、六个 ref、`updatePassword()`、`accountMenuToggled()`；弹层只留账户摘要 + `/account` 链接。
- [ ] `AppShell.vue`：`accountMenu` ref 绑到 `<details>`；`pointerdown` 外部点击关闭、`Escape` 关闭并回焦 `<summary>`、`watch(route.fullPath)` 关闭；两个 `document` 监听在 `onBeforeUnmount` 移除。
- [ ] `tests/journey.spec.ts`：改密段改为 `router.push("/account")` 后提交，断言强度不变（仍断请求体与成功文案）。
- 验证：`npm run typecheck && npm run test -- --run`（此处必须全绿，改密是唯一被自动化钉住的迁移）

**回滚点**：本步独立可回滚；若 journey 测试出现与改密无关的连带失败，先回滚本步再排查，不要在成员页重构上叠加调试。

### 5. R2 + R3 成员页重构（一次做完，不拆两步）
- [ ] 候选区：`.table-scroll > table.data-table` 紧凑表；保留 `#candidate-query`、`form.inline-form`（**保持为该页第一个** `.inline-form`）、`.candidate-choice`、`.role-picker`、"一次添加" 按钮文案。
- [ ] 搜索下限提示（`< 2` 字符且非纯数字不发请求）；50 人上限拒选 + 禁用提交；空角色行提交前拦截。
- [ ] 批量错误 `/^Member row (\d+)\b/` 解析到行，加 `.row-error`，解析失败只显示原文。
- [ ] 成员区：`.table-scroll > table.data-table.member-table`，四列；文本 + 角色筛选；行内 `<details>` 承载角色编辑与转移负责人；`PENDING_APPROVAL` 的批准/拒绝留在绑定列。
- [ ] 「选择本项目使用的 SCM 身份」提为独立面板（`account` 是成员时渲染），从 `v-for` 里移出。
- 验证：`npm run lint && npm run typecheck && npm run test -- --run`

**回滚点**：`git checkout -- frontend/src/features/project/ProjectMembersPage.vue` 即可退回，其余步骤不受影响。

### 6. 新增 AC1/AC2 测试
- [ ] `tests/workspace.spec.ts`：两条断言（无 query 进入补成第一个项目；带 query 进入不重定向）。自带最小 `fetch` 桩，不改 `journey.spec.ts` 的 FakeServer。
- 验证：`npm run test -- --run`

### 7. 文档同步
- [ ] `MANUAL-ACCEPTANCE.md`：补 R4（长输出定高滚动，三档宽度）、R5（外部点击/`Esc` 关闭、改密在 `/account`）、R6（两处 Token 入口与最小权限）。§1 里「点击用户名可展开…当前密码、新密码…」那条要跟着改，不能留旧位置描述。
- [ ] `docs/v2/TEST-ISSUES.md`：T-001..T-004、T-008、T-009 六行状态改为已处理并写处理结论与验证方式；顶部「状态」行与任务状态表同步。
- 验证：`git diff` 自读一遍，确认没有把三个 08-24 任务的状态写反。

### 8. 最终全量检查（Phase 2.2 最后一轮）
- [ ] `cd frontend && npm run lint && npm run typecheck && npm run test -- --run && npm run build`
- [ ] 逐条对照 `prd.md` 的 AC1–AC11 自查，逐项写进 `result.md`。
- [ ] 人工验收：真实浏览器 1440 / 768 / 390 三档 + 减动效，走 `MANUAL-ACCEPTANCE.md` 的相关条目。

## 验证命令（`frontend/` 下）

```bash
npm run lint
npm run typecheck
npm run test -- --run
npm run build
```

全绿零 skip 才算通过。**不跑后端**：本任务不碰 `backend/`。

## 审查门

- 第 4 步后：确认账户弹层里已无任何口令输入，且 `/account` 能真实改密。
- 第 5 步后：确认 `journey.spec.ts` 步骤 4 未被弱化（仍断 `POST /members/batch` 的请求体）。
- 第 8 步后：确认 6 导航 / 11 路由形态未变，`package.json` 未加依赖，`backend/` 与 `evaluation/` 零改动。

## 明确不做

- 不加分页、不加客户端存储、不加第七个一级导航。
- 不改后端候选契约以补 SCM 就绪状态（见 `design.md` §0）。
- 不动成员移除、知识删除、需求删除（属 `08-24-resource-removal-semantics`）。
