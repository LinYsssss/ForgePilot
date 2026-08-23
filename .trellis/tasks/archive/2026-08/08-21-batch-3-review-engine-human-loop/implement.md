# 批次 3 实施计划

前置：`prd.md`（范围与规则）、`design.md`（十四项裁定 + 实测支撑）、`api-contract.md`（端点已冻结）、
`research/` 五份（四份含真实实测输出）。

## 0. 分工原则

- **地基我自己写**：迁移、实体、Repository、枚举。批次 2 的教训是地基交给代理会卡住，
  而且它是所有切片的共同依赖，卡住就全线停摆。
- **切片并行**，并发 ≤5，**文件范围严格不重叠**。每个代理拿到：明确文件清单 + 验收标准 +
  必须回报的五项（完成/未完成、改动文件、测试命令与结果、风险、假设）。
- **不采信代理总结**：每轮收口我回到实际文件与命令输出核对。

## 1. 第 0 轮（我自己做）——地基

| # | 产出 | 要点 |
|---|---|---|
| 0.1 | `V6__review.sql` | 三张表 + 触发器 + 索引 + `ai_call_log.review_id` 外键。按 `design.md` §4 逐条 |
| 0.2 | 实体与 Repository | `Review` / `Finding` / `FindingEvent` + 各自 Repository；D013.1 变体 A |
| 0.3 | 枚举 | `ReviewStatus` / `ReviewDecision` / `FindingStatus` / `FindingContinuity` / `FindingType` / `PullRequestActivity`(6) / `RequirementActivity`(8) |
| 0.4 | 摘掉 `reviewActivity` | `RequirementDetail` / `RequirementSummary` 删字段（`design.md` §2.1）。**先做**，否则第 1 轮 D、E 会撞同一个包 |
| 0.5 | 三处预期内破坏 | `FoundationDatabaseTest.EXPECTED_TABLES` → 16 张全名；smoke `expected_tables` → 16 张全名；反转 `aiCallLogHasNoReviewForeignKeyYetAndNoRowsUsingIt` |

**第 0 轮完成的判据**：`./mvnw -B -ntp verify` 全绿（此时新表已建、无业务代码），
且 `FoundationDatabaseTest` 断言的是 16 张全名而非数量。

## 2. 第 1 轮（5 个代理并行）——后端切片

文件范围**互不重叠**，全部在 `backend/src/main/java/com/forgepilot/review/`（除 E）。

| 代理 | 文件范围 | 核心验收 |
|---|---|---|
| **A 引擎核心** | `ReviewService` `ReviewExecutor` `ReviewExecutorConfig` `ReviewReconciliationScheduler` `PullRequestChangedListener` | 两个监听方法（`design.md` §5.1）；并发上限在 **corePoolSize** 且有**直接读 corePoolSize 的断言**（§5.4）；re-claim 同事务删弃用 attempt 的 Finding（§6.2）；reconciliation 的 FROM 只有 `review`（§5.7） |
| **B 输出与血缘** | `ReviewOutputValidator` `ChangedFileBatcher` `FindingContinuityCalculator` | 每条 AC 必有 verdict，漏项补 `NOT_FOUND`；任一 Batch 非法 JSON → **整个 Review FAILED**，绝不部分成功；两个 hash **不含 LLM 自由文本**；优先级 `SUPPRESSED > PERSISTING > NEW`；连续性只在同一 PR 内 |
| **C 人工闭环** | `ReviewDecisionService` `FindingLifecycleService` `ReviewController` `FindingController` | 六项前置 + **PR 行锁** + 条件更新，影响行数必须为 1（§6.3，**必须有并发测试**）；前置 5 用 `IS NOT DISTINCT FROM`（§6.4）；Gate 派生不存位（§6.5）；状态流转一律条件更新（§6.9）；角色矩阵**逐格照抄**，含 LEADER 那两个 ❌ |
| **D 活动派生** | `ReviewActivityService` `ReviewActivityController` `PullRequestActivity` `RequirementActivity` | 两个枚举**分开**（6 值 / 8 值）；`NO_PR`/`MIXED` 不得出现在单 PR 语境；counts 稠密 6 键；NULL 用 `IS NOT DISTINCT FROM`；activity 全部算在 `review` 侧，**不得**让 `requirement` 反向依赖 |
| **E 需求质量** | `requirement/RequirementQualityService` + 端点 | 仅 LEADER；结果归属具体 Revision；**不得改动**批次 1 的「DRAFT 修改时同事务清空 `quality_json`」逻辑 |

## 3. 第 2 轮——前端与补测

| 代理 | 范围 | 核心验收 |
|---|---|---|
| **F 前端三页** | `/reviews` `/reviews/:id` `/projects/:id/settings` | 三页从占位变实页；**不新增第八条路由**、**不新增一级菜单**；关联下拉框放 `/reviews/:id` 头部（§3.6） |
| **G 全旅程测试** | `frontend/tests/` | jsdom 挂载真实 `App` + 真实 router + stub fetch，走完三角色链路；断言 PRD `:131`/`:135` 那三条「不得合并」的 DOM 约束 |
| **H 补批次 2 欠账** | `backend/src/test/` | 三元组冻结的**并发**竞争；`GitHubClient.required()` 的**拒绝**分支；changed-file 超限分支；缺密钥启动失败 |

## 4. 第 3 轮（我自己做）——实测冻结与收口

| # | 事项 | 不可放松的理由 |
|---|---|---|
| 4.1 | **4 GB 目标机最大预算 Review 实测** | [D012](../../../../../docs/v2/DECISIONS.md#d012) 第 2 条与 [D014](../../../../../docs/v2/DECISIONS.md#d014) 第 6 条两处明确：运行边界是**实测输出**，不得预写常量 |
| 4.2 | 据实把并发冻结为 **1 或 2** | 同上。同时量连接池占用（`design.md` §5.6：Hikari 只有 5 条） |
| 4.3 | 人工检查清单 | `design.md` §3.5：把「无人可执行」变成「用户可执行」 |
| 4.4 | `result.md` + D014 逐条自证 | 部分通过必须记为部分通过 |

## 5. 明确的凑绿红线

`prd.md` §7 点了三处最容易凑绿的地方。实施时**逐条设防**：

1. **过期 Worker 的四条路径**（完成 / 标记失败 / 续租 / 插 Finding）各需一条断言。
   只测第一条就报绿是本批次最容易犯的错。
2. **并发上限**：必须有断言**直接读 `corePoolSize`**。
   只断言「Review 能跑完」对 `setMaxPoolSize(2)` 这种实测无效的写法**照样报绿**。
3. **失败判定**：断言必须证明「判定为 FAILED」，而不是「没抛异常」。
4. **PR 行锁**：必须有**并发**测试。单线程绿在这里毫无意义——
   实测证明不加锁时单线程完全正常，问题只在交错时出现。

## 6. 收口

1. `./mvnw -B -ntp verify` 全绿无 skip；`backend/pom.xml` 与 `frontend/package.json` 零改动。
2. Compose 空库冷启动断言 **16 张全名**。
3. CI 四 job 全绿，`ci.yml` 仍无 `secrets.*`。
4. 按 [D014](../../../../../docs/v2/DECISIONS.md#d014) 五条逐条自证，任一不成立就停。
5. 归档任务，记录 session。
