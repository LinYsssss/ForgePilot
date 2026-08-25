# 技术设计：前端体验修复（T-001..T-004、T-008、T-009）

对应 `prd.md`。本任务是纯前端改动：不新增端点、表、迁移、运行时依赖、一级导航或 AI 流程。

## 0. 一处必须先纠正的 PRD 措辞

R2 写「提交前展示显示名、`@username`、平台 ID、角色与 **SCM 就绪状态**预览」。**候选契约里没有 SCM 字段**：`MemberCandidateResponse` 只有 `userId / username / displayName / enabled / alreadyMember`（`backend/.../project/MemberCandidateResponse.java`），而候选还不是成员，因此既没有 `project_member_scm_binding` 行，别人的 `scm_identity` 也不对外暴露。要展示它必须改后端契约，与 AC11「无后端改动」直接冲突。

**结论：候选预览展示显示名、`@username`、平台 ID、角色四项，不含 SCM 就绪状态。** 成员侧的 SCM 绑定状态由 R3 的成员列表承载（那里有 `listScmBindings` 真实数据）。AC3/AC4 未要求候选 SCM 状态，验收口径不受影响。

## 1. 改动面

| 文件 | 需求 | 改动性质 |
|---|---|---|
| `features/workspace/WorkspacePage.vue` | R1 | 加默认项目补 query 的一次性重定向 |
| `features/project/ProjectMembersPage.vue` | R2 R3 | 候选区改紧凑表 + 越界/空角色守卫 + 行级错误定位；成员区改 `data-table` + 筛选；自身 SCM 绑定表单提为独立面板 |
| `features/requirement/RequirementDetailPage.vue` | R4 | 质量检查与实现建议输出改定高滚动 |
| `components/AppShell.vue` | R5 | 移出改密表单；账户菜单加外部点击/`Esc`/路由变化关闭 |
| `features/auth/AccountSettingsPage.vue` | R5 R6 | 接收改密表单；一次性 Token 加 Provider 入口 |
| `features/scm/RepositoryPage.vue` | R6 | 仓库 Token 加 Provider 入口 |
| `features/scm/api.ts` | R6 | 新增 `providerTokenPage()` 纯函数 |
| `tests/workspace.spec.ts` | AC1 AC2 | 新增（见 §8） |
| `tests/journey.spec.ts` | AC8 | 改密断言随表单迁移到 `/account` |
| `MANUAL-ACCEPTANCE.md` | AC7 AC8 AC9 | 补三条人工验收项 |
| `docs/v2/TEST-ISSUES.md` | R7 | 六项补处理结论 |

后端、迁移、`app/routes.ts`、`styles/tokens.css`、`package.json` **不改**。

## 2. R1 工作台默认项目

规则（PRD 已决策 1）：进入 `/workspace` 时 URL 无 `project` query，就取 `listProjects()` 返回顺序的第一个补进 query。

数据流：

```
onMounted -> listProjects()
  ├─ projects.length === 0 -> 保持 projectId === null，渲染引导空态
  └─ projects.length > 0 且 projectId === null -> router.replace(workspaceRoute(projects[0].id))
```

三个要点：

- 用 `replace` 而不是 `push`：默认选择不是一次用户导航，不该在历史里留一格让「返回」弹回无上下文的 `/workspace`。
- **不需要任何存储**。项目上下文只由 query 承载，而这条规则的触发前提是 query 缺失；用户一旦手动切换，query 就有值，规则从此不介入。AC2 由这个前提条件本身满足。
- **不会循环**：`replace` 后 `projectId !== null`，条件不再成立。`AppShell` 的 `RouterView` 以 `route.fullPath` 为 key，所以重定向会让 WorkspacePage 重挂一次并重新 `listProjects()`——多一次列表请求，换掉一整套本地状态机，接受。

空态文案指向 `/projects` 新建项目，而不是留白。

## 3. R2 候选批量添加

沿用现有 `searchMemberCandidates` / `addMembers`，只改交互与守卫。

