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

## 4. 迁移 `V6__review.sql`（实测支撑，见 `research/finding-constraint-trigger-measured.md`）

### 4.0 先纠正我自己给研究委托写错的一条前提

我在研究委托里把「父子上下文」写成「file_path / line 等」。**这是错的**，而且方向性地错。
`ARCHITECTURE.md:200` 原文：

> 使用 `IS NOT DISTINCT FROM` 保证 Finding 的 **`requirement_id` 与 `requirement_revision_id`**
> 分别等于父 Review 对应列。Review 未关联 Requirement 时，Finding 两列必须都为空。

「父子上下文」是这**两列**的跨行不变式。`path` / `line` 是 Finding 自己的列，**没有对应的父列**，
谈不上「与父一致」，实测确认它们在数据库层零约束（`line = -9999` 直接落库）。
它们的正确性归 `ARCHITECTURE.md:342-343` 的 Validator（`filePath` 必须在 changed files 内、
行号必须落在 patch 可验证范围），那是**应用层**规则。
照我原来那个错前提设计，会做出一个规范没要求、也无法自洽的触发器。

### 4.1 表与约束（不依赖研究的部分）

- 三张表 `review` / `finding` / `finding_event`，建完恰好 **16 张**，此后不得再有新表。
- `review` 唯一键**逐字**写成
  `UNIQUE NULLS NOT DISTINCT (pull_request_id, head_sha, review_input_fingerprint, requirement_revision_id)`。
  写成默认的 `NULLS DISTINCT`，未关联需求的 PR 就能堆积无限条同四元组 Review，
  「当前 Review」从单数变成集合，整张单 PR 映射表失去意义，`ARCHITECTURE.md:261` 的幂等也一并失效。
- `review` 需 `UNIQUE(project_id, id)`（供 Finding 父 FK）与
  `UNIQUE(project_id, id, execution_attempt)`（供 §6.1 的 attempt fence）。
- `finding` 需 `UNIQUE(project_id, id)` 供 `finding_event` 父 FK。
- `ARCHITECTURE.md:206-220` 的四条行内 CHECK 逐字落地，外加 §2.7 裁定的
  `CHECK (decision = 'PENDING' OR status = 'COMPLETED')`。
