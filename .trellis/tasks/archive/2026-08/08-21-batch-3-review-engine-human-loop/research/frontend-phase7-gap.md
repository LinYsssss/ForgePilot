# Research: 前端 Phase 7 缺口盘点

- **Query**: 批次 3（Phase 6 + Phase 7）前端现状、Phase 7「三个一级页面 + 浏览器/可访问性/响应式/视觉漂移验收」的可达性、以及「三角色可重复演示」闭环的入口缺口
- **Scope**: internal（代码 + 权威文档 + 前两批 `result.md`）+ 本机工具能力实测
- **Date**: 2026-08-21
- **纪律**: 本文只记录**读到的原文**与**跑过的命令输出**。凡属我的推导，均以「**推导**」显式标注。
  凡是做不到的，直接写做不到（延续批次 1 AC11 / 批次 2 AC20 的记法）。

---

## 0. 结论摘要（先说最重要的三条）

1. **「三个一级页面」= 项目 / 研发需求 / 代码审查。「代码审查」本身就是第三个一级页面，不是挂在别人下面的子页面。**
   委托问题里「Review 与 Finding 是挂在其中某一项下面的子页面」这句话**一半对一半错**：
   Finding 对（它连路由都没有，必须长在 `/reviews/:id` 里面），Review 错（`/reviews` 与 `/reviews/:id`
   本来就在批准的七条路径里）。依据见 §2.1。
2. **按 Phase 7 的字面标准，今天「三个一级页面」完成度是 0 / 3**：`/reviews` 与 `/reviews/:id` 是
   100% 占位页，`/projects/:id/settings` 也是占位页（批次 2 的 SCM + Knowledge 后端全无 UI）。见 §1.4、§3。
3. **在不新增依赖的前提下，「浏览器 / 响应式 / 视觉漂移」三项无法自动化验收，这是实测结论不是猜测**：
   本机 jsdom 30.0.1 无 `window.matchMedia`、`getBoundingClientRect()` 恒为 0、
   且当前 vitest 配置下**导入的 CSS 一条都不进 jsdom**（`document.styleSheets.length === 0`）。
   实测输出见 §4.3。**「可访问性」可以做到语义层的一部分，其余三项做不到。**
   批次 3 若照现状交付，AC 只能再次记为**部分通过**——但 §4.6 给出了一个**不加依赖也能真实改善**的做法，
   以及它改善不到的地方。

---

## 1. 现有前端完整盘点

### 1.1 文件清单（`frontend/`，排除 `node_modules` / `dist`）

命令与真实输出：

```bash
$ find frontend -type f -not -path "*/node_modules/*" -not -path "*/dist/*" | sort
frontend/.dockerignore
frontend/Dockerfile
frontend/README.md
frontend/index.html
frontend/nginx.conf
frontend/package-lock.json
frontend/package.json
frontend/scripts/lint.mjs
frontend/src/App.vue
frontend/src/app/router.ts
frontend/src/app/routes.ts
frontend/src/components/AppShell.vue
frontend/src/env.d.ts
frontend/src/features/auth/LoginPage.vue
frontend/src/features/auth/session.ts
frontend/src/features/project/ProjectMembersPage.vue
frontend/src/features/project/ProjectsPage.vue
frontend/src/features/project/api.ts
frontend/src/features/requirement/AcceptanceCriteriaEditor.vue
frontend/src/features/requirement/RequirementDetailPage.vue
frontend/src/features/requirement/RequirementsPage.vue
frontend/src/features/requirement/api.ts
frontend/src/features/requirement/status.ts
frontend/src/lib/datetime.ts
frontend/src/lib/http.ts
frontend/src/main.ts
frontend/src/styles/base.css
frontend/src/styles/tokens.css
frontend/src/views/FoundationPlaceholderPage.vue
frontend/tests/http.spec.ts
frontend/tests/motion.spec.ts
frontend/tests/requirement.spec.ts
frontend/tests/routes.spec.ts
frontend/tests/session.spec.ts
frontend/tsconfig.app.json
frontend/tsconfig.json
frontend/vite.config.ts
```

**共 8 个页面级 `.vue`，其中 1 个是占位页**（`FoundationPlaceholderPage.vue`）。

### 1.2 前端自批次 1 起零改动（已核实）

```bash
$ git log --oneline -3 -- frontend/
e2bc73b feat(frontend): add login, project, member and requirement screens
8f7bb9d feat(frontend): add Phase 1 Vue scaffold and freeze the precision review console contract
6ecc8dc chore: reset ForgePilot to clean V2 skeleton

$ git log -1 --format="%h %ad %s" --date=short -- frontend/src/
e2bc73b 2026-08-21 feat(frontend): add login, project, member and requirement screens
```

与批次 2 `result.md` §1「前端 **未改动**（`git diff 1c6922f..HEAD -- frontend/` 为空）」一致。
**因此当前前端形态 = 批次 1 结束时的形态**，批次 2 的 AI / Knowledge / SCM 三条切片**没有任何 UI**。

### 1.3 导航结构（核实：**仍然是三项**）

`frontend/src/app/routes.ts:18-22`：

```ts
export const TOP_LEVEL_NAVIGATION = [
  { label: "项目", to: "/projects" },
  { label: "研发需求", to: "/requirements" },
  { label: "代码审查", to: "/reviews" },
] as const;
```

渲染于 `frontend/src/components/AppShell.vue:28-37`（`<nav aria-label="主导航">`，`v-for` 遍历上表）。
测试锁死于 `frontend/tests/routes.spec.ts:55-59`（断言三个 `to` 值）与 `:68`（断言 `.nav-link` 恰好 3 个）。

**核实结论：批次 1 `result.md` §4 说的「一级导航仍严格三项」到今天仍然成立，没有变成四项，也没有减少。**

### 1.4 路由表（七条批准路径 + 登录 + 兜底）

`frontend/src/app/routes.ts:31-39` 声明批准路径常量，`:71-122` 是真实路由表：

| 路径 | 名称 | 组件 | 定义位置 | 是否有产品内容 |
|---|---|---|---|---|
| `/` | — | redirect → `/projects` | `routes.ts:72` | n/a |
| `/login` | `login` | `LoginPage.vue` | `routes.ts:73-78` | ✅ 有（不在一级导航内） |
| `/projects` | `projects` | `ProjectsPage.vue` | `routes.ts:79-84` | ✅ 有 |
| `/projects/:id/members` | `project-members` | `ProjectMembersPage.vue` | `routes.ts:85-90` | ✅ 有 |
| `/projects/:id/settings` | `project-settings` | **`FoundationPlaceholderPage.vue`** | `routes.ts:91-96` | ❌ **占位** |
| `/requirements` | `requirements` | `RequirementsPage.vue` | `routes.ts:97-102` | ✅ 有 |
| `/requirements/:id` | `requirement-detail` | `RequirementDetailPage.vue` | `routes.ts:103-108` | ✅ 有 |
| `/reviews` | `reviews` | **`FoundationPlaceholderPage.vue`** | `routes.ts:109-114` | ❌ **占位** |
| `/reviews/:id` | `review-detail` | **`FoundationPlaceholderPage.vue`** | `routes.ts:115-120` | ❌ **占位** |
| `/:pathMatch(.*)*` | — | redirect → `/projects` | `routes.ts:121` | n/a |

占位页原文（`frontend/src/views/FoundationPlaceholderPage.vue:11-22`）：

