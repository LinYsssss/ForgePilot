# Frontend UX remediation for workbench, members, and account

来源：`docs/v2/TEST-ISSUES.md` 的 T-001、T-002、T-003、T-004、T-008、T-009。

## Goal

修复 2026-08-24 真实部署全链路测试发现的六个体验缺陷。这六项的共同性质是**后端能力已经存在或根本不需要后端**，因此本任务是纯前端改动：不新增后端端点、表、迁移、运行时依赖、一级导航或 AI 流程。

## Background and confirmed facts

- **T-001**：`frontend/src/features/workspace/WorkspacePage.vue` 没有任何默认项目选择逻辑（`selectedProject`/`activeProject`/默认值均零命中）。项目上下文只经 URL query `PROJECT_QUERY_KEY` 传入，而 `app/routes.ts:71-72` 的 workspace 路由在无 projectId 时返回不带 query 的 `{ name: "workspace" }`，所以首次进入必然无上下文。
- **T-002**：后端能力**已全部就绪**，无需新增端点——`ProjectMemberController` 已有 `GET /candidates`（:43）、`POST /batch`（:50）、`PATCH /{userId}/roles`（:58）。候选搜索、原子批量添加、成员多角色都是 08-23 任务已交付的能力，缺的只是前端把它们用起来。
- **T-003**：`features/project/ProjectMembersPage.vue` 以大卡片展示成员且无筛选入口。
- **T-004**：需求质量检查与 AI 实现建议的长输出会撑坏 `features/requirement/RequirementDetailPage.vue` 的布局。
- **T-008**：改密表单在 `components/AppShell.vue:112` 的 `<details class="account-menu">` 弹层内（`changePassword` 于 :67 调用），而 `/account` 路由对应的 `features/auth/AccountSettingsPage.vue` **不含**改密。菜单用原生 `<details>`，原生行为就是点击外部不关闭；`AppShell.vue:46` 的 `accountMenuToggled` 只处理 `toggle` 事件，没有外部点击监听。后端 `POST /api/auth/password`（`AuthController:41`）与 `PATCH /api/auth/profile`（:52）都已存在。
- **T-009**：一次性个人 Token 输入在 `AccountSettingsPage.vue:168`，仓库接入 Token 在 `features/scm/RepositoryPage.vue`，两处都没有指向 Provider Token 创建页的入口。
- 锁定导航与路由形态的是 `frontend/tests/routes.spec.ts` 的断言（:72-100）。当前形态为 6 个一级导航 / 11 条产品路由 + 非一级 `/account` 页，**本任务不改变这个形态**。
- 一次性个人 Token 的既有边界必须保持：只用于验证所有权，不落库、不回显、不进日志（08-23 任务 R3）。

## Requirements

### R1 工作台默认上下文（T-001）

- 首次进入 `/workspace` 且 URL 未带项目时，前端自主选定一个默认项目并补齐 query，使工作台始终有上下文。
- 选定规则必须是确定的、可解释的，并在用户只有一个项目、有多个项目、没有任何项目三种情况下都有明确表现；无项目时展示引导而不是空白。
- 用户手动切换项目后，该选择在同一会话内保持，不被默认规则覆盖。

### R2 成员批量添加（T-002）

- 成员添加界面必须能总览候选成员、多选、批量套用共同角色，并允许逐行覆盖角色，全部复用现有 `GET /candidates` 与 `POST /batch`。
- 已是成员的候选禁用选择；单次提交上限沿用后端的 50 人约束，前端在提交前就阻止越界。
- 提交前展示显示名、`@username`、平台 ID 与角色预览；批量失败时把后端返回的错误定位到具体行。（原文还要求「SCM 就绪状态」——候选契约里没有该字段，补上必须改后端，与 AC11 冲突。已决策第 5 条取代该项，成员侧的 SCM 绑定状态由 R3 承载。）
- 批量路径**不得**授予或转移 `LEADER`，与后端既有约束一致。

### R3 成员列表可浏览性（T-003）

- 成员改为紧凑列表展示，单屏可比较多名成员，保留显示名、`@username`、平台 ID、角色集合与 SCM 绑定状态。
- 提供按显示名/用户名和按角色的筛选。

### R4 长内容布局稳定（T-004）

- 需求质量检查与 AI 实现建议的输出无论多长都不得撑破容器、不得导致页面横向滚动。
- 长文本采用受约束的可滚动/可折叠容器；1440 / 768 / 390 三个断点均需人工确认。

### R5 账户操作归位与菜单交互（T-008）

- 改密迁入 `/account` 的资料管理区域，与显示名修改同处一个账户设置面。
- 账户菜单点击外部区域和按 `Esc` 都要关闭，并保持键盘可达与焦点可见。
- 迁移后 `routes.spec.ts` 与相关前端测试同步更新，不得为通过测试而弱化断言。

### R6 Token 获取入口（T-009）

- 一次性个人 Token 与仓库接入 Token 两处输入都提供对应 Provider（GitHub / GitLab 及自建实例）Token 创建页的入口，并说明所需最小权限范围。
- 外链必须遵守既有出站 URL 策略；不得因为加链接而放宽该策略。
- 文案不得暗示 ForgePilot 会保存一次性个人 Token。