- 同时补 `ai_call_log.review_id` 的外键（[D015.1](../../../docs/v2/DECISIONS.md#d015)），
  并反转 `aiCallLogHasNoReviewForeignKeyYetAndNoRowsUsingIt` 的断言。

**必须写进迁移注释的承重点**：`review` 的三列复合外键
`(project_id, requirement_id, requirement_revision_id) → requirement_revision(project_id, requirement_id, id)`
在 `MATCH SIMPLE` 下，任一列为 NULL 整条外键**就被跳过**。
让它承重的是那条「同空或同非空」的 CHECK——与批次 2 `requirement_attachment` 的 NOT NULL 同型
（[D015.2](../../../docs/v2/DECISIONS.md#d015)）。**删掉那条 CHECK，外键会静默失效。**

### 4.2 约束触发器用 **IMMEDIATE**，不用 `INITIALLY DEFERRED`

实测的延迟模式行为其实不坏：违规语句成功返回、事务全程可用、**不进 `25P02`**、
COMMIT 报 `23514` 并**点名具体 finding id**；JPA 侧是 `DataIntegrityViolationException`
直接包 `PSQLException(23514)`，`getServerErrorMessage().getConstraint()` 拿得到约束名。

**但延迟有两个实测陷阱，而且都很难查**：

- 先插违规行**再改对** → COMMIT **仍然失败**；
- 先插违规行**再删掉** → COMMIT **仍然失败**。

原因是延迟事件绑定在**行版本**上，不是事务的最终状态。

**裁定：用 IMMEDIATE。** 理由是延迟在本项目**买不到任何东西**——
Finding 的上下文是插入时从父 Review 复制的，不存在需要临时违规的合法场景。
延迟只会把「第 3 条 Finding 写错了」的报错时点推迟到 COMMIT，还附赠两个陷阱。

（顺带实测结论保留在研究里：[D015.9](../../../docs/v2/DECISIONS.md#d015) 担心的绕过口子**真实存在**——
在 JPA 事务内用 `doWork()` + savepoint + `SET CONSTRAINTS IMMEDIATE` 能救回并成功提交。
本批次选 IMMEDIATE 使这条路径不可达，算是顺手关掉。）

### 4.3 父表那一半走 `ARCHITECTURE.md:200` 明确给出的**更简单的分支**

原文是「触发器也必须覆盖对父 Review 上下文列的更新，**或**直接拒绝这些身份列在创建后变化」。

**裁定：取后者。** `review` 上加一个 `BEFORE UPDATE` 触发器，
拒绝 `pull_request_id` / `head_sha` / `review_input_fingerprint` /
`requirement_id` / `requirement_revision_id` / `review_input_fingerprint` /
`context_snapshot_json` 在创建后发生变化。

**三个理由**：

1. 这几列**本来就是 Review 的身份**（四元组）与 `ARCHITECTURE.md:102` 明文标注的
   「**不可变的** `review_input_fingerprint`/`context_snapshot_json`」。
   改它们等于把一条 Review 改成另一条 Review，语义上就不该允许。
   §2.1 只是「说」它们不可变，这条触发器让它**真的**不可变。
2. 实测发现「函数包装的 CHECK」在 PG15 上被接受且真能在 INSERT 上拦住，
   但**挡不住父表更新**——也就是说单靠 CHECK 一定要配一个父侧机制。取本分支正好补上。
3. **它顺手关掉了一个实测出来的并发写偏斜**：研究实测「并发插 Finding vs 改 Review 上下文」
   触发器本身挡不住，目前挡得住**纯属** D003 唯一键含 `requirement_revision_id` 的**副作用**。
   身份列一旦不可变，这个场景**根本不可达**——从「靠副作用」变成「靠设计」。

### 4.4 批量插入：整批回滚是既定事实，靠**写入前校验**保住好行

实测：延迟与非延迟**都整批回滚**，差别只是什么时候知道。
唯一能保住好行的是写入前校验，或每行独立事务（后者会制造「部分成功报告」，
被 `ARCHITECTURE.md:334` / P6 明令禁止）。

**裁定**：Finding 的上下文在写入前由 `ReviewOutputValidator` 校验
（它本来就要校验 `acId` / `sourceId` / `filePath` / 行号，多校验两列上下文是顺手的）。
触发器是**最后一道防线**，不是第一道。这与 `ARCHITECTURE.md:341` 的 Validator 职责一致。

一次 Review 的全部 Finding 与终局状态**同一事务**提交——
这同时满足 §6.1 fence 的预防性（研究 §3.9 的隐藏前提正是「有一个已经碰过 finding 行的事务正开着」）。

### 4.5 补一条 §2.3 索引清单里没有的索引

实测：血缘外键 `(project_id, carried_from_finding_id)` 无索引时删 2000 行 **7.04 s**，
有索引 **176 ms**（40×）。PostgreSQL 不为外键的**引用侧**自动建索引。

**裁定**：建 `idx_finding_carried_from (project_id, carried_from_finding_id)`。
这是索引，不是列也不是表，不触及 §2.1 的清单；不建它会让任何删除路径慢 40 倍。

### 4.6 Flyway 实测已关闭前两批遗留的 caveat

实测确认 `CREATE CONSTRAINT TRIGGER` + `$fn$` plpgsql 函数体 + `UNIQUE NULLS NOT DISTINCT`
+ D015.1 的补外键，**六个迁移全绿**。批次 1 与批次 2 各自遗留过「Flyway 能否处理 plpgsql 函数体
分隔符」的 caveat，本批次实测关闭。

D013.1 变体 A 的映射方式在 `finding` 上**照样适用**——实测自然写法仍然启动即
`MappingException: Column 'project_id' is duplicated`，变体 A 读写与关联导航全部正常。

## 5. 执行器与调度（实测支撑，见 `research/after-commit-scheduling-measured.md`）

### 5.1 触发必须是**两个方法**，不是一个方法挂两个注解

实测：同一方法同时标注 `@EventListener` 与 `@TransactionalEventListener(AFTER_COMMIT)` 时
**只被调用一次，且在提交之后**——事务内那一半被静默丢掉。

**裁定**：

```text
@EventListener                                  → 同事务内幂等创建/取得 Review(PENDING)
@TransactionalEventListener(AFTER_COMMIT)       → 提交后才把它交给执行器
```

两个方法各自成立，缺一不可。写成一个方法会让 `ARCHITECTURE.md:261`
「监听失败则整个 SCM 事务回滚」**永远不可能触发**——因为那一半根本没跑。

### 5.2 AFTER_COMMIT 里绝不能用 EntityManager，也绝不能用裸 JdbcTemplate

实测三个反直觉读数：AFTER_COMMIT 阶段 `isActualTransactionActive()` 仍返回 `true`、
`isConnectionTransactional()` 也返回 `true`，但物理连接的 `autoCommit` 已被恢复成 `true`。

- `EntityManager.persist` / `Repository.saveAndFlush` → 显式失败（`No active transaction`）。**好事**。
- 裸 `JdbcTemplate.update` → **成功并立即提交**，落在一条已提交连接上、单语句自动提交、无法回滚。

**裁定**：AFTER_COMMIT 回调内若需写库，**只能** `REQUIRES_NEW`。
禁止用 `isActualTransactionActive()` 判断「我在不在事务里」——实测它必然判错。

### 5.3 调度失败对调用方不可见，reconciliation 是唯一恢复路径

实测：AFTER_COMMIT 监听器抛出的异常由 Spring 的
`TransactionSynchronizationUtils.invokeAfterCompletion` `catch (Throwable)` 掉，只打一条 ERROR 日志。
webhook 依然返回 **202**，PR 行与 PENDING Review **已提交**，调用方看不到任何异常。
执行器满时的 `TaskRejectedException` 走同一条静默路径。

这与 `ARCHITECTURE.md:263` 完全一致（「after-commit 提交失败不回滚已提交的 PR/PENDING，
而是保留 PENDING 供 reconciliation 恢复」）——**实测确认这不是缺陷，是设计**。
但它意味着：**reconciliation 不是兜底，是唯一恢复路径**，必须有测试证明它真的恢复。

### 5.4 并发上限必须落在 `corePoolSize` —— 这是本批次最隐蔽的一个坑

实测 Boot 4.1 的默认 `applicationTaskExecutor` 是 `core=8 / max=2147483647 / queue=2147483647`，
**直接复用等于 8 路并发 + 无界积压**，与「冻结为 1 或 2」正面冲突。

更要命的是：`ThreadPoolExecutor` **只有队列满了才会扩到 `maxPoolSize`**，
而默认队列无界 → 永远不满 → 永远只有 `corePoolSize` 个线程。
实测 `core=1 / max=4 / 默认队列` 提交 8 个任务，池中**始终只有 1 个线程**。

> **裁定**：并发上限写在 `corePoolSize`，并**必须同时显式设 `queueCapacity`**。
> 只写 `setMaxPoolSize(2)` 是一个**看起来正确、实测无效**的写法，
> 而且任何「跑通一个 Review」的测试都会给它报绿——正是 `prd.md` §7 风险 3 的具体实例。
> 因此本批次**必须有一条断言直接检查执行器的 `corePoolSize`**，而不是只断言「Review 能跑完」。

### 5.5 声明自己的 Executor 会让 Boot 的默认 Executor 整个消失

实测：上下文里存在**任何** `Executor` 类型的 bean，Boot 的 `applicationTaskExecutor` 就不再创建。

ForgePilot 当前不用 MVC async（无 `Callable` / `DeferredResult` / SSE），因此**当前无实害**。
**裁定**：接受这个后果，但在 `ReviewExecutorConfig` 的 javadoc 里写明——
它是一个「以后加 SSE 时会突然踩到」的雷，留一句注释比留一个惊喜便宜。
本批次明确不建 SSE（`prd.md` §3），所以不为它提前加规避。

### 5.6 连接池是并发的硬天花板

`application.yml:9`：`maximum-pool-size: ${FORGEPILOT_DB_POOL_SIZE:5}`（我已核实）。
并发 Review 会占用连接：并发 2 就只剩 3 条给 Web 层。
**这个交互必须一起进 §6 的 4 GB 实测**，不能只量内存。

### 5.7 reconciliation 的禁令用一条**结构规则**执行

`ARCHITECTURE.md:265` 禁止「按当前 head + 当前上下文无 Review 补建缺失 Review」。
实测对照：以 `pull_request` 为驱动表的 `NOT EXISTS` 补建查询返回一行（会建出不该有的 Review），
以 `review` 为唯一驱动表的恢复查询返回空。

> **裁定**：reconciliation 的查询里，**`FROM` 子句只准出现 `review`**。
> 这条规则可以被 code review 一眼检查，比「记得不要补建」可靠。

## 6. fencing 与并发（实测支撑，见 `research/fencing-and-concurrency-measured.md`）

### 6.1 旧 Worker 插入 Finding 由**数据库**拒绝，不靠应用层查 attempt

实测：只靠应用层「先查 token 再插入」存在**真实 TOCTOU 破口**——
陈旧 attempt 的 Finding 能落到活 Review 下。

**裁定**：`finding` 上加复合外键
`(project_id, review_id, review_attempt) → review(project_id, id, execution_attempt)`，
配套 `review` 上的 `UNIQUE(project_id, id, execution_attempt)`。
实测控制组证明两个对象缺一不可：三列 UNIQUE 让 `execution_attempt` 成为键列从而产生**阻塞**，
三列 FK 才产生**拒绝**。

这条 fence 是**预防性**的：Worker 正在写 Finding 时，并发 re-claim 会**阻塞**而非抢走。

### 6.2 fence 的代价必须正面处理，否则崩溃的 Worker 会把 Review 永久钉死

实测：attempt 1 崩溃时已写了部分 Finding，reconciliation re-claim 递增 attempt 会撞外键：

```
ERROR: update or delete on table "review" violates foreign key constraint
       "fk_finding_review_attempt" on table "finding"
```

**裁定**：re-claim **同事务**先删除被放弃 attempt 的 Finding，再领取。
实测确认历史不受影响——`COMPLETED` 的 Review 根本领不到（领取条件只匹配 PENDING 或过期 RUNNING），
其 Finding 一行不动。

**禁止用 `ON UPDATE CASCADE`**：实测它把陈旧 attempt 的 Finding **静默改标**成新 attempt 的产出——
比没有 fence 更糟，它伪造证据。

### 6.3 Decision 的三件套缺一不可，尤其那把 PR 行锁

实测（最高危）：不加 `SELECT ... FOR UPDATE`，闸门的条件更新**不会阻塞**在并发的 SCM head 更新上，
而是对着**陈旧的 PR 快照**求值前置 3/4/5，于是在 head 已经推进到 `head2` 之后
仍然对 `head1` 的 Review 放行了 `APPROVE`：

```
 id | review_head | decision | pr_head_now
----+-------------+----------+-------------
  1 | head1       | APPROVE  | head2
```

机制：条件更新的 EvalPlanQual 只重查**目标行**（`review`），
连接进来的 `pull_request` 是普通快照读，不阻塞、不重算。
加锁后同一交错返回 0 行，正确拒绝。

**裁定**：`ARCHITECTURE.md:277` 的写法逐字照做，一个字都不省。
并且**必须有一条并发测试**证明「不加锁会放行」是被挡住的——单线程绿在这里毫无意义。

### 6.4 前置 5 必须写 `IS NOT DISTINCT FROM`

实测：写成 `=` 时，两侧都为 NULL 得 NULL，条件更新**永远 0 行**——
未关联需求的 PR 将**永远无法做出任何终局决定**。
读侧（activity 判定）与写侧（唯一键 `NULLS NOT DISTINCT`）用的是配对语义，**必须两侧都做**。

### 6.5 Decision Gate 必须是**派生**的，不能在 `pull_request` 上存布尔位

实测三种常见写错（看最新一条 Decision / 看有无 APPROVE / 判定里不带 head）
**都会错误解除封锁**。而 force-push 回旧 head 时，派生式判定会自动重新封锁，
布尔位则不会——那等于放行一个已被退回的 head。

**裁定**：Gate 判定恒为
`∃ review WHERE pull_request_id = ? AND head_sha = <PR 当前 head> AND decision = 'REQUEST_CHANGES'`。
不缓存、不存位、不看 Review 的新旧顺序。

### 6.6 `now()` 是事务开始时间（失活陷阱，非越权）

实测：长事务里 `lease_until < now()` 会漏判过期。这不会造成越权，只会让恢复变慢。
**裁定**：reconciliation 的领取查询保持短事务；不为此引入 `clock_timestamp()`——
它会让同一语句内多次求值不一致，代价大于收益。

### 6.7 `finding_event` 的 `is_a_change` 必须按 `action` 分支

实测：该表同时审计**状态**与**指派**两个维度，一条笼统的
「from 与 to 不相等」CHECK 在指派事件上会误判。
**裁定**：CHECK 按 `action` 分支写，与批次 2 `pull_request_requirement_event` 的
`ck_..._is_a_change` 同型但更细。

### 6.8 「只有继承的 SUPPRESSED 可重开」需要约束触发器

实测：`CHECK` 不能带子查询，表达不了这条；约束触发器可以。
但本项目 §2.1 **只为 `finding` 的上下文一致性授权了一个约束触发器**。

**裁定**：这条规则**由 Service 执行**，如实记为「非数据库执行」，
不为它新增第二个约束触发器——§2.1 的授权是逐个给的，不是给了一类。
Service 侧必须有条件更新（`WHERE status='REJECTED' AND continuity='SUPPRESSED'`）
而不是先查后写，并有并发测试。

### 6.9 审计行的 `from_status` 只有配合条件更新才是真的

实测：先查后写时，并发下会记录**两条互相矛盾的转移**（都声称从 OPEN 出发）。
**裁定**：所有 Finding 状态流转一律用
`UPDATE ... WHERE id = ? AND status = <期望的 from>` 条件更新，影响行数必须为 1，否则 409；
审计行的 `from_status` 取自该条件，而非取自先前那次读。