```vue
<p class="eyebrow">Phase 1 · engineering foundation</p>
<h1 id="foundation-title">{{ title }}</h1>
<p class="lede">路由、请求边界与工程工具已经就位。业务能力将在对应阶段获得授权后，以纵向切片实现。</p>
<strong>当前仅为工程底座</strong>
<p>本页不包含模拟数据、业务操作、登录流程或审查结论。</p>
```

**即：点「代码审查」这个一级菜单，今天看到的是一句「当前仅为工程底座」。**

### 1.5 会话与请求边界

- 路由守卫：`frontend/src/app/router.ts:19-21`，`meta.requiresSession === true && !hasSession()` → `/login`。
- 401 唯一反应点：`router.ts:24-29`（`setUnauthorizedHandler` → 清会话 + `replace('/login')`）。
- 冷启动探针：`frontend/src/features/auth/session.ts:30-40`，`GET /api/auth/me`，兼作 `XSRF-TOKEN` cookie 下发。
- CSRF：`frontend/src/lib/http.ts:82-87`，写方法自动带 `X-XSRF-TOKEN`。
- 错误体：`http.ts:1-6` 的 `ApiError { code, message, traceId }`，与批次 1 `api-contract.md` §0 一致。

**这一层是可复用的，批次 3 不需要动。**（推导）

### 1.6 状态与派生量的现状（批次 3 必须扩的地方）

`frontend/src/features/requirement/status.ts:9-22`：

```ts
/** Read-only derived review activity. Batch 1 has no PR data, so it is always NO_PR. */
export type ReviewActivity = "NO_PR";

export const REVIEW_ACTIVITY_LABELS: Record<ReviewActivity, string> = {
  NO_PR: "无关联 PR",
};
```

`ReviewActivity` 目前**只有一个取值**。PRD §5（`docs/v2/PRD.md:106-117`）规定了八个：
`REVIEW_REQUIRED / FAILED / CHANGES_REQUESTED / REVIEWING / PENDING / APPROVED / MIXED / NO_PR`。
批次 3 必须把这个联合类型扩到八个，否则 `vue-tsc` 会在 `Record<ReviewActivity, string>` 上直接失败。

好消息是**展示容器已经建好且已被测试锁住**：需求状态与评审活动是两个独立的 `<dd>`
（`RequirementsPage.vue:168-186`、`RequirementDetailPage.vue:171-205`），
`frontend/tests/requirement.spec.ts:119-129` 断言 `status.text()` 里**不含** `NO_PR`。
这条正好对应 PRD `:117`「需求状态与评审活动并列展示，不得合并」与 ARCHITECTURE `:424`。

### 1.7 一处小的既存视觉债（顺手记下，非阻塞）

`button-quiet` 这个 class 在 14 处模板里被使用，但**任何 CSS 里都没有定义它**：

```bash
$ grep -rn "button-quiet" frontend/src/styles/ || echo "NOT DEFINED IN ANY CSS"
NOT DEFINED IN ANY CSS
```

使用点：`ProjectsPage.vue:77,80`、`ProjectMembersPage.vue:193,218,229`、
`RequirementDetailPage.vue:157,228,247`、`LoginPage.vue:82,90`、`AcceptanceCriteriaEditor.vue:44,52,60,70`。
**后果**：这些按钮只拿到 `.button` 基础样式，「次要按钮」这一层视觉分级实际不存在。
不影响功能与可访问性，属视觉漂移清单里应当被抓到、但从未被抓到的一条——
**这本身就是「没有做过真正的视觉漂移验收」的一个旁证**。（推导）

---

## 2. Phase 7 的「三个一级页面」到底是哪三个

### 2.1 原文依据（不是推导）

`docs/v2/ARCHITECTURE.md:409`：

> 一级导航只有三个：**项目**、**研发需求**、**代码审查**。

紧接着 `:411-419` 给出全部批准路径：

```text
/projects
/projects/:id/members
/projects/:id/settings       # SCM + Knowledge
/requirements
/requirements/:id
/reviews
/reviews/:id
```

`:421-423`：

> Workbench、Knowledge、Repository、Metrics、Agent、Patch、AI Logs 均**不做**一级页面。
> 知识检索测试不面向普通用户；管理员只需看到文档状态与失败原因。
> **一次性实现建议位于 Requirement 详情页**，不创建 Assistant 一级菜单或 Conversation 页面。

`docs/v2/PRD.md:84`（「不做」清单）：

> Workbench、代码仓库/知识/AI 日志一级菜单。

`docs/v2/IMPLEMENTATION-PLAN.md:116`：

> 任何新增表、模块、**一级页面**、运行时依赖或改变已接受决策的行为都必须先补充并批准新的决策记录。

### 2.2 对委托里那个理解的核实

> 「所以『三个一级页面』应当就是现有的那三项，Review 与 Finding 是挂在其中某一项下面的子页面。」

**前半句对，后半句需要更正：**

| 断言 | 判定 | 依据 |
|---|---|---|
| 「三个一级页面」= 现有那三项（项目 / 研发需求 / 代码审查） | ✅ **对** | ARCHITECTURE `:409` 逐字列名；`routes.ts:18-22` 已实现同样三项 |
| 「Review 挂在其中某一项下面」 | ❌ **错** | **代码审查就是那三项里的第三项**。`/reviews`、`/reviews/:id` 是 ARCHITECTURE `:417-418` 批准的一级路径，`routes.ts:37-38` 已把它们列进 `PRODUCT_ROUTE_PATHS`，`routes.ts:109-120` 已注册路由（组件是占位页） |
| 「Finding 挂在某一项下面」 | ✅ **对** | 批准的七条路径里**没有** Finding 路径。因此 Finding 只能是 `/reviews/:id` 内部的一个区域，不能有自己的路由 |

**正确的结构（原文 + 一处推导）**：

```text
一级导航（3，锁死）
├── 项目 /projects
│   ├── /projects/:id/members        成员 + 项目级 SCM 身份            ← 已实现
│   └── /projects/:id/settings       SCM 仓库配置 + 项目知识上传/状态   ← 占位，批次 2 的后端全无 UI
├── 研发需求 /requirements
│   └── /requirements/:id            需求详情 + AC + 版本历史          ← 已实现（批次 1 范围）
│                                    + 质量检查结果（Phase 6）         ← 缺
│                                    + 一次性实现建议（ARCHITECTURE:423）← 缺（后端批次 2 已有）
│                                    + 关联 PR 列表与 review_activity   ← 缺
└── 代码审查 /reviews                 ← 占位
    └── /reviews/:id                  Review 详情只读页（Phase 6 退出条件）← 缺
                                      └─ Finding 列表 + 人工生命周期（Phase 7）← 缺（无独立路由）
                                      └─ Review Decision 决策区（Phase 7）    ← 缺
                                      └─ AC 覆盖判定 + truncation manifest    ← 缺
```

**推导（需要在 design 阶段裁定，见 §7 OPEN-3）**：批准的七条路径里**没有 PR 详情页**，
而后端已有 `GET /api/projects/{projectId}/pull-requests/{pullRequestId}`
（`backend/.../scm/ScmController.java:53-57`）与 PRD P1 要求的关联纠正接口（`:65-70`）。
PR 这个对象必须在**不新增第八条路径**的前提下找到落点。我倾向于放在 `/reviews`（列表按 PR 分组）
与 `/reviews/:id`（详情页头部展示所属 PR + 关联需求下拉框）——但这是我的判断，不是原文规定。