- **紧凑候选表**：复用 `ReviewsPage` 已有的 `.table-scroll > table.data-table` 形态（`base.css` 已有全部样式），列为 `选择 / 成员 / 平台 ID / 状态`。保留 `.candidate-choice` 类名与 `#candidate-query`、`.role-picker`、"一次添加"按钮文案——`journey.spec.ts` 步骤 4 钉的是这几个钩子，改形态不该弱化那条全链路断言。
- **搜索下限对齐后端**：后端 `search()` 在 `query.length < 2 && !numericId` 时返回 422。前端此前是静默 `return`，用户看不出为什么没反应；改为渲染同一条件的提示，不再发请求。
- **50 人上限**：`addBatch` 的 `@Size(max = 50)`。前端在 `toggleCandidate` 达到 50 时拒绝再选并提示，提交按钮同时禁用。
- **空角色守卫**：后端 `BatchRow.roles` 是 `@NotEmpty`，取消勾选全部角色的行会让**整批** 422。提交前拦掉，并把该行标出。
- **行级错误定位**：后端错误文案是 `Member row {index} ...`。用 `/^Member row (\d+)\b/` 取出下标，给对应预览行加 `.row-error` 并保留原始文案。解析失败就只显示原文——不猜。
- **LEADER 不可达**：`assignableRoles` 常量仍只有 `DEVELOPER` / `REVIEWER`，UI 里没有 LEADER 复选框；后端 `validateAssignableRoles` 是第二道。AC5 由两层共同保证，不需要新机制。

批量仍是整批事务，不做逐行提交——半套成员关系比一次失败更难解释（与 `08-24-resource-removal-semantics` 决策 7 对知识文件的相反选择互为对照）。

## 4. R3 成员紧凑列表

- 形态：`.table-scroll > table.data-table.member-table`，列为 `成员 / 角色 / SCM 绑定 / 操作`。信息项与旧卡片一一对应，不丢：显示名、`@username`、平台 ID、角色集合、绑定状态、远程账号、权限级别、核验时间（后三项进 `title` 与次行小字，保持单行可比较）。
- 筛选：复用 `.review-filters` 的 `.field` 栅格形态——文本框（显示名/用户名/平台 ID）+ 角色 `<select>`。纯 `computed` 派生，无存储、无 URL 参数（成员页的项目上下文已在路径参数里，筛选是瞬时视图状态，按 `state-management.md` 属 Local UI state）。
- 每行 `操作` 单元格里放一个 `<details>`：LEADER 见角色复选 + 保存 + 转移负责人；`PENDING_APPROVAL` 的批准/拒绝按钮留在 `SCM 绑定` 列（那是对该行绑定的判定，不是行编辑）。
- **「选择本项目使用的 SCM 身份」表单提为独立面板**。它此前渲染在 `account?.id === row.member.userId` 的那一行里——全表最多命中一行，等于用一个 `v-for` 表达一个单例表单。提成面板既让表格保持紧凑，也消掉这层错位。这是简化，不是加面。
- 不分页（PRD 已决策 4）。

## 5. R4 长内容布局

复用 T-010 已落地的属性组（`FindingCard.vue` 的 `.narrative-body`：定高 + `overflow: auto` + `pre-wrap` + `break-word`），不另造原语。

落点是每个 AI 面板的**结果区整体**——`.quality-report` 与 `.guidance-result` 各加 `max-height` + `overflow: auto` + `word-break: break-word`，散文节点加 `.advice-prose { white-space: pre-wrap; }`。

选整体而不是逐块：逐块会让「清单滚动条套在结果滚动条里」，嵌套滚动比长页面更难用。`pre-wrap` 只加在散文元素上，不加在 `<ul>`/`<ol>` 上——那会把模板缩进的换行渲染成可见空行。

## 6. R5 改密归位与菜单交互

