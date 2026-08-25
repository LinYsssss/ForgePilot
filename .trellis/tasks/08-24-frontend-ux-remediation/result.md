# Result — Frontend UX remediation for workbench, members, and account

任务：`08-24-frontend-ux-remediation`（T-001、T-002、T-003、T-004、T-008、T-009）
完成日期：2026-08-25
验收结论：**自动化与静态部分通过；三档宽度的浏览器人工验收待用户执行**（下文如实区分）

## 交付

| 需求 | 文件 | 内容 |
|---|---|---|
| R1 / T-001 | `features/workspace/WorkspacePage.vue` | 无 `project` query 时取项目列表第一个并 `router.replace` 补进 query；一个项目都没有时展示「先建一个项目」引导面板而不是空白 |
| R2 / T-002 | `features/project/ProjectMembersPage.vue` | 候选与提交预览改紧凑 `data-table`；三条后端约束前置（2 字符下限、50 人上限、行角色非空）；`Member row {index}` 解析到具体预览行并标出 |
| R3 / T-003 | 同上 | 成员改四列表格（成员 / 角色 / SCM 绑定 / 操作）+ 文本与角色筛选；角色编辑与负责人转移收进行内 `<details>`；自身 SCM 绑定表单从 `v-for` 提为独立面板 |
| R4 / T-004 | `features/requirement/RequirementDetailPage.vue` | `.quality-report` / `.guidance-result` 整体定高滚动 + `word-break`，散文节点 `pre-wrap` |
| R5 / T-008 | `components/AppShell.vue`、`features/auth/AccountSettingsPage.vue` | 改密表单整体迁入 `/account`；弹层只留跳转链接；新增外部 `pointerdown`、`Escape`（回焦 `<summary>`）、路由变化三条关闭路径，监听在 `onBeforeUnmount` 移除 |
| R6 / T-009 | `features/scm/api.ts`、`AccountSettingsPage.vue`、`RepositoryPage.vue` | 新增纯函数 `providerTokenPage(provider, apiBase)` 与两组最小权限常量；两处 Token 输入各给入口，地址不可解析时退化为路径文本 |

**边界**：`git status` 确认 `backend/` 与 `evaluation/` **零改动**；`package.json` 未变（无新依赖）；`app/routes.ts` 未变（6 导航 / 11 路由 + 非一级 `/account` 形态不动）；无迁移。

## 自动化证据

```
npm run lint       → Frontend foundation policy checks passed.
npm run typecheck  → 通过（vue-tsc，零错误）
npm run test --run → 12 files / 37 tests passed，零跳过（此前 11 / 35）
npm run build      → 通过（build 先重跑一次 vue-tsc）
```

新增测试只有一个文件、两条断言：`tests/workspace.spec.ts` 锁「默认项目规则只在 query 缺失时触发」。这条不变量的两个写错方向都有真实后果——补完 query 仍触发就是无限重定向，带 query 进入也触发就是覆盖用户已做出的选择（AC2）。两条断言各自钉住一个方向。

`tests/journey.spec.ts` 的改密段随表单迁到 `/account`，**断言强度未降**（仍钉 `POST /api/auth/password` 的请求体与成功文案），并**新增**一条「弹层内已无任何口令输入」。步骤 4 的批量添加断言（`POST /members/batch` 请求体）原样保留，未被本次重构弱化。FakeServer 补了 `/api/scm/identities` → `[]`，否则 `/account` 挂载会命中它的 "unexpected request" 守卫。

**刻意未写的测试**：50 人上限、空角色守卫、行级错误定位、R3 两个筛选、R4 定高、R6 外链。前三项是提交前的条件判断与一个正则，筛选是 `computed`；后两项是视觉，而 jsdom 在本配置下不加载 CSS、`getBoundingClientRect()` 恒为 0、无 `matchMedia`，布局与外链行为在这里**证不了**。为它们写单测只会制造「已覆盖」的假象。它们进 `MANUAL-ACCEPTANCE.md`。

## 逐条验收