### 2.3 差距（要什么 / 有什么 / 差什么）

| 一级页面 | 原文要求 | 现有 | 差 |
|---|---|---|---|
| **项目** | `/projects` + `/members` + `/settings`（SCM + Knowledge） | 前两个已实现 | **`/settings` 整页**：SCM 仓库注册/更新、凭据不回显、知识文档上传与 `status`/`failure_reason` 展示（ARCHITECTURE `:422` 明确要求「管理员只需看到文档状态与失败原因」） |
| **研发需求** | 列表 + 详情 + 版本历史 + 一次性实现建议 + 质量检查 + 真实 `review_activity` | 列表/详情/版本历史/指派/状态机已实现；`review_activity` 容器已建但类型恒为 `NO_PR` | **质量检查区**、**一次性实现建议区**、**`ReviewActivity` 扩到 8 值**、**关联 PR 展示**、**需求附件** |
| **代码审查** | `/reviews` 列表 + `/reviews/:id` 详情（含 Finding、Decision、AC 判定、manifest） | **全部占位** | **整个一级页面**，两条路由 |

**按 Phase 7 字面标准「三个一级页面完成浏览器、可访问性、响应式和视觉漂移验收」，今天完成度 0 / 3。**
（「项目」因 `/settings` 占位不完整；「研发需求」缺三块；「代码审查」为空。）

---

## 3. Phase 6 的「Review 详情只读页」挂在哪，需要哪些后端字段

### 3.1 位置

`docs/v2/IMPLEMENTATION-PLAN.md:76` 末句：

> 退出：非法 JSON 不假成功、大 PR 不静默丢文件、after-commit 失败可恢复、fencing/父 FK/上下文/聚合矩阵集成测试全绿；**Review 详情只读页可用**。

落点是 **`/reviews/:id`**（`routes.ts:115-120`，今天指向 `FoundationPlaceholderPage`）。
Phase 6 只要求**只读**；Phase 7 才在同一页上加人工决策区（Finding 生命周期 + Review Decision）。
**推导**：因此这一页应当一次建好骨架，Phase 6 交只读部分，Phase 7 往里加操作区，而不是建两个页面。

配套还需要 **`/reviews`** 列表页，否则用户点一级菜单「代码审查」无处可去、也无从拿到 `:id`。
**这一条实施计划没写死**，是我的推导（Phase 6 只提「详情只读页」）——见 §7 OPEN-4。

### 3.2 这一页需要的后端字段（为批次 3 `api-contract.md` 打底）

依据：`ARCHITECTURE.md` §2.1（`:102-104` review/finding/finding_event 列）、§3.1（`:267-286`）、
§3.2（`:288-303`）、§3.4（`:327-337`）、§3.5（`:339-348`）、§3.6（`:350-360`）、§6（`:424`）、
`PRD.md` §5（`:106-136`）、§6 P4/P5/P8（`:145-149`）。

#### 3.2.1 Review 身份与有效性（三个概念不得混）

ARCHITECTURE `:269-273` 原文：

```text
Review Identity = pull_request_id + head_sha + review_input_fingerprint + requirement_revision_id
Current Validity = Review 的 head/fingerprint/requirement revision 均等于 PR 当前值
Decision Gate = pull_request_id + head_sha 上是否已有 REQUEST_CHANGES
```

页面需要的字段（**推导出的响应形状**，字段名待 design 定）：

| 字段 | 来源 | 用途 |
|---|---|---|
| `id`, `projectId`, `pullRequestId` | `review` 行 | 身份 |
| `headSha`, `reviewInputFingerprint`, `requirementId`, `requirementRevisionId` | `review` 行（不可变） | 身份四元组 |
| `pullRequestCurrent: { headSha, reviewInputFingerprint, requirementId, requirementRevisionId }` | `pull_request` 当前值 | 供页面**自行对比**派生「当前 / 已过期」 |
| `stale: boolean`（或由前端算） | 派生 | ARCHITECTURE `:346`：「页面以当前 PR 的 head/fingerprint/revision 对比快照派生"当前/已过期"，**不写 `INVALIDATED` 状态**」 |
| `decisionGateBlocked: boolean` + `blockingReviewId` | 派生 | `:284` 同一 head 出现 `REQUEST_CHANGES` 后只能靠新 head 解除；页面必须能解释**为什么 APPROVE 按钮不可用** |

⚠️ **`requirementRevisionId` 的比较必须是 `IS NOT DISTINCT FROM`（NULL 也要相等，`:283` 第 5 条）。**
如果契约里把它写成可选字段并让前端用 `==` 比，NULL 语义会漂。（推导，但风险实在）

#### 3.2.2 执行状态与审计

| 字段 | 来源 | 备注 |
|---|---|---|
| `status` | `PENDING / RUNNING / COMPLETED / FAILED`（§3.2 `:290-299`） | 与 `decision` **正交**，UI 必须分开 |
| `failureReason` / `errorSummary` | `review` 行 | R3：非法 JSON → FAILED，页面必须能说明「失败」而不是空报告 |
| `executionAttempt` | `review.execution_attempt` | 重试次数，只读展示 |
| `engine`, `promptVersion`, `model` | `review` 审计列（§2.1 `:102`） | 评测可复现所需 |
| `createdAt`, `startedAt`, `completedAt` | `review` 行 | 耗时展示 |

**不要**把 `execution_token` / `lease_until` 发给前端（推导：fencing 凭证，泄漏无收益）。

#### 3.2.3 Decision（Phase 6 只读，Phase 7 可写）

| 字段 | 备注 |
|---|---|
| `decision` | `PENDING / APPROVE / REQUEST_CHANGES`，写一次（PRD `:136`） |
| `decisionBy`（userId + username）, `decisionAt`, `decisionComment` | §2.1 CHECK 保证组合合法（`:214-220`） |

#### 3.2.4 上下文快照（P8：历史页面禁止反查当前关联）

PRD P8（`:149`）：

> Review 保存审查时的 requirement_id、requirement_revision_id 与不可变上下文快照；历史结果不得通过 PR 当前关联反查语义。

因此响应里必须**自带**快照，不能让前端再去拉 `/requirements/:id`：

| 字段 | 内容 |
|---|---|
| `requirementSnapshot` | 审查时的 `title` / `revisionSeq` / `background` / `description` |
| `acceptanceCriteriaSnapshot[]` | `{ acId, acKey, text }`——**`ac_key` 是跨 Revision 稳定身份**（§3.6 `:354`） |
| `knowledgeEvidence[]` | `{ sourceId, documentTitle, excerpt, excerptHash }`——不可变 excerpt（§3.5 `:344`） |

#### 3.2.5 AC 覆盖判定（Phase 6 的核心只读产出）

§3.5 `:341`：「每条 AC 最终必须有 `COVERED | NOT_FOUND | AT_RISK`；模型漏项由 Validator 补 `NOT_FOUND`。」

```jsonc
acVerdicts: [ { "acId": 91, "acKey": "AC-1", "verdict": "COVERED",
                "rationale": "…", "evidenceRefs": [ … ] } ]
```

#### 3.2.6 覆盖 / 截断 manifest（P5，禁止静默截断）

PRD P5（`:146`）与 §3.4 `:335`：「未审查文件必须显式呈现，禁止静默截断。」