### R7 交付约束

- 前端 lint / typecheck / test / build 全绿零 skip；不新增运行时依赖。
- 不改后端代码、不加端点、不加迁移、不动一级导航数量、不碰正式评测资产。
- `docs/v2/TEST-ISSUES.md` 内对应六项补处理结论与验证方式。

## Acceptance Criteria

- [ ] AC1 首次进入 `/workspace` 即有项目上下文；单项目、多项目、无项目三种情况表现均符合 R1 且无空白页。
- [ ] AC2 手动切换项目后默认规则不再覆盖用户选择。
- [ ] AC3 可一次搜索、多选并批量添加成员且批量套用角色，可逐行覆盖；已是成员的候选不可选。
- [ ] AC4 批量提交越界或非法时前端阻止或准确定位到行，且不产生部分成员（后端事务语义未被绕过）。
- [ ] AC5 批量路径无法授予或转移 `LEADER`。
- [ ] AC6 成员列表为紧凑形态并可按名称与角色筛选，信息项无丢失。
- [ ] AC7 超长质量检查与实现建议输出不撑破布局、不产生页面横向滚动，1440/768/390 人工确认通过。
- [ ] AC8 改密可在 `/account` 完成并成功改密；账户菜单点击外部与 `Esc` 均关闭，键盘可达。
- [ ] AC9 两处 Token 输入均有 Provider 获取入口与最小权限说明，且不违反出站 URL 策略。
- [ ] AC10 前端 lint/typecheck/test/build 全绿零 skip；6 导航 / 11 路由 + 非一级 account 页形态不变。
- [ ] AC11 无后端改动、无迁移、无新依赖；正式评测资产未被触碰。

## 已决策（2026-08-24 方案评审）

### 1. 默认项目取「现有列表顺序的第一个」，且不引入任何客户端存储

规则：进入 `/workspace` 时若 URL **没有**项目 query，就取项目列表按后端既有排序的第一个并补进 query；只有一个项目时自然就是它；一个都没有时展示引导空态而不是空白页。

不选「最近访问」是因为它需要 `localStorage`／`sessionStorage`，而那正是「不复杂化」要避开的东西；不选「LEADER 优先」是因为它对同时是多个项目 LEADER 的用户仍不确定，还得再加一层 tie-break。

**会话内保持不需要任何存储**：项目上下文本来就只经 URL query 承载，而默认规则**只在 query 缺失时**触发。用户一旦手动切换，query 就有值，默认规则从此不再介入——R1 的「不被默认规则覆盖」由这个前提条件天然满足，不需要额外记状态。

### 2. 账户菜单保留一个「账户设置」链接，只移除改密表单

完全移除会让用户失去发现入口；而把表单留在弹层里正是 T-008 报的问题本身。所以弹层只留跳转到 `/account` 的链接，改密表单迁到 `/account` 与显示名修改同处一个面板。

### 3. 长内容用定高滚动，不用折叠——并且复用 T-010 已经落地的那一组属性

`FindingCard.vue` 的 `.narrative-body` 已经在 V9 那次改动里定下形态：`max-height` + `overflow: auto` + `white-space: pre-wrap` + `word-break: break-word`。需求质量检查与实现建议沿用同一组，不另造原语。

选定高滚动而非默认折叠：答辩演示时内容直接可见更有说服力，折叠要多一次点击才看得到 AI 到底产出了什么；而布局稳定这个目标两者都能达成。这也是 T-010 `design.md` 里说的「二者不会各造一套」。

### 4. 紧凑列表不分页，只做筛选

当前部署仅 3 个用户，可预见规模内单屏加筛选足够。分页要引入页码状态、与筛选的交互、以及「跨页多选」这一串问题，收益为零。

结论写在此处以免后续会话反复：**在成员规模触及可用性问题之前不加分页**，届时再单独决策。

### 5. 候选预览不含 SCM 就绪状态（取代 R2 原文该项）

`MemberCandidateResponse` 只有 `userId / username / displayName / enabled / alreadyMember`。候选还不是成员，因此既没有 `project_member_scm_binding` 行，别人自有的 `scm_identity` 也不对外暴露——要展示 SCM 就绪状态必须扩后端契约，与 AC11「无后端改动」直接冲突。

因此候选预览只展示显示名、`@username`、平台 ID 与角色四项。成员侧的 SCM 绑定状态由 R3 的成员列表承载（那里有 `listScmBindings` 的真实数据）。AC3/AC4 未要求候选 SCM 状态，验收口径不变。


## Out of Scope

- 任何后端端点、表结构、迁移或授权语义变更。
- 成员移除、知识删除、需求删除（属 `08-24-resource-removal-semantics`）。
- Finding 的问题说明、修复建议与置信度（属 `08-24-finding-explanation-and-remediation`）。
- 新增一级导航、AI 流程、Agent、第二 Review 流程。
- OAuth、长期保存个人 Token、从 Provider 批量导入协作者。
- 视觉体系重做；本任务只解决可浏览性与布局稳定，不重设计。
- 修改或重跑正式评测资产。
