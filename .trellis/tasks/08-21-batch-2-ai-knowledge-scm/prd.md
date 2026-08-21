# 批次 2 需求（Phase 4 + Phase 5）

授权依据：[D012](../../../docs/v2/DECISIONS.md#d012)（批次划分）、[D014](../../../docs/v2/DECISIONS.md#d014)（闸门执行者）。
上一批次：`.trellis/tasks/archive/2026-08/08-21-batch-1-auth-project-requirement/result.md`。

> **状态：验收条件待研究回填。** 本文的范围、边界与规则来自权威文档，与研究结论无关，先行写死；
> 逐条验收条件在三份 `research/` 落地后补齐，因为其中最关键的一条——**如何在没有凭据的前提下测**——
> 目前还没有被证实可行的答案。没有答案就写 AC 等于写空头支票。

## 1. 为什么是这一批

批次 1 交付了「需求是什么」：账户、项目、成员、需求与不可变版本。
批次 2 交付「上下文从哪来」的两条腿——**知识**（文档、Chunk、向量检索、附件归属）和
**代码变更**（GitHub 仓库、Webhook、PR 权威快照）。两者都不产出 Review 结论，
但 Review 的输入全部由这一批定义；输入的身份算错，批次 3 的幂等与历史语义会整体错位。

十六张表中批次 1 建了六张，本批次再建七张：`knowledge_document`、`requirement_attachment`、
`knowledge_chunk`、`scm_repository`、`pull_request`、`pull_request_requirement_event`、`ai_call_log`。
余下三张（`review`、`finding`、`finding_event`）属批次 3。

## 2. 范围

1. **AI Gateway**：统一 chat/embed 调用、超时、**一次** retry、`PromptSanitizer`、`ai_call_log` 落库。
2. **Knowledge**：`knowledge_document` / `knowledge_chunk`、单个无维度 `vector` 列、项目内检索、安全上传。
3. **附件关系**：`requirement_attachment` 与 [D005](../../../docs/v2/DECISIONS.md#d005) 的归属约束；提升为公共知识是**复制**而非就地改写。
4. **一次性 Requirement Implementation Guidance**：不建 Conversation、不建 SSE、不建 Assistant 模块。
5. **GitHub SCM**：一个项目一个活动 `scm_repository`；稳定身份 = provider + 规范化 instance identity + external id，有 PR 后冻结。
6. **PR 快照**：验签后读 Provider 权威数据，保存 base/head、changed files、patch 与确定性 `review_input_fingerprint`。
7. **PR ↔ 需求关联**：`REQ-<n>` 解析（[D013.2](../../../docs/v2/DECISIONS.md#d013)：`<n>` 即 `requirement.id`，按 PR 所属项目过滤）、人工纠正、
   `pull_request_requirement_event` 审计（[D007](../../../docs/v2/DECISIONS.md#d007)）。
8. **作者映射**：不可变作者快照 + 可重算 `author_user_id`；授权键是 `scm_external_user_id`，
   **禁止按用户名授权**（[P11](../../../docs/v2/PRD.md) / [D010](../../../docs/v2/DECISIONS.md#d010)）。
9. **`PullRequestChanged`**：在更新 PR 的同一事务内发布的进程内同步事件。

## 3. 明确不做

- 不做 Review 引擎、Finding、人工决策闭环——那是批次 3。本批次**不得**新建 `review` / `finding` / `finding_event`。
- 不做 GitLab（Phase 8）。`scm` 的子包白名单已允许 `scm.github` / `scm.gitlab`，但本批次只落 `github`。
- 不建向量索引、不绑定 embedding 维度（[D001](../../../docs/v2/DECISIONS.md#d001)）。
- 不建 Conversation / 多轮会话 / SSE / 通用 Assistant。
- 不建 Prompt Registry、不建万能 ContextBuilder（ARCHITECTURE §4）。
- 不新增第 17 张表、不新增顶层包、不新增一级菜单。

## 4. 规则

### R1. CI 绝不依赖凭据

**这是本批次的硬约束，也是最大的技术风险。** CI 四个 job 不得持有 AI provider key、
GitHub token 或任何仓库秘密，也不得访问 github.com 或任何真实 provider。
因此 Gateway、Embedding、Webhook 验签与 PR 快照的自动化测试**必须**打到进程内或本地的假服务端。
若研究证明现有 classpath 上没有可用的 stub 手段，而唯一解是新增依赖，**先停下来出决策**，不要偷偷加。

### R2. 那一次 retry 是被授权的例外

本项目通篇禁止 retry / fallback / 兼容分支。AI Gateway 的「一次 retry」是
`IMPLEMENTATION-PLAN.md` Phase 4 明文规定的产品行为，**只此一处**，且必须有测试证明它恰好重试一次、
不重试不可重试的错误、并且两次调用都落 `ai_call_log`。不得借此把 retry 扩散到别处。

### R3. 输入必须显式失败

Phase 4 退出条件要求：非法 UTF-8、NUL 字节、超限输入、维度不匹配一律**显式失败**，
不得静默截断、不得写坏数据。每一条都要有断言，并且断言的是真实错误而不是"没抛异常"。

### R4. Webhook 是信号不是真相

验签用**原始字节**，不能重新序列化后再算。验签失败不写任何数据。
Provider 的权威快照才是真值；`source_revision` / `source_updated_at` 只用于乱序保护，
**不得**参与 `review_input_fingerprint`。旧事件不得让 head / base / patch 回退。重放必须幂等。

### R5. 项目隔离照旧由数据库执行

七张新表全部携带 `project_id`，项目内引用一律复合外键，被引用表提供对应唯一键。
Repository 读路径一律接受 `projectId`。约束冲突不捕获后继续（[D013.11](../../../docs/v2/DECISIONS.md#d013)），统一映射 409/422。
复合外键关联沿用 [D013.1](../../../docs/v2/DECISIONS.md#d013) 变体 A，已写入 `.trellis/spec/backend/database-guidelines.md`。

### R6. `ai_call_log` 不是日志

它是有 schema、有项目作用域的落库数据。不得走 slf4j，其 prompt/response 载荷不得进应用日志
（`.trellis/spec/backend/logging-guidelines.md`）。

## 5. 已知会被本批次打破的东西

批次 1 的两处断言是按「恰好六张表」写死的，本批次新增七张表**必然**使其失败，属预期内改动而非回归：

1. `backend/src/test/java/com/forgepilot/FoundationDatabaseTest.java` 的 `EXPECTED_TABLES`。
2. `scripts/phase1-compose-smoke.sh` 的 `expected_tables`（CI 的 compose job 依赖它）。

两处都必须改成新的**完整表名列表**，不得退化成只比数量——逐名比对能挡下计划外的表，比数量强。

## 6. 开始前必须回答的开放项

来自批次 1 `result.md` §10，本批次动到相关表之前必须有结论：

1. 成员移出项目的语义：`requirement.assignee` 如何处置。本批次 `pull_request.author_user_id`
   已经带列级 `ON DELETE SET NULL`（§2.3 唯一规定了删除语义的地方），与批次 1「全表不写 ON DELETE」
   并存，需要在 design 中说清两者为何不矛盾。
2. 若引入禁用账户接口，必须同时递增 `session_version`。本批次预计不引入。
3. 需求状态审计表仍是 MVP 缺口（[D013.3](../../../docs/v2/DECISIONS.md#d013)），本批次不补。

## 7. 验收条件

**待研究回填。** 三份 `research/` 落地后按 Phase 4 / Phase 5 的退出条件逐条展开为可断言的 AC，
并明确每条在哪一层断言、用什么证据。凡是「无法在无凭据环境下验证」的条目，
要么给出 stub 方案，要么如实记为缺口——不写成看起来能过的样子。