```jsonc
coverage: {
  "reviewedFiles": ["src/a.ts", …],
  "skippedFiles":  [ { "path": "src/big.ts", "reason": "PATCH_TOO_LARGE", "truncatedAtChars": 60000 } ],
  "batchCount": 3
}
```

#### 3.2.7 Finding（Phase 6 只读，Phase 7 加操作）

§2.1 `:103` 的列 + §3.6 的血缘规则：

| 字段 | 备注 |
|---|---|
| `id`, `findingType` | `REQUIREMENT` / `CODE_QUALITY`（§2.3 `:211`：`CODE_QUALITY` 时 `ac_id` 必须为 NULL） |
| `path`, `line` | 行号必须落在 patch 可验证范围，否则不给精确行号（§3.5 `:343`）→ **`line` 必须可为 null，UI 要能显示「无精确行号」** |
| `evidence`, `evidenceHash` | 不可变 excerpt |
| `acId`, `acKey` | 回溯到 AC |
| `sourceId` | 回溯到知识 excerpt |
| `status` | `OPEN / CONFIRMED / IN_PROGRESS / FIXED / VERIFIED / CLOSED / REJECTED` |
| `continuity` | `NEW / PERSISTING / SUPPRESSED` |
| `carriedFromFindingId` | 血缘指针 |
| `assigneeId` / `assigneeUsername` | 认领人 |
| `aiConfidence` | ⚠️ 见下 |
| `events[]` | `finding_event`：`{ actorUsername, action, from, to, comment, createdAt }`（Phase 7） |

⚠️ **PRD `:135` 与 ARCHITECTURE `:424` 是硬约束**：

> AI 置信度、Finding 状态、Review Decision 三者**不互相替代**，UI 上必须分开呈现。
> 需求状态与派生的评审活动（D011）同样是两个正交维度，不得合并为一个标签。

再叠加 PRD `:131`：`status`（人工生命周期）与 `continuity`（跨轮血缘）
「**不得混入同一字段或同一 UI 标签**」。

**推导：`/reviews/:id` 上一条 Finding 至少要同时显示四个互不合并的标记**——
`status`、`continuity`、`aiConfidence`、以及所属 Review 的 `decision`。
这是本页最容易被「做成一个漂亮的综合风险徽章」而违规的地方。

#### 3.2.8 `NOT_REPORTED` 是派生查询，不落库

§3.6 `:356`：「上一轮存在、本轮未报告只查询派生 `NOT_REPORTED`，**不落库、不自动判定修复**。」
→ 响应里应有一个独立的 `notReportedFromPrevious[]` 数组，**不能**混进 `findings[]`。（推导）

---

## 4. 「可访问性、响应式、视觉漂移」在本项目现有工具下能做到什么

### 4.1 `frontend/package.json` 里的命令（逐条列出）

`frontend/package.json:9-15`：

```json
"scripts": {
  "dev": "vite",
  "lint": "node scripts/lint.mjs",
  "typecheck": "vue-tsc --noEmit -p tsconfig.app.json",
  "test": "vitest",
  "build": "vue-tsc --noEmit -p tsconfig.app.json && vite build"
}
```

`.trellis/spec/frontend/quality-guidelines.md:41-47` 规定的执行顺序是
`npm ci` → `lint` → `typecheck` → `test -- --run` → `build`（五条）。

### 4.2 实际跑了一遍（真实输出，未删改）

环境：`node v24.18.0`，`npm 12.0.1`，复用既有 `node_modules`（**未**重跑 `npm ci`，见 §7 假设 A2）。

```bash
$ cd /root/ForgePilot/frontend && npm run lint
npm notice run forgepilot-frontend@0.1.0 lint
npm notice run node scripts/lint.mjs
Frontend foundation policy checks passed.
EXIT=0
```

```bash
$ npm run typecheck
npm notice run forgepilot-frontend@0.1.0 typecheck
npm notice run vue-tsc --noEmit -p tsconfig.app.json
EXIT=0
```

```bash
$ npm run test -- --run
npm notice run forgepilot-frontend@0.1.0 test
npm notice run vitest --run

 RUN  v4.1.11 /root/ForgePilot/frontend

Not implemented: Window's scrollTo() method
Not implemented: Window's scrollTo() method
Not implemented: Window's scrollTo() method
Not implemented: Window's scrollTo() method
Not implemented: Window's scrollTo() method
Not implemented: Window's scrollTo() method
Not implemented: Window's scrollTo() method
Not implemented: Window's scrollTo() method

 Test Files  5 passed (5)
      Tests  15 passed (15)
   Start at  18:22:36
   Duration  26.69s (transform 9.95s, setup 0ms, import 14.87s, tests 3.51s, environment 44.49s)

EXIT=0
```

```bash
$ npm run build
npm notice run forgepilot-frontend@0.1.0 build
npm notice run vue-tsc --noEmit -p tsconfig.app.json && vite build
vite v8.2.1 building client environment for production...
transforming...
✓ 52 modules transformed.
rendering chunks...
computing gzip size...
dist/index.html                   0.48 kB │ gzip:  0.30 kB
dist/assets/index-Bzq-Qb4-.css   10.22 kB │ gzip:  2.39 kB
dist/assets/index-DB71gMCW.js   117.18 kB │ gzip: 42.93 kB
✓ built in 2.91s
EXIT=0
```

**四条全绿，与批次 1 `result.md` §4 记录的 `index-DB71gMCW.js 117.18 kB` 完全一致**——
再次印证前端自批次 1 起零改动。

**「Not implemented: Window's scrollTo()」八行是 jsdom 的能力缺口，不是失败**
（`router.ts:16` 的 `scrollBehavior` 在 jsdom 里没有实现）。它本身就是「jsdom ≠ 浏览器」的一个现场证据。

#### 这四条命令各自实际验证了什么

| 命令 | 真正验证的 | **不**验证的 |
|---|---|---|
| `lint`（`scripts/lint.mjs`） | tab/行尾空白（`:49-51`）、`tokens.css` 之外的裸颜色（`:52-54`）、13 个禁用依赖（`:9-24, 57-63`） | 任何渲染、任何可访问性、任何布局 |
| `typecheck` | TS/模板类型 | 同上 |
| `test`（vitest + jsdom） | 5 文件 15 用例：路由/导航常量、skip link 存在、会话跳转、CSRF 头、需求状态与评审活动不合并、`acKey` 保持 | 见 §4.3 |
| `build` | 生产可构建、产物体积 | 同上 |

### 4.3 三项验收的能力边界——**实测，不是推断**

我在**不改动仓库任何文件**的前提下（探针写在 `/tmp`），用本机 jsdom 30.0.1 与本机 vitest 4.1.11 实测了四个决定性问题。

**探针 1：直接在 jsdom 里注入真实 `base.css` 并测量**

```bash
$ node /tmp/fp-probe/probe.mjs
{
  "jsdomVersion": "30.0.1",
  "innerWidth": 1024,
  "innerHeight": 768,
  "boundingRectOfPanel": { "width": 0, "height": 0, "top": 0, "left": 0 },
  "computedPaddingOfPanel": "0",
  "computedBackgroundOfPanel": "rgba(0, 0, 0, 0)",
  "computedMinHeightOfNavLink": "44px",
  "matchMediaType": "undefined",
  "matchMedia_max42rem": "N/A",
  "matchMedia_reducedMotion": "N/A",
  "styleSheetsSeen": 1,
  "cssRulesParsed": 69
}
```

读法：

