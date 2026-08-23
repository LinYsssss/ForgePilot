# 批次 3 需求（Phase 6 + Phase 7）

授权依据：[D012](../../../../../docs/v2/DECISIONS.md#d012)（批次划分）、[D014](../../../../../docs/v2/DECISIONS.md#d014)（闸门执行者）。
上一批次：`.trellis/tasks/archive/2026-08/08-21-batch-2-ai-knowledge-scm/result.md`（§9 载有 D014 闸门自证，五条全部成立）。

> **状态：验收条件待研究落地后回填**（见 §8）。范围、边界与规则来自权威文档，与研究结论无关。

## 1. 为什么是这一批

批次 1 交付「需求是什么」，批次 2 交付「上下文从哪来」。
批次 3 交付**产品本身**——把需求、知识与代码变更合成一次审查，并让人做出终局决定。

这是十六张表的最后三张（`review`、`finding`、`finding_event`），也是**唯一一批直接对应论文主张的实现**：
「需求与项目知识上下文增强的智能代码审查」这句话，在这一批之前一个字都没有兑现。

**本批次之后不得再有新表。**

## 2. 范围

### Phase 6：Requirement Quality + Review Engine

1. **需求质量检查**：规则 + 一次结构化 AI Quality，结果归属具体 Revision，DRAFT 期正文一改即失效
   （批次 1 已实现「同事务清空 `quality_json`」，本批次填入真实内容）。
2. **唯一 Review Engine**：自动触发、人工触发、失败重试**最终共用** `ReviewService.requestReview(...)`
   （ARCHITECTURE §3.1）。不得因分批产生第二套 Pipeline（§3.4）。
3. **触发与幂等**：`review` 的**同步**监听器参加 `scm` 的同一事务，按四元组幂等创建或取得 Review(PENDING)；
   监听失败则整个 SCM 事务回滚。执行器只能在 after-commit callback 中启动。
4. **执行状态机与 fencing**：`PENDING → RUNNING → COMPLETED | FAILED`；`FAILED → PENDING`（人工重试复用同一行）；
   `RUNNING → PENDING`（lease 过期由 reconciliation 恢复）。领取是**单条原子条件更新**，递增 `execution_attempt`、
   生成新 `execution_token`、写 `lease_until`。**过期 Worker 的完成、失败、续租与插入 Finding 影响行数必须为 0。**
5. **reconciliation**：只处理**已落库但未执行或停滞**的任务（未被领取的超时 PENDING、lease 过期的 RUNNING），
   统一回到同一领取/执行路径。**禁止补建缺失 Review**——需求关联或版本变化后的重审一律人工触发。
6. **大 PR 分批**（[D002](../../../../../docs/v2/DECISIONS.md#d002)）：Batch 只产 Finding candidate 与 AC evidence，
   **不产 AC verdict**；全部 Batch 完成后 Final Synthesis 统一产出；任一 Batch 非法 JSON 且修复失败 → **整个 Review = FAILED**；
   必须保存 truncation/coverage manifest 且在 UI 显式呈现。
7. **输出校验**：每条 AC 最终必须有 `COVERED | NOT_FOUND | AT_RISK`，模型漏项由 Validator 补 `NOT_FOUND`；
   `acId` 属于当前 Revision、`sourceId` 在本次召回白名单、`filePath` 在 changed files 内、行号落在 patch 可验证范围。
8. **Finding 跨轮血缘**（[D009](../../../../../docs/v2/DECISIONS.md#d009)）：`finding_key` / `evidence_hash` / `basis_hash` /
   `continuity` / `carried_from_finding_id`。两个 hash **均不得包含 LLM 自由文本**。
   优先级固定 `SUPPRESSED > PERSISTING > NEW`；连续性只在**同一 PR** 内计算。
9. **运行边界实测**：在目标 4 GB 机、生产 JVM/PostgreSQL 上限下跑至少一个**最大预算** Review，
   据实把并发 Review 冻结为 **1 或 2**，并记录峰值、失败与降级行为。**这个数字必须是量出来的，不得预写为常量。**
10. **Review 详情只读页**可用。

### Phase 7：人工闭环 + 三页面统一验收

11. **Finding 人工生命周期**：`OPEN → CONFIRMED → IN_PROGRESS → FIXED → VERIFIED → CLOSED`，
    旁路 `OPEN|CONFIRMED → REJECTED`，打回 `FIXED → IN_PROGRESS`，
    重开 `REJECTED → OPEN`（**仅** `continuity=SUPPRESSED` 的继承驳回项，须留审计）。
    该状态机与 `continuity` **正交**，不得混入同一字段或同一 UI 标签。
12. **Review Decision**：仅 `PENDING → APPROVE | REQUEST_CHANGES`，**一次**。
    `SELECT ... FOR UPDATE` 锁 `pull_request` 行 + 逐条校验六项前置 + `WHERE decision='PENDING'` 条件更新；
    影响行数必须为 1，否则 409。**同一 head 出现 REQUEST_CHANGES 后只能靠新 head SHA 解除**——
    改 Base、需求关联、需求版本或重新同步 Diff 都不能解除该闸门。
13. **三个一级页面**完成浏览器、可访问性、响应式与视觉漂移验收。
14. **三角色可重复演示**：需求 → PR → Finding → 退回 → 修复 → 新 Review → 通过 → DONE。
    其中 **DONE 由 LEADER 手动执行**（P9），不是 APPROVE 的自动后果。
15. **Revision/Diff 变化显示 `REVIEW_REQUIRED`**；旧 Review 不可对当前输入作终局决定。

## 3. 明确不做

- **不新增第 17 张表。** 执行恢复不另建任务表，用 Review 行上的 attempt/token/lease（ARCHITECTURE §2.1「不建」清单）。
- 不建 `review_task/report/issue`、`review_decision`、`webhook_delivery`、通用 `audit_event`、任何 vector 影子表。
- 不新增顶层包（`review` 包已存在，目前只有 `package-info.java`）、**不新增一级菜单**、不新增运行时依赖。
- 不做 GitLab（Phase 8）。不做撤销或改判 Decision（MVP 不支持）。
- **不运行 holdout**（锁死在 Phase 8，只跑一次）。development 集三臂增量试跑，**只据 development 调参**。
- 不设 `review.INVALIDATED` 状态——执行状态与语义有效性是两个维度，"已过期"由页面对比派生。
- 不自动重审：需求版本变更后关联 PR 显示"审查已过期"，由人工按权限触发。
- 不自动认定「本轮未报告 = 已修复」（P10）。`NOT_REPORTED` 只查询派生，**不落库**。

## 4. 规则

### R1. 三个概念不得混为一谈

ARCHITECTURE §3.1 明确区分：

```text
Review Identity  = pull_request_id + head_sha + review_input_fingerprint + requirement_revision_id
Current Validity = Review 的 head/fingerprint/requirement revision 均等于 PR 当前值
Decision Gate    = pull_request_id + head_sha 上是否已有 REQUEST_CHANGES
```

Base、changed files、patch 或纳入指纹的 Diff version 改变时，**即使 head SHA 不变**也必须形成新的 Review Identity。
`requirement_revision_id` 的比较必须用 `IS NOT DISTINCT FROM`——NULL 亦须相等（§3.1 第 5 条前置）。
把三者中任意两个混用，都会造成**放行不该放行的合并**，这是本批次最高危的一类错误。

### R2. 过期 Worker 的四种写入都必须为 0 行

「不得完成」很容易被实现成只挡住 COMPLETED。ARCHITECTURE §3.2 的原文是
「过期 Worker 的写入影响行数为 0，**不能覆盖新尝试、插入 Finding 或改写 Review**」。
因此至少四条路径各需一条断言：完成、标记失败、续租、插入 Finding。
**只测第一条就报绿，是本批次最容易犯的凑绿。**

### R3. 绝不生成「成功空报告」

P6 与 §3.5：非法 JSON 允许**一次** format-repair；仍失败则 FAILED。
分批场景下任一 Batch 失败 → **整个 Review FAILED**，不输出部分成功报告。
断言必须证明「判定为失败」，而不是「没抛异常」。

### R4. 两个 hash 不得哈希模型自由文本

`evidence_hash` 只覆盖确定性源码证据（统一换行、去除易变行号，但**不得**对 Python/YAML 等
缩进敏感内容做通用空白折叠）；`basis_hash` 覆盖被引用 Requirement/AC 内容、知识 excerpt/hash 与确定性规则版本。
只有两者**均未变**才允许继承历史误报抑制。这条决定了抑制机制是否可信——
哈希了模型输出，抑制就会随模型措辞漂移而失效或误抑。

### R5. 运行边界是实测输出，不是常量

[D012](../../../../../docs/v2/DECISIONS.md#d012) 第 2 条与 [D014](../../../../../docs/v2/DECISIONS.md#d014) 都明确：
Phase 6 的并发上限与 batch 预算**必须实测得到**。
先写死一个 2 再补个测试证明"2 能跑"，不算实测——必须是在 4 GB 目标机上跑出峰值后**据实冻结**。
若实测结论是 1，就写 1；这不是降级，是诚实。

### R6. 项目隔离与授权照旧由数据库执行

三张新表全部携带 `project_id`；`finding` 的**永久**父 FK `(project_id, review_id) → review(project_id, id)`；
`finding_event` 的 `(project_id, finding_id) → finding(project_id, id)`。
可空复合外键是 `MATCH SIMPLE`，因此**不能**用 Finding 的 nullable Requirement/Revision/AC 外键证明父 Review 存在
（ARCHITECTURE §2.2）。父子上下文一致由 §2.1 唯一授权的那个**约束触发器**用 `IS NOT DISTINCT FROM` 保证。

### R7. 补上批次 2 欠下的两条

[D016.2](../../../../../docs/v2/DECISIONS.md#d016)：`review` 表落地后**必须**补上 P1 的 DEVELOPER 半条授权
（本人 PR 且当前 head 尚无人工终局 Decision 时可改关联）。
[D015.1](../../../../../docs/v2/DECISIONS.md#d015)：补 `ai_call_log.review_id` 的外键——
`aiCallLogHasNoReviewForeignKeyYetAndNoRowsUsingIt` 已把「此刻全为 NULL」钉死，补外键不会撞历史数据。

## 5. 已知会被本批次打破的东西

按批次 2 的先例，两处按「恰好十三张表」写死的断言**必然**失败，属预期内改动：

1. `backend/src/test/java/com/forgepilot/FoundationDatabaseTest.java` 的 `EXPECTED_TABLES`。
2. `scripts/phase1-compose-smoke.sh` 的 `expected_tables`（CI 的 compose job 依赖它）。

两处都必须改成**十六张全名**，不得退化成只比数量。
第三处：`KnowledgeAndScmConstraintTest.aiCallLogHasNoReviewForeignKeyYetAndNoRowsUsingIt`
断言「不存在涉及 `review_id` 的外键」——本批次补外键后该断言**必须**反转，
且反转要连同 R7 一起做，不能只删掉了事。

## 6. 开始前必须回答的开放项

来自批次 2 `result.md` §11 与 §7.5：

1. **[D016.2](../../../../../docs/v2/DECISIONS.md#d016) 的 DEVELOPER 半条**：`review` 建表后即可表达，本批次必须补。
2. **三元组冻结的并发竞争未测**（批次 2 §7.5）：本批次引入更多并发写入点，应顺手补上。
3. **`GitHubClient.required()` 的拒绝分支未测**、**changed-file 超限路径零覆盖**
   （[D016.1](../../../../../docs/v2/DECISIONS.md#d016)）：两条都是「守卫存在但拒绝分支未测量」，成本很低，应补。
4. **缺密钥启动失败未断言**（批次 2 §7.4）：一条被声明却未被测量的 fail-closed 属性。
5. **无自动化浏览器点击闭环**（批次 1 AC11 部分通过的原因）：Phase 7 的验收要求「浏览器、可访问性、
   响应式、视觉漂移」四项。**若在不新增依赖的前提下仍做不到，必须再次如实记为部分通过，不得粉饰。**
   devDependency 是否也在「不新增依赖」的禁令之内，是本批次必须先裁定的开放项。

## 7. 风险

1. **这一批的规格密度远高于前两批**。ARCHITECTURE §3 已经规定到近乎实现级，
   设计的余地小、但**读漏一句的代价大**——尤其 §3.1 的六条 Decision 前置与 §3.6 的连续性五条规则。
2. **授权正确性风险最高**：Decision Gate 写错 = 放行不该合并的 PR。
   任何涉及「谁能做终局决定」「封锁如何解除」的判断都必须有并发实测，不接受单线程绿。
3. **凑绿风险最高**：R2 的四条路径、R3 的失败判定、R5 的实测数字，三处都存在
   「写一条容易过的断言就报绿」的诱惑。批次 2 已经出现过一次（`AiGateway.chat` 零调用者却 20 条 AC 全绿）。
4. **4 GB 实测可能跑不动**：若目标机资源不足以完成一次最大预算 Review，
   如实记录失败与降级行为本身就是 Phase 6 要求的产出，**不得为了有个数字而缩小"最大预算"的定义**。

## 8. 验收条件

**待研究落地后回填。** 五份研究分别覆盖：约束触发器与 25P02 实测、after-commit 调度与 reconciliation、
fencing 与并发、Review activity 判定矩阵、前端 Phase 7 缺口。

**记法约定**（延续批次 1、2）：验收条件只有通过与不通过，**部分通过必须记为部分通过**，
不得为了凑绿而放宽措辞。批次 1 的 AC11 与批次 2 的 AC20 都是这样记的。