| AC | 结论 | 依据 |
|---|---|---|
| AC1 首次进入即有上下文，三种情况无空白 | 通过（多项目/单项目自动化，无项目为静态确认） | `workspace.spec.ts`；无项目分支渲染引导面板 |
| AC2 手动切换后默认规则不覆盖 | 通过 | `workspace.spec.ts` 第二条：带 `?project=9` 进入保持 9 |
| AC3 一次搜索、多选、批量套用、逐行覆盖、已是成员不可选 | 通过 | `journey.spec.ts` 步骤 4；`canSelect()` 对 `alreadyMember`/`!enabled` 禁用 |
| AC4 越界或非法时前端阻止或定位到行，不产生部分成员 | 通过 | 50 上限拒选、空角色禁用提交、`locateFailedRow()`；批量仍是后端单事务，未绕过 |
| AC5 批量路径无法授予或转移 LEADER | 通过 | UI 只渲染 `assignableRoles`（DEVELOPER/REVIEWER）；后端 `validateAssignableRoles` 为第二道 |
| AC6 紧凑列表可按名称与角色筛选，信息项无丢失 | 通过 | 四列覆盖旧卡片全部字段（显示名、`@username`、平台 ID、角色集合、绑定状态、标签/用途、远程账号、仓库权限、核验时间） |
| AC7 超长输出不撑破布局、无横向滚动，三档人工确认 | **部分**：CSS 已落地，三档浏览器确认待执行 | `MANUAL-ACCEPTANCE.md` §4 |
| AC8 `/account` 可改密；菜单外部点击与 `Esc` 关闭，键盘可达 | **部分**：改密与「弹层无口令输入」已自动化；外部点击/`Esc`/回焦待浏览器确认 | `journey.spec.ts`；`MANUAL-ACCEPTANCE.md` §1 |
| AC9 两处 Token 入口与最小权限，不违反出站策略 | 通过 | 见下「出站策略」一节 |
| AC10 四命令全绿零 skip；导航/路由形态不变 | 通过 | 上文命令输出；`routes.spec.ts` 未改仍绿 |
| AC11 无后端改动、无迁移、无新依赖；评测资产未触碰 | 通过 | `git status --porcelain -- backend/ evaluation/` 输出 0 行 |

AC7、AC8 记为「部分」是口径问题而非缺陷：这两条的验收方式 PRD 就写明是人工三档确认，而本会话没有浏览器。**不把静态改动记成人工验收通过。**

## 出站 URL 策略未被放宽

`OutboundUrlPolicy` 约束的是**服务端**解引用调用方给的 URL（SSRF：`169.254.169.254`、内网跳板）。R6 生成的是浏览器可点链接，不构成任何服务端出站调用，白名单（生产为空）与拒绝逻辑一字未改。链接来源是当前用户此刻在同一表单里**自己**填的 API 基地址——`/account` 是本人输入，仓库页凭据表单 `v-if="isLeader"` 是 LEADER 本人输入——不存在他人投喂 URL 的路径。地址不可解析时不渲染链接，只给路径文本；外链一律 `rel="noreferrer"`。

## 一处 PRD 措辞已纠正（已写回 prd.md「已决策」第 5 条）

R2 原文要求候选预览展示「SCM 就绪状态」。`MemberCandidateResponse` 没有该字段，候选也还没有项目绑定，别人自有的 `scm_identity` 不对外暴露——补上必须扩后端契约，与 AC11 直接冲突。故候选预览为显示名 / `@username` / 平台 ID / 角色四项；成员侧的 SCM 绑定状态由 R3 的成员表用 `listScmBindings` 的真实数据承载。AC3/AC4 未要求该项，验收口径不变。

## 写回 spec 的三条知识

1. `.trellis/spec/frontend/design-contract.md`：`/account` 现在同时拥有显示名、密码与 SCM 身份，账户菜单不承载任何表单；成员目录是可筛选紧凑表而非一人一卡；自身绑定是单例面板；Workspace 恒有项目上下文且只由 URL 承载。
2. `.trellis/spec/frontend/component-guidelines.md`：**原生 `<details>` 不会因点击外部而关闭**，这是元素自身行为，菜单式弹层必须自己补三条关闭路径并在卸载时摘监听；以及 `white-space: pre-wrap` **绝不能加在 `<ul>`/`<ol>`/`<table>` 上**——模板缩进会渲染成可见空行。
3. 同上文件：前端守卫要照抄后端的**同一批数字**（50 / 2 字符 / 角色非空），不自造更严的限制，也不因为前端挡了就撤掉服务端校验。

## 未做（属其他任务）

成员移除、知识删除与批量上传、作废需求删除 → `08-24-resource-removal-semantics`（V10）。分页 → 规模触及可用性问题前不加（PRD 已决策 4）。