- `boundingRect` 全 0 → **jsdom 完全不做布局**。响应式、横向溢出、遮挡、目标尺寸一律不可测。
- `computedPaddingOfPanel: "0"`、`background: rgba(0,0,0,0)`，而 `.panel` 的真实声明是
  `padding: var(--fp-space-6)`、`background: var(--fp-color-surface)`
  （`base.css:155-162`）→ **jsdom 不解析 `var()` 自定义属性**。
  本项目设计契约规定 `tokens.css` 是唯一色彩/间距来源（`design-contract.md:34-42`），
  **因此整套设计系统对 jsdom 不可见**。
- `computedMinHeightOfNavLink: "44px"` → 字面值（`base.css:100` 的 `min-height: 2.75rem`）**能**读到。
  即：只有**不走 token 的字面声明**可以被断言。
- `matchMediaType: "undefined"` → **jsdom 没有 `window.matchMedia`**。
  `prefers-reduced-motion` 与 `max-width: 42rem` 断点**在行为上无法被测**。

**探针 2：当前 vitest 配置下，导入的 CSS 到底进不进 jsdom**

```bash
$ npx vitest run --root /tmp/fp-probe      # spec 里 import 了真实 base.css
 Test Files  1 passed (1)
      Tests  1 passed (1)

$ cat /tmp/fp-probe/out.txt
STYLESHEETS=0
NAVLINK_MIN_HEIGHT='auto'
MATCHMEDIA=undefined
GETBOUNDINGRECT_WIDTH=0
```

**这一条最关键：`STYLESHEETS=0`。**
`frontend/vite.config.ts:10-15` 的 `test` 块没有设 `css` 选项，其默认值是 `false`，
所以在**真实的 `npm run test` 环境里，CSS 一个字节都没进 jsdom**——
连探针 1 里那个能读到的 `min-height: 44px` 都读不到（`'auto'`）。

**探针 3：本机有没有浏览器**

```bash
$ which chromium chromium-browser google-chrome firefox
（无输出）
$ ls -d ~/.cache/ms-playwright ~/.cache/puppeteer
ls: cannot access '/root/.cache/ms-playwright': No such file or directory
ls: cannot access '/root/.cache/puppeteer': No such file or directory
$ echo "DISPLAY='${DISPLAY:-<unset>}'"
DISPLAY='<unset>'
```

**本机没有任何浏览器，也没有显示。**

**探针 4：npm registry 通不通（决定"能不能装"）**

```bash
$ npm view playwright version
1.62.1
$ npm config get registry
https://registry.npmjs.org/
```

**registry 可达**——所以「装不上」不是理由；能不能装是**授权问题**，不是能力问题。

### 4.4 「不新增依赖」是否覆盖 devDependency——查证结果

**原文只说了「运行时依赖」，没说 devDependency。** 逐条列证：