- 表单连同 `currentPassword` / `newPassword` / `passwordConfirmation` / `passwordPending` / `passwordError` / `passwordMessage` 与 `updatePassword()` 整体从 `AppShell.vue` 移入 `AccountSettingsPage.vue`，与显示名同处账户设置面。类名 `.account-password-form` 与三个 input 的 id 保持不变，迁移后测试只改「在哪个页面」，不改断言强度。
- 弹层保留跳转 `/account` 的链接（PRD 已决策 2），移除表单后不再需要 `accountMenuToggled` 的清空逻辑。
- 关闭行为三条，都作用在 `<details>` 的 `open` 上：
  - `document` 上的 `pointerdown`：目标不在 `<details>` 内则关闭；
  - `document` 上的 `keydown`：`Escape` 关闭并把焦点还给 `<summary>`（否则焦点留在已消失的弹层里）；
  - `watch(() => route.fullPath)`：路由变化关闭，点了「管理资料与 SCM 身份」不会留着弹层。
- 两个监听在 `onBeforeUnmount` 移除（`quality-guidelines.md` 禁止泄漏监听器）。仍用原生 `<details>`，不引入焦点陷阱或第二套弹层运行时。

## 7. R6 Token 获取入口

`features/scm/api.ts` 新增一个纯函数：

```ts
export function providerTokenPage(provider: ScmProvider, apiBase: string): string | null
```

- 从 `apiBase` 取 origin：host 去掉前缀 `api.`（`api.github.com` → `github.com`），路径丢弃（`/api/v3`、`/api/v4`）。
- 拼 Provider 的固定路径：GitHub `"/settings/tokens"`，GitLab `"/-/user_settings/personal_access_tokens"`。
- `apiBase` 不是合法 `http(s)` URL 时返回 `null`，页面改为纯文本说明该在自己实例的哪个路径创建 Token——**不渲染无法解析的链接**。

自建实例因此自然被覆盖：GHE `https://ghe.example.com/api/v3` → `https://ghe.example.com/settings/tokens`；自管 GitLab `https://gitlab.example.com/api/v4` → `https://gitlab.example.com/-/user_settings/personal_access_tokens`。

**与出站 URL 策略的关系**：`OutboundUrlPolicy` 约束的是**服务端**解引用调用方给的 URL（SSRF）。这里生成的是浏览器可点链接，不构成任何服务端出站调用，也不改变白名单——策略未被放宽。链接来源是当前用户此刻**自己**在同一表单里填的 `apiBase`（`/account` 是本人输入；仓库页的凭据表单 `v-if="isLeader"`，是 LEADER 本人输入），不存在他人投喂 URL 的路径。外链一律 `target="_blank"` + `rel="noreferrer"`，不泄漏来源页地址。

最小权限说明按用途分别写：

| 输入位置 | 用途（后端实际调用） | GitHub | GitLab |
|---|---|---|---|
| `/account` 一次性个人 Token | `GET user` 验证身份 | `read:user` | `read_user` |
| 仓库接入 Token | 读 PR/MR 与 diff、校验仓库权限 | `repo`（公开仓库 `public_repo`） | `read_api` |

文案继续明说一次性 Token 不保存、不回显、不入日志（既有 08-23 R3 边界）。

## 8. 测试

只写承重的：

- **新增 `tests/workspace.spec.ts`**（锁 AC1/AC2）：一个项目的桩下进入 `/workspace` → query 补成第一个项目；带 `?project=` 进入 → 不重定向。锁的不变量是「默认规则只在 query 缺失时触发」，写错就是无限重定向或覆盖用户选择——这是本任务唯一有真实失败模式的新逻辑。
- **改 `tests/journey.spec.ts`**：改密断言从账户弹层移到 `/account`，其余不动。
- 其余不写：50 上限与空角色守卫是提交前的条件判断，行级错误定位是一个正则，R3 筛选是 `computed`，R4/R6 是视觉与外链——它们的失败模式在浏览器里一眼可见，而 jsdom 无 CSS、无 `matchMedia`，本就证不了布局。这三项进 `MANUAL-ACCEPTANCE.md`。

## 9. 兼容性与回滚

- 路由与导航形态不变（6 导航 / 11 路由 + 非一级 `/account`），`routes.spec.ts` 断言不动。
- 无后端契约变化，前后端可独立部署；旧前端配新后端、新前端配旧后端都成立。
- 回滚 = 回滚这批前端提交，无数据迁移、无状态残留（本任务未引入任何客户端存储）。
- 正式评测冻结、语料、holdout 台账与原始输出不触碰。
