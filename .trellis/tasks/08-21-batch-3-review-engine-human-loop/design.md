# 批次 3 技术设计

> **状态：迁移与执行器两节待研究落地**（§4、§5）。本文先冻结**开放项裁定**（§2、§3），
> 因为它们决定模块边界与响应体形状，写代码前必须定死。

## 1. 设计原则（延续批次 1、2）

1. 首要判据：**不要复杂化；能运行、没有矛盾点。** 先找零新增表、零新增列、零新增抽象的可行解。
2. 文档之间的张力用**明确裁定收窄解释**消除，不用新增结构去同时满足两边。
3. 数据库能执行的约束不下放给 Service；数据库做不到的如实记为「非数据库执行」。
4. 收窄授权永远比放宽安全。规格没规定的授权，**默认不授予**。

## 2. Review activity 的裁定

研究（`research/review-activity-matrix.md`）的两条结论我已回原文核对，确认属实：

- **单 PR 值域恰为 6 个**：`REVIEW_REQUIRED/FAILED/CHANGES_REQUESTED/REVIEWING/PENDING/APPROVED`
  （`DECISIONS.md:122` 逐字规定）。
- **`NO_PR` 与 `MIXED` 只属需求级聚合**。`IMPLEMENTATION-PLAN.md:73` 的 8 值清单是两层的并集。

因此**用两个 Java 枚举，不是一个**：`PullRequestActivity`(6) 与 `RequirementActivity`(8)。
把它写成一个 8 值枚举按 PR 计算，会让 `NO_PR`/`MIXED` 出现在单 PR 语境里——那是无意义的值。

### 2.1 O2 / G8：activity 在哪个模块算 —— **裁定：`review` 独占**

`ARCHITECTURE.md:61` 的依赖方向是 `common, project, scm, knowledge, requirement, ai ← review`：
**`review` 依赖它们，不是反过来**。`:71` 又说 `review` 是唯一跨模块编排者。
而 activity 的计算同时需要 `pull_request`（scm）与 `review` 两张表。

**裁定**：activity 由 `review` 模块独占计算与暴露。具体：

- `requirement` 的 `RequirementDetail` / `RequirementSummary` **删除 `reviewActivity` 字段**
  （批次 1 那个恒为 `"NO_PR"` 的常量一并删掉）。
- `review` 侧新增两个只读端点（见 `api-contract.md`）：单需求的与项目内全量映射的。
- 前端需求页多发一次请求取 activity。