| 出处 | 原文 | 措辞 |
|---|---|---|
| `docs/v2/IMPLEMENTATION-PLAN.md:116` | 「任何新增表、模块、一级页面、**运行时依赖**或改变已接受决策的行为都必须先补充并批准新的决策记录。」 | **运行时** |
| `docs/v2/DECISIONS.md:256-257`（D014 评审标准） | 「边界检查（无计划外的表、顶层包、一级菜单、**运行时依赖**）通过」 | **运行时** |
| `docs/v2/IMPLEMENTATION-PLAN.md:129`（批次 1 闸门自证） | 「边界检查（无计划外的表/顶层包/一级菜单/**运行时依赖**）通过」 | **运行时** |
| `docs/v2/ARCHITECTURE.md:449` | 「**不引入**：RabbitMQ/Kafka/Redis/ES/Milvus/Qdrant、Resilience4j…」 | 全部是运行时组件 |
| `AGENTS.md` | 未提依赖 | — |

**但反方向也有明确证据：**

| 出处 | 原文 | 含义 |
|---|---|---|
| `frontend/scripts/lint.mjs:57-63` | `const dependencies = { ...packageJson.dependencies, ...packageJson.devDependencies };` 然后逐个查禁用名单 | **本项目的 lint 把 dependencies 与 devDependencies 一视同仁地检查** |
| `docs/v2/DECISIONS.md:380`（D015.8） | 「**不新增 WireMock / MockWebServer / MockServer 依赖。**」 | WireMock 等**只可能是 test scope**，却被当成「新增依赖」明确禁止 |
| `docs/v2/DECISIONS.md:384` | 「`MockRestServiceServer` 都已可用，WireMock/MockWebServer 都不在，**新增即是新增依赖**」 | 同上，**已有先例把测试依赖算作依赖** |
| 批次 1 `result.md:113` | 「`git diff --stat frontend/package.json frontend/package-lock.json` 为空——**未新增任何依赖**」 | 判据是整个 `package.json` 无差异，不区分两类 |
| 批次 2 `result.md:18` | 「新增运行时依赖 **零**（`backend/pom.xml` 自 `f1d02e1` 起未再改动）」 | 标题写「运行时」，判据却是整份 pom 无改动 |

**判定：需要裁定的开放项（OPEN-1）。**

- **字面读**（权威文档措辞）：只禁运行时依赖，devDependency 未被禁。
- **实践读**（D015.8 先例 + `lint.mjs` 的实现 + 前两批的自证判据）：**测试依赖已经被当作依赖禁止过一次**，
  且本项目 lint 会主动扫 devDependencies。
- **我的判断（推导，不是原文）**：按 D015.8 建立的先例，新增 Playwright 属于「新增依赖」，
  需要一条 D0xx 才能做。**不要靠"文档只写了运行时"这个措辞空子绕过去**——
  这正是 IMPLEMENTATION-PLAN `:13` 说的「实现发现规则冲突时先停下，更新文档或新增决策，不用代码"自行解释"」。

⚠️ 补充一条容易被忽略的事实：Playwright 的成本**不只是一个 devDependency**。
它要下载浏览器二进制（本机 `~/.cache/ms-playwright` 不存在），CI 要装系统库，
且批次 2 的硬约束「CI 不得依赖任何 AI/SCM 凭据或仓库秘密」虽不直接相关，
但「CI 装浏览器」会显著改变现有四个 job 的形态与时长。这属于**运行环境变更**，不只是包清单变更。（推导）

### 4.5 不新增依赖时，三项各能做到什么 / 做不到什么

| 验收项 | 能做到 | 做不到 | 依据 |
|---|---|:--|---|
| **浏览器** | ❌ 基本做不到。只能做 jsdom 组件级挂载 + 事件触发，以及像批次 1 那样用 `curl` 沿 nginx `/api/` 走一遍网络路径 | 真实渲染、真实 CSS 级联、真实事件派发链、真实 SPA 深链接渲染、控制台/网络错误检查（`quality-guidelines.md:73` 明确要求这一条） | 探针 3：本机无浏览器、无 DISPLAY |
| **可访问性** | ⚠️ **部分能做**：landmark（`header`/`nav`/`main`）、`aria-label`、`aria-labelledby`、标题层级、`label[for]`↔控件 `id` 关联、`role="alert"`、skip link 的 `href` 指向、原生控件而非可点 `div`、状态旁是否有文字（非纯色彩）——这些都是**纯 DOM 断言**，vitest+jsdom 完全可测 | 对比度（无 CSS）、焦点可见性（`:focus-visible` 是 CSS）、**真实 Tab 键顺序**（jsdom 不实现顺序焦点导航，只能手动 `.focus()`）、屏幕阅读器可达性、目标尺寸（无布局） | 探针 1/2：`STYLESHEETS=0`、`boundingRect=0` |
| **响应式** | ❌ 做不到（行为层） | 1440/768/390 三档任何一档的实际布局、横向溢出、断点切换 | 探针 1/2：无布局、`matchMedia === undefined` |
| **视觉漂移** | ❌ 做不到（行为层）。只能做**文本级**检查：像 `motion.spec.ts:11-16` 那样把 CSS 当文件 `readFile` 后 grep，或扩展 `lint.mjs` 增加静态规则（例如「`button-quiet` 被使用但未定义」这类死类名检查——§1.7 那条今天没人抓到） | 截图基线、像素/结构 diff、token 是否真的生效 | 同上 |

**关于「视觉漂移」还有一条必须说清的原文事实**：
`.trellis/spec/frontend/quality-guidelines.md:55-59` 把这道闸门**本来就定义为人工检查**：

> For every visual or component change, **manually inspect** the selected direction at 1440, 768, and 390 CSS pixels and record the result when the change is substantial

`design-contract.md:56-57` 与 `component-guidelines.md:76-77` 同样写的是 check（人工）。
**所以「视觉漂移/响应式」缺的不是"规范要求自动化而我们没自动化"，而是"规范要求人工做而当前没人能做"**——

⚠️ **这才是批次 3 真正的死结**：D014（`DECISIONS.md:243-266`）把评审闸门委托给了**编排会话**，
而编排会话是一个**无浏览器、无显示器的 headless 容器里的 AI**，它**结构性地无法执行**
一条被规范定义为「肉眼在 1440/768/390 三档下看」的验收。
可选出路只有三条：(a) 授权装浏览器（含 devDependency 或系统包），
(b) 由用户本人做这一档并把结论写进 `result.md`，(c) **如实记为部分通过**。
**没有第四条。**（推导，但每一步依据都在上面）

### 4.6 批次 3 会不会重蹈批次 1 的覆辙——如实回答

批次 1 把 AC11 记为**部分通过**的原文理由（`result.md:213-215`）：

> **没有自动化的浏览器点击闭环**。…… 真正的「点开页面走一遍」没有自动化，
> 因为那需要引入 Playwright 之类的浏览器驱动，而本批次明确不新增依赖。
> **这是一个如实记录的缺口，不是已覆盖项。**

**回答：会，而且这次更严重——但也有一块可以真实改善。**

**为什么更严重**：批次 1 的 AC11 只要求「前端闭环、五条命令全绿、无新一级菜单」，
是一条相对宽松的条款。Phase 7 的原文是四项并列——
「三个一级页面完成**浏览器、可访问性、响应式和视觉漂移**验收」——
其中**三项**（浏览器/响应式/视觉漂移）在不加依赖时**行为上不可验证**（§4.3 已实测）。
批次 1 至少还能说「API 闭环有 `BatchOneApiTest`、代理跳有 curl」；
批次 3 要验的是**渲染与布局**，curl 和 JVM 内测试**一点忙都帮不上**。

**能真实改善的一块（不加任何依赖）**：
今天 `tests/` 下 5 个文件 15 个用例里，**没有一个是走完整旅程的**——
最接近的 `requirement.spec.ts` 只挂载需求详情页做了两次断言。
批次 3 完全可以在 vitest+jsdom 里写一个 **stub-fetch 全旅程组件测试**：
挂载真实 `App`，从 `/login` 开始，`trigger('submit')` 登录 → 建项目 → 加成员 →
写需求 → 置 READY → 指派 → 进 `/reviews/:id` → 认领 Finding → 标记 FIXED →
REQUEST_CHANGES → 新 Review → APPROVE → 回需求页置 DONE，
每一步都断言**真实 DOM 里出现了预期文本**、以及**发出的请求方法/路径/请求体**。
这在能力上是可行的（`routes.spec.ts:19-38` 已经证明可以挂载真实 `App` + 真实 router + stub fetch）。

**但必须同时如实写清它不是什么**：

- 它**不是浏览器**——没有 CSS、没有布局、没有真实事件链（探针 2 已量出 `STYLESHEETS=0`）。
- 它**证明不了**响应式、对比度、焦点可见、Tab 顺序、视觉漂移。
- 它**证明不了** nginx 那一跳（那仍需 curl，像批次 1 那样）。
- 它能证明的是：**路由与角色守卫、请求契约、以及"状态/血缘/置信度/Decision 四个标记没被合并"这类 DOM 结构约束**。
  ——最后这一条其实很有价值，因为 PRD `:131,:135` 那三条「不得合并」的规定，
  正好是纯 DOM 层面就能断言的，而它们恰恰是本页最容易违规的地方。

**因此我的建议（推导）**：批次 3 应当
(1) 写这个全旅程 jsdom 测试，把「点击闭环」从"完全没有"提升到"组件级有、浏览器级无"；
(2) **仍然把 Phase 7 的这条 AC 记为部分通过**，并在 `result.md` 里逐字写明
「浏览器/响应式/视觉漂移三项未自动化验收，原因是不新增依赖 + 本机无浏览器」；
(3) 把 OPEN-1（devDependency 是否可加）作为**批次 3 开工前必须裁定**的事项提给用户。
**把 (1) 说成"已完成浏览器验收"就是粉饰，比缺它更糟。**

---

## 5. 「三角色可重复演示」的可执行脚本

退出条件原文（`IMPLEMENTATION-PLAN.md:83`）：

> 退出：三角色可重复演示"需求→PR→Finding→退回→修复→新 Review→通过→DONE"；Revision/Diff 变化显示 `REVIEW_REQUIRED`。

配合 PRD §7「产品 E2E（Phase 7 退出标准）」十条（`PRD.md:166-177`）。

**前置**：三个账户 `lead` / `dev` / `rev`；一个真实 GitHub 仓库 + 可达的 webhook；
三个浏览器 profile 或串行登录/登出（`AppShell.vue:41` 有退出按钮）。

图例：✅ 今天有入口 ｜ ❌ **今天前端没有入口** ｜ 🔧 后端已有接口但无 UI ｜ 🌐 在 ForgePilot 之外发生

| # | 角色 | 操作 | 期望看到 | 入口 |
|---:|---|---|---|---|
| 1 | LEADER | 打开 `/`，被重定向到 `/login`（未登录）；注册并登录 | 进入 `/projects` | ✅ `LoginPage.vue:22-36`, `router.ts:19-21` |
| 2 | LEADER | 在「新建项目名称」输入并提交 | 列表出现该项目，徽章「我的角色：负责人」 | ✅ `ProjectsPage.vue:55-62,68-85` |
| 3 | LEADER | 进「成员管理」，加 `dev`(DEVELOPER)、`rev`(REVIEWER) | 两行成员出现 | ✅ `ProjectMembersPage.vue:137-153` |
| 4 | LEADER | 给两人填 SCM 外部 id 与用户名 | 「SCM 外部 id」不再是「未配置」 | ✅ `ProjectMembersPage.vue:199-221` |
| 5 | LEADER | **配置项目 SCM 仓库**（provider / externalId / apiBase / token / webhookSecret） | 仓库已连接，凭据不回显 | ❌🔧 `/projects/:id/settings` 是占位页（`routes.ts:91-96`）；后端在 `ScmController.java:38-44` |
| 6 | LEADER | **上传项目知识文档**，查看解析状态与失败原因 | 文档列表 + `status` + `failure_reason` | ❌🔧 同上占位页；ARCHITECTURE `:414,:422` 要求它在 `/settings` |
| 7 | LEADER | 建需求 + 至少 2 条 AC | 跳到需求详情，`AC-1`/`AC-2` 可见 | ✅ `RequirementsPage.vue:130-151` |
| 8 | LEADER | **运行需求质量检查** | 规则结果 + 一次 AI 结构化结果，归属当前 Revision | ❌ 前端无入口；**后端 Phase 6 也还没有** |
| 9 | LEADER | 置 `DRAFT → READY` | 徽章变「就绪」，编辑区变「发布新版本」 | ✅ `RequirementDetailPage.vue:223-235`, `status.ts:37-43` |
| 10 | LEADER | 指派给 `dev` | 状态自动变「开发中」（首次指派同事务） | ✅ `RequirementDetailPage.vue:237-250` |
| 11 | DEVELOPER | 登出→登录 `dev`，打开该需求 | 只读可见；无 LEADER 的编辑区（`editable` 为 false） | ✅ `RequirementDetailPage.vue:54-58` |
| 12 | DEVELOPER | **生成一次性实现建议** | 实现清单 + 相关规则 + 风险提示，**不产生聊天会话** | ❌🔧 后端 `RequirementController.java:93`；ARCHITECTURE `:423` 要求它在需求详情页 |
| 13 | DEVELOPER | 推 `feat/REQ-<n>-*` 分支并开 PR | webhook → 自动关联需求 → 自动建 PENDING Review | 🌐 + 后端已有（批次 2） |
| 14 | 任意 | **看到这个 PR 及其关联需求** | PR 号、base/head、关联需求 | ❌🔧 后端 `ScmController.java:53-57`；**批准的七条路径里没有 PR 页**（见 §7 OPEN-3） |
| 15 | 任意 | 回需求列表，看 `review_activity` | 需求状态与评审活动**两个独立字段**，显示 `PENDING`/`REVIEWING` | ⚠️ 容器有（`RequirementsPage.vue:177-185`）但 `ReviewActivity` 只有 `NO_PR`（`status.ts:10`） |
| 16 | REVIEWER | 登录 `rev`，点一级菜单「代码审查」 | Review 列表 | ❌ `/reviews` 是占位页（`routes.ts:109-114`） |
| 17 | REVIEWER | 打开 Review 详情 | AC 覆盖判定（`COVERED/NOT_FOUND/AT_RISK`）+ 带证据的 Finding + **未审查文件清单** | ❌ `/reviews/:id` 是占位页（`routes.ts:115-120`）——**Phase 6 退出条件** |
| 18 | REVIEWER | 点 Finding 的证据，回溯到 AC / 知识 excerpt / 代码行 | 三类证据均可点击定位（PRD `:171`） | ❌ |
| 19 | REVIEWER | 确认部分 Finding（`OPEN → CONFIRMED`），驳回另一部分（`→ REJECTED`） | 状态徽章更新，`finding_event` 留痕 | ❌ Phase 7 |
| 20 | REVIEWER | 对整个 Review 做 `REQUEST_CHANGES` 并留备注 | Decision 写一次；需求页 activity 变 `CHANGES_REQUESTED` | ❌ Phase 7 |
| 21 | REVIEWER | 再试一次 APPROVE | 被拒（同 head 已有 REQUEST_CHANGES，只能靠新 head 解除） | ❌；**这一条是演示里最该给人看的一条**（ARCHITECTURE `:275,:284`） |
| 22 | DEVELOPER | 认领 Finding（`CONFIRMED → IN_PROGRESS`） | 认领人显示为 `dev` | ❌ Phase 7 |
| 23 | DEVELOPER | 修复后推新 head SHA | 自动产生**新** Review，旧 Review 完整保留 | 🌐 + 后端 Phase 6 |
| 24 | DEVELOPER | 标记 Finding 已修复（`IN_PROGRESS → FIXED`） | 状态更新 | ❌ Phase 7 |
| 25 | 任意 | 打开新 Review | 上轮已驳回且两个 hash 均未变的 Finding 显示为**已抑制**（`status=REJECTED` + `continuity=SUPPRESSED`），且**与人工状态分列两个标签** | ❌；PRD `:131,:173` |
| 26 | REVIEWER | 复验：通过（`FIXED → VERIFIED`）或打回（`FIXED → IN_PROGRESS`） | 两条路径都要演示 | ❌ Phase 7 |
| 27 | REVIEWER | 对新 Review 做 `APPROVE` | **只结束当前 Review**，需求**不**自动 DONE（P9） | ❌ Phase 7 |
| 28 | LEADER | 登录 `lead`，在需求详情点「置为 已完成」 | 需求 `IN_DEVELOPMENT → DONE` | ✅ `RequirementDetailPage.vue:223-234`, `status.ts:40` |
| 29 | LEADER | 发布需求新 Revision（或改 Diff） | 关联 PR 显示「审查已过期」/ `REVIEW_REQUIRED`，旧 Review 不能对当前输入终局 | ⚠️ 发版本有入口（`RequirementDetailPage.vue:253-277`）；**「已过期」的展示无入口** |
| 30 | 任意 | 用 B 项目用户猜 A 项目 id | 一律不可见（404 与「不存在」不可区分） | ✅ 后端已证（批次 1 AC5）；前端表现为错误提示 |

**统计**：30 步里，✅ 完整可走 **9 步**，⚠️ 部分可走 **2 步**，🌐 应用外 **2 步**，
❌ **前端毫无入口 17 步**（其中 6 步后端已有接口、11 步连后端都在批次 3 里）。

**推导：Phase 7 的退出条件今天连"演示一次"都做不到，更别说"可重复演示"。**
批次 3 的前端工作量 = 一个全新的一级页面（代码审查，两条路由）
+ 一个占位页转真页（项目设置：SCM + Knowledge）
+ 需求详情页加三块（质量检查、实现建议、关联 PR / 已过期提示）
+ `ReviewActivity` 从 1 值扩到 8 值。

---

## 6. 缺口清单（要什么 / 有什么 / 差什么）

| # | 要什么（原文出处） | 有什么 | 差什么 | 阻塞级别 |
|---:|---|---|---|---|
| G1 | Review 详情只读页（`PLAN:76`，Phase 6 退出条件） | `/reviews/:id` 占位页 | 整页：身份四元组、当前/已过期、AC 判定、Finding+证据、coverage manifest、执行/失败状态 | **Phase 6 退出闸门** |
| G2 | 代码审查一级页面（`ARCH:409,417`） | `/reviews` 占位页 | 整页：Review 列表（按 PR / 需求聚合） | **Phase 7 「三页面」** |
| G3 | Finding 人工生命周期（`PLAN:80`，PRD `:124-131`） | 无 | 七状态 + 认领/标记/验证/打回/重开 + `finding_event` 审计展示；**且 status 与 continuity 必须两个标签** | **Phase 7** |
| G4 | Review Decision 决策区（`PLAN:81`） | 无 | APPROVE / REQUEST_CHANGES 一次性写入 + 六项前置不满足时的**可解释禁用态** | **Phase 7** |
| G5 | 项目设置页（`ARCH:414`：SCM + Knowledge） | 占位页 | SCM 仓库注册/更新（凭据不回显）、知识上传 + `status`/`failure_reason`（`ARCH:422`） | **Phase 7 「三页面」之「项目」** |
| G6 | 一次性实现建议在需求详情页（`ARCH:423`） | 无（后端有） | 需求详情页一个区块；**不得建 Conversation 页** | Phase 7 E2E（PRD `:169`） |
| G7 | 需求质量检查展示（`PLAN:68`） | 无（后端也无） | 需求详情页一个区块，结果归属 Revision | Phase 6 |
| G8 | `ReviewActivity` 八个取值（PRD `:106-117`） | `status.ts:10` 只有 `NO_PR` | 扩类型 + 八个中文标签 + `MIXED` 的各状态计数展示（PRD `:117`） | **会直接让 `typecheck` 失败** |
| G9 | 「审查已过期 / `REVIEW_REQUIRED`」提示（`PLAN:83`，PRD `:176`） | 无 | 需求详情页与 Review 详情页各一处 | **Phase 7 退出条件** |
| G10 | PR 的展示与关联纠正（PRD P1，D016.2） | 无（后端有 `GET`/`PUT`） | 落点未定（七条路径里没有 PR 页）→ **OPEN-3** | Phase 7 E2E |
| G11 | 证据可点击回溯到 AC/知识/代码行（PRD `:171`） | 无 | Review 详情页内的证据定位机制 | Phase 7 E2E |
| G12 | 三页面的浏览器/可访问性/响应式/视觉漂移验收（`PLAN:82`） | 五条命令；15 个 jsdom 用例；人工检查清单（`quality-guidelines.md:55-75`） | **浏览器/响应式/视觉漂移无自动化，且本机无浏览器可做人工**（§4.3/§4.5） | **需要裁定 → OPEN-1 / OPEN-2** |
| G13 | 死类名 `button-quiet`（§1.7） | 14 处使用，0 处定义 | 补样式或删类名 | 低（但它证明视觉闸门从未真跑过） |

---

## 7. 未回答的问题、假设、必须裁定的开放项

### 必须在 design 阶段裁定的开放项

**OPEN-1（最重要）：devDependency 是否在「不新增依赖」禁令之内？**
- 权威文档只写「**运行时**依赖」（`PLAN:116`、`DECISIONS:257`、`PLAN:129`）。
- 但 D015.8（`DECISIONS:380,384`）**已经把只可能是 test scope 的 WireMock/MockWebServer 当作「新增依赖」明确禁止**，
  且 `frontend/scripts/lint.mjs:57-63` 把 `devDependencies` 与 `dependencies` 合并检查。
- **原文没有正面回答，因此这是一条需要裁定的开放项。**
- 我的推荐（推导）：按 D015.8 的先例，**视为在禁令内，需要一条 D0xx 才能加**。
  若用户决定放行 Playwright，那条决策必须同时回答：CI 里怎么装浏览器、四个 job 的时长变化、
  以及「holdout 锁死 Phase 8」等三条不可放松规则是否受影响（结论应为不受影响，但要写明）。

**OPEN-2：「浏览器/响应式/视觉漂移验收」由谁执行？**
D014 把闸门委托给编排会话，而编排会话**无浏览器、无 DISPLAY**（§4.3 探针 3），
`quality-guidelines.md:55-59` 又把这道闸门定义为**人工在 1440/768/390 三档下看**。
三条出路（授权装浏览器 / 用户本人做 / 如实记部分通过）必须**先选一条**，
否则会到批次 3 收尾时才发现这条 AC 无人可执行。

**OPEN-3：PR 对象的页面落点。**
批准的七条路径里没有 PR 页，但 PRD P1 要求「页面下拉框可改可清除」关联需求，
且 D016.2 要求批次 3 补上 DEVELOPER 的半条授权。
放在 `/reviews/:id` 头部？放在 `/requirements/:id` 的「关联 PR」区？两处都放？
**不能新增第八条路径**（那是新增一级页面之外的路由，仍需按 `PLAN:116` 走决策）。

**OPEN-4：`/reviews` 列表页的内容与聚合维度。**
Phase 6 只说「Review 详情只读页可用」，没说列表页。但一级菜单「代码审查」必须点得开。
列表按什么聚合（PR / 需求 / 时间）、显示哪些列、是否分页——原文无规定。

**OPEN-5：Finding 数量大时的分页/虚拟化。**
ARCHITECTURE §7.2（`:458`）允许单 PR 最多 300 个 changed files。
Finding 数量上限无规定，前端一次性渲染是否可接受，未有结论。

**OPEN-6：三角色演示时的会话切换。**
D013.7 是进程内 `HttpSession`，同一浏览器同时只有一个会话。
可重复演示是要求串行登出/登录，还是三个浏览器 profile？演示脚本必须写死一种。

### 我做的假设（若不成立，本文相应结论作废）

- **A1**：批次 2 的「前端未改动」是准确的。我用 `git log -- frontend/src/` 独立核实过（§1.2），
  且 `npm run build` 产出的 `index-DB71gMCW.js 117.18 kB` 与批次 1 `result.md:111` 逐字一致——**双向印证**。
- **A2**：我复用了既有 `node_modules`，**没有跑 `npm ci`**。
  `quality-guidelines.md:41-47` 要求的完整序列以 `npm ci` 开头。
  因此我的四条输出证明的是「当前 node_modules 下全绿」，**不等于**批次 1 那种「删掉 node_modules 全新安装后全绿」。
  批次 3 收尾自证时必须重跑完整五条。
- **A3**：`/tmp/fp-probe` 的两个探针**没有改动仓库任何文件**（探针文件全在 `/tmp`，
  vitest 用 `--root /tmp/fp-probe` + 符号链接的 `node_modules` 运行）。
  `git status` 中 `frontend/` 应保持无改动——**请在采信本文前自行 `git status` 复核**。
- **A4**：`vitest` 的 `test.css` 默认值是 `false`——这一条我**没有**只依赖文档，
  而是实测出 `STYLESHEETS=0`（§4.3 探针 2）。

### 我没有回答的

- **没有实际启动过 dev server 或 compose 来看页面**。本文所有关于「页面长什么样」的判断
  都来自读源码，不是来自看渲染结果。（无浏览器，无法看。）
- **没有验证批次 3 的后端接口形状**——它们还不存在。§3.2 的字段表是从
  ARCHITECTURE §2.1/§3 推导出来的**需求清单**，不是已实现的契约。
- **没有评估工作量/排期**。
- **没有检查 `nginx.conf` / `Dockerfile`** 是否需要为新页面调整（SPA 回落已由批次 1 的 curl 验证过 `GET /requirements` 返回 200）。

---

## Related Specs

- `.trellis/spec/frontend/index.md` — 八份前端规范索引
- `.trellis/spec/frontend/design-contract.md` — 方向 B「精密审查台」；`:26-30` 明确要求 Finding 生命周期 / AI 置信度 / 需求状态 / Decision / review activity **各自独立标签**
- `.trellis/spec/frontend/quality-guidelines.md` — `:37-53` 五条命令；`:55-75` **人工**视觉漂移清单（8 项）
- `.trellis/spec/frontend/component-guidelines.md` — `:64-77` 可访问性契约
- `.trellis/spec/frontend/motion.md` — `:16-17` 验证方式 = `motion.spec.ts` + **人工**在 1440/768/390 三档看
- `.trellis/tasks/archive/2026-08/08-21-batch-1-auth-project-requirement/api-contract.md` — 「契约先行」的写法样板，批次 3 照此办理

## External References

无。本次研究未使用外部检索；所有结论来自本仓库文件与本机实测。