**理由**：任何让 `requirement` 去问 `review` 的方案都会造成 `requirement ↔ review` 双向依赖，
被 ArchUnit 的 `featureSlicesAreFreeOfCycles` 当场打回。
批次 2 已有先例：`scm` 需要 `requirement` 的数据时，走的是 `RequirementDirectory` 只读 facade
（[D015.6](../../../docs/v2/DECISIONS.md#d015)），方向是 `scm → requirement`，与依赖图一致。
这里方向要求相反，所以不能照搬 facade，只能把计算搬到 `review` 侧。

**代价**（如实记录）：需求列表页多一次 HTTP 往返。可接受——它换来的是依赖图不成环，
而成环会让 ArchUnit 的七条规则里最重要的一条失效。

### 2.2 O1 / G5：`DONE` 是否需要 review 前置条件 —— **裁定：不加**

`PRD.md:103` 与 P9（`:150`）：`→ DONE` 由 **LEADER 确认全部关联工作完成**，
且「**AI、Webhook、PR、Review 一律不得推进这些状态**」。

**裁定**：`DONE` 不加任何 review 前置条件，维持批次 1 的实现不变。

**理由**：三条独立证据同向——
(a) 「确认全部关联工作完成」是**人的判断**，不是可机检谓词（一个需求可以有多个 PR，
   哪些算「全部」只有人知道）；
(b) 加前置条件等于让 Review 参与推进需求状态，与 `:103` 那句禁令**直接冲突**；
(c) 加了就要让 `requirement` 反查 `review`，与 §2.1 的依赖方向冲突。

批次 1 的 `RequirementLifecycleTest` 已经在零 PR 情况下把需求置为 DONE，本裁定使其保持有效。

### 2.3 O3 / G4：已关闭的 PR 是否计入聚合 —— **裁定：照字面全部计入，不加 `state` 列**

我核实过：`pull_request` 确实没有 state/merged/closed 列（`V5__scm.sql:43-85`）。

**裁定**：需求级聚合计入**全部**关联 PR，不加状态列。
LEADER 可按 P1 清除关联作为逃生口，且该操作有 `pull_request_requirement_event` 完整留痕。

**理由**：加 `state` 列要求我自行定义 merged 与 closed 的语义差别——权威文档一个字没写，
而 Phase 5 的 PR 快照契约也没有把 state 纳入。**为一个演示里不会出现的场景，
去发明一套原文没有的语义，正是「复杂化」。** P1 已经授权 LEADER 随时改关联，逃生口是现成的。

**这是一个如实记录的产品限制**，必须写进 `result.md`：
*一个被关闭而未合并的 PR 会持续计入其关联需求的评审活动聚合，
LEADER 需要手动清除关联才能让该需求脱离该 PR 的影响。*

### 2.4 O4 / G6：关联「改走再改回」使旧 Review 复活 —— **裁定：照字面实现，两个维度并列展示**

场景：PR head `H1` 关联需求 A 得 `APPROVE`；改关联到 B 重审得 `REQUEST_CHANGES`；再改回 A。
此时旧的 `APPROVE` 重新满足当前有效性 → activity 显示 `APPROVED`，
但 Decision Gate 看 `(PR, H1)` 上存在 `REQUEST_CHANGES`，该 head 已被永久锁死。

**裁定**：照字面实现。activity 显示 `APPROVED`，Decision Gate 照样拦截，
**UI 必须在该 head 上并列展示「此 head 已有退回」标记**。

**理由**：`ARCHITECTURE.md:267-273` 用三行把 Identity / Current Validity / Decision Gate
明确列为**三个不同概念**——它们本来就被允许各说各话。
关键在于**闸门是安全属性，activity 是展示量**：闸门拦住了 APPROVE，没有任何不安全的事发生。
候选方案 (b)「让 activity 吸收 Decision Gate」会改动 `PRD.md:112` 的充要条件，**那是改规格**，不在授权内。

需要注意但不构成问题的一点：LEADER 可能看到 `APPROVED` 就把需求置 DONE。
这是可接受的——按 §2.2，DONE **本来就是**人的判断，没有任何自动闸门被绕过。

### 2.5 O5：`review.requirement_id` 是否进当前有效性 —— **裁定：不进**

`ARCHITECTURE.md:283` 的前置条件只列了 `requirement_revision_id`。
且 `review` 上有 CHECK 保证 `requirement_id` 与 `requirement_revision_id` 同空或同非空，
而一个 Revision 只属于一个 Requirement（函数依赖），因此匹配 revision **蕴含**匹配 requirement。
加进去是冗余谓词，还会多一个索引列。**四元组就是四元组。**

### 2.6 O6 / G1：lease 过期但未回收的 RUNNING 显示什么 —— **裁定：仍显示 `REVIEWING`**

**理由**：`PRD.md:113` 只提 `status`。让 activity 感知 lease 会引入原文没有的判定输入，
并使**同一行在不同时刻返回不同值**——那要求测试冻结 `Clock`，且让一个只读派生量依赖挂钟。
reconciliation 周期才是这个窗口的正确解，不是在展示层打补丁。

**如实记录**：lease 过期到 reconciliation 回收之间，页面会显示「审查中」而实际无 Worker 在跑，
窗口长度等于 reconciliation 周期。

### 2.7 O7 / G2：不可达的 `(status, decision)` 组合 —— **裁定：加 CHECK，让它不可存**

新增 `CHECK (decision = 'PENDING' OR status = 'COMPLETED')`。

**理由**：`ARCHITECTURE.md:202` 的措辞是「Review 与 Finding 还**必须具备**以下行内约束」——
「必须具备」是**下限**，不是穷举清单。而 `:279` 已把 `status=COMPLETED` 列为 Decision 的第一条前置。
把它写成 CHECK 只是把已有规定落到数据库，不是扩充规格。
本项目一贯偏好「数据库执行 > Service 执行」，而让非法状态**不可表示**又优于事后检测。

### 2.8 O8 / G3：`MIXED` 的 counts 形状 —— **裁定：稠密，6 个键恒在**

`RequirementActivity` 响应恒带 `counts`，含全部 6 个单 PR 值，未出现的为 `0`。

**理由**：响应形状稳定 → 前端类型非可选 → 少一类空值分支。多出的字节可以忽略。
`PRD.md:117` 只要求 `MIXED` 时必须有 counts，恒有它不违反该要求。

## 3. Finding 与前端的裁定

### 3.1 §5.1 `VERIFIED → CLOSED` 由谁 —— **裁定：LEADER / REVIEWER**

与「验证通过」同侧。状态机把 `VERIFIED` 与 `CLOSED` 列为两个状态，因此不合并；
但关闭是验证通过的自然第二步，交给同一批人最简单，且不扩大任何人的权限面。

### 3.2 §5.2 `REJECTED → OPEN`（重开）由谁 —— **裁定：LEADER / REVIEWER，且仅限 `continuity=SUPPRESSED`**

与「确认 / 拒绝」同侧。`PRD.md:127` 的「**仅**继承驳回项」必须由代码强制：
`continuity != SUPPRESSED` 的 `REJECTED` 是**不可逆终态**，任何角色都不能重开。
重开须写 `finding_event` 留痕（`:127` 明文要求）。

**注意**：重开后 `continuity` **保留** `SUPPRESSED`（`:131`：血缘事实不因当前状态改变而消失）。
把 continuity 一并改掉是错的。

### 3.3 §5.3 Finding 指派 —— **裁定：本批次不开独立指派端点**

「认领」即指派自己，并入 `CONFIRMED → IN_PROGRESS` 转换。
少一个端点、少一处授权面。PRD §3 只有「Finding 认领」（DEVELOPER），没有「指派他人」——
凭空造一个指派权就是**授予规格没给的权限**。

### 3.4 前端 OPEN-1：devDependency 是否在「不新增依赖」之内 —— **裁定：在**

权威文档措辞只写「运行时依赖」，但 [D015.8](../../../docs/v2/DECISIONS.md#d015) 已经把
只可能是 test scope 的 WireMock/MockWebServer 当作「新增依赖」明确禁止，
且 `frontend/scripts/lint.mjs:57-63` 把 `devDependencies` 与 `dependencies` 合并检查。

**裁定：视为在禁令内。本批次不加 Playwright 或任何浏览器驱动。**
不靠「文档只写了运行时」这个措辞空子绕过去——`IMPLEMENTATION-PLAN.md:13` 明确要求
发现规则冲突时先停下、更新文档或新增决策，不用代码自行解释。

### 3.5 前端 OPEN-2：浏览器/响应式/视觉漂移由谁验收 —— **裁定：如实记部分通过 + 交付人工检查清单**

实测（`research/frontend-phase7-gap.md` §4.3）：本机无浏览器、无 `DISPLAY`，
jsdom 无 `matchMedia`、`getBoundingClientRect()` 恒为 0、当前配置下 CSS 一字节不进 jsdom。
而 `.trellis/spec/frontend/quality-guidelines.md:55-59` **本就把视觉漂移定义为人工在 1440/768/390 三档下看**。

**裁定，三件事同时做**：

1. 写一个 **jsdom 全旅程组件测试**（挂载真实 `App` + 真实 router + stub fetch），
   覆盖三角色完整链路的**路由守卫、请求契约、以及 PRD `:131`/`:135` 那三条「不得合并」的 DOM 结构约束**。
   把点击闭环从「完全没有」提升到「组件级有」。
2. **交付一份用户十分钟可执行的人工检查清单**（`docker compose up` + 三档分辨率 + 逐条勾选），
   把「无人可执行」变成「用户可执行」。
3. **AC 仍记为部分通过。** 清单存在不等于闸门通过；jsdom 测试**不是**浏览器验收。
   把 (1) 说成「已完成浏览器验收」就是粉饰，比缺它更糟——批次 1 的 AC11 就是这样记的。

### 3.6 前端 OPEN-3：PR 关联控件放哪 —— **裁定：`/reviews/:id` 头部**

Review 详情页本就展示其 PR，关联下拉框放在该页头部。
需求详情页只**只读**列出关联 PR。**不新增第八条路由。**

### 3.7 前端 OPEN-4：`/reviews` 列表页 —— **裁定：按项目筛选的 Review 列表**

沿用需求页同样的 `?project=` 查询键。列：PR 编号、head SHA（短）、关联需求、
执行状态、Decision、是否当前有效、创建时间。按 `(created_at, id)` 倒序。
**MVP 不做分页**（如实记为限制）。

### 3.8 前端 OPEN-5 / OPEN-6

- Finding 一次性全量渲染，不做分页或虚拟化（如实记为限制）。
- 三角色演示**串行登出/登录**。[D013.7](../../../docs/v2/DECISIONS.md#d013) 是进程内会话，
  同一浏览器本来就只能有一个会话，演示脚本写死这一种。

## 4. 迁移 `V6__review.sql`

**待 `research/finding-constraint-trigger-measured.md` 与
`research/fencing-and-concurrency-measured.md` 落地后填写。**

已经确定、不依赖研究的部分：

- 三张表 `review` / `finding` / `finding_event`，建完恰好 **16 张**，此后不得再有新表。
- `review` 唯一键必须**逐字**写成
  `UNIQUE NULLS NOT DISTINCT (pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)`。
  写成默认的 `NULLS DISTINCT`，未关联需求的 PR 就能堆积无限条同四元组 Review，
  「当前 Review」从单数变成集合，整张单 PR 映射表失去意义，`ARCHITECTURE.md:261` 的幂等也一并失效。
- `review` 与 `finding` 各需 `UNIQUE(project_id, id)` 供子表复合父 FK。
- `finding` 的**永久**父 FK `(project_id, review_id) → review(project_id, id)`。
- `ARCHITECTURE.md:206-220` 的四条行内 CHECK 逐字落地，外加 §2.7 裁定的那条。
- 本批次同时补 `ai_call_log.review_id` 的外键（[D015.1](../../../docs/v2/DECISIONS.md#d015)），
  并反转 `aiCallLogHasNoReviewForeignKeyYetAndNoRowsUsingIt` 的断言。

**一处必须写进迁移注释的承重点**：`review` 的三列复合外键
`(project_id, requirement_id, requirement_revision_id) → requirement_revision(project_id, requirement_id, id)`
在 `MATCH SIMPLE` 下，只要任一列为 NULL 整条外键**就被跳过**。
让它承重的是那条「同空或同非空」的 CHECK——与批次 2 `requirement_attachment` 的 NOT NULL 同型
（[D015.2](../../../docs/v2/DECISIONS.md#d015)）。**删掉那条 CHECK，外键会静默失效。**

## 5. 执行器与调度

**待 `research/after-commit-scheduling-measured.md` 落地后填写。**

已经确定的边界：

- 不新增运行时依赖 → 执行器只能用 Spring 自带的 `TaskExecutor` 系列。
- 并发上限**必须实测冻结为 1 或 2**，不得预写常量（[D012](../../../docs/v2/DECISIONS.md#d012) 第 2 条、
  [D014](../../../docs/v2/DECISIONS.md#d014) 第 6 条，两处都明确不可放松）。
- reconciliation 用 `@Scheduled`，只恢复**已落库**的 PENDING/RUNNING，**禁止补建**。
  它不属于被禁的「兜底分支」：`ARCHITECTURE.md:181` 与 `:265` 明文要求它存在。
