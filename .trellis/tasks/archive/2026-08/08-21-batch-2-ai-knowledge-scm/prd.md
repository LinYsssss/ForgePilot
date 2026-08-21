# 批次 2 需求（Phase 4 + Phase 5）

授权依据：[D012](../../../docs/v2/DECISIONS.md#d012)（批次划分）、[D014](../../../docs/v2/DECISIONS.md#d014)（闸门执行者）。
上一批次：`.trellis/tasks/archive/2026-08/08-21-batch-1-auth-project-requirement/result.md`。

> **状态：验收条件已回填**（研究落地后补全，见 §7）。范围、边界与规则来自权威文档，与研究结论无关。

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

研究已落地，**最关键的一条「如何在无凭据前提下测」已被证实可行**（[D015.8](../../../docs/v2/DECISIONS.md#d015)：
`jdk.httpserver` 与 `MockRestServiceServer` 均已在 classpath 上，零依赖增量），因此以下 AC 全部可断言。

- [ ] **AC1**　空库 Flyway 后恰好 13 张业务表；七张新表的项目内引用均为含 `project_id` 的复合外键，
      每个被引用表具备对应唯一键；真实 PostgreSQL 集成测试证明跨项目写入被数据库拒绝（`23503`），
      而不是只靠 Service 校验。
- [ ] **AC2**　公共知识（`source_requirement_id IS NULL`）无法被挂成需求附件（`23503`）；
      附件被钉在其 Document 自身归属的那个需求上；**并有反证测试**证明子表 `requirement_id` 一旦可空，
      整条三列检查就蒸发（[D015.2](../../../docs/v2/DECISIONS.md#d015)）。
- [ ] **AC3**　文档类型与归属不匹配被 `23514` 拒绝；`FAILED` 文档必须带失败原因，否则被数据库拒绝。
- [ ] **AC4**　`knowledge_chunk` 维度自洽 CHECK 生效（`23514`）；**与本项目既有维度不符的向量写入被应用层拒绝**；
      并有记录性测试说明混维度会让整个项目的 TopK 查询失败（[D015.3](../../../docs/v2/DECISIONS.md#d015)）。
- [ ] **AC5**　NUL 与非法 UTF-8 显式失败；**孤立代理项被应用层拒绝**，且测试先断言"驱动确实会静默改成 `?`"，
      使该断言不可能空转（[D015.5](../../../docs/v2/DECISIONS.md#d015)）；超限文本被 `KnowledgeUploadValidator` 拒绝。
- [ ] **AC6**　检索一律带 `projectId`；A 项目检索不到 B 项目的 chunk。`::vector` 与 `<=>` 只出现在
      `ChunkSearchRepository` 一处。
- [ ] **AC7**　提升为公共知识产生**新** Document，原附件行未被就地改写（[D005](../../../docs/v2/DECISIONS.md#d005)）。
- [ ] **AC8**　AI Gateway：超时真的触发并记为 `TIMEOUT`；**可重试错误下 stub 请求计数恰为 2，永久错误恰为 1**；
      两次尝试都落 `ai_call_log`；畸形 JSON 判定为失败而非"成功空结果"；`Authorization` 头携带配置值
      且**全仓库不存在任何真实 key**。
- [ ] **AC9**　全仓库无硬编码 provider host；SCM base URI 取自 `scm_repository.api_base`。
- [ ] **AC10**　`OutboundUrlPolicy` 在白名单为空时逐条拒绝 loopback、`10/8`、`172.16/12`、`192.168/16`、
      `169.254.169.254`、`::1` 与非 `http(s)` 协议；集成测试中该策略**始终开启**，只加一条窄白名单例外。
- [ ] **AC11**　Webhook 按**原始字节**验签：正确签名通过；改一字节被拒；**解析后结构相同但字节不同**
      （重排键/加空白）被拒。无效签名返回 `401` 且零写入；未知仓库同样 `401`，两者不可区分。
- [ ] **AC12**　`review_input_fingerprint` 确定性：同输入同值；任一输入变一字节则变值；
      `source_revision` / `source_updated_at` 变化**不**改变它。
- [ ] **AC13**　重放幂等；旧 `source_updated_at` 不得回退 head/base/patch。
- [ ] **AC14**　`REQ-<n>` 按 PR 所属项目解析（经 `RequirementDirectory`，不注入 `RequirementRepository`）；
      外项目 id 解析为「未关联需求」且**不阻断入库**。
- [ ] **AC15**　稳定身份三元组全局唯一；有 PR 后修改三元组被拒（`409`）——该不变式由 Service 执行，
      **必须在 `result.md` 如实记为「非数据库执行」**（`design.md` §3.7）。
- [ ] **AC16**　移除成员后 `pull_request.author_user_id` 置空，而 `author_external_user_id` /
      `author_username` 快照不变；授权判定不读 `scm_username`。
- [ ] **AC17**　`ai_call_log.review_id` 建列未建外键，且该列全为 NULL——批次 3 补外键的前置条件。
- [ ] **AC18**　ArchUnit 七条全绿；顶层包仍八个；子包只有 `scm.github`；`scm` 不依赖 `review`；
      `knowledge` 不反查 `requirement`。
- [ ] **AC19**　`./mvnw -B -ntp verify` 全绿无 skip；**`backend/pom.xml` 零改动**；
      Compose 空库冷启动通过且断言十三张表；CI 四个 job 全绿且 `ci.yml` 中仍无 `secrets.*`。
- [ ] **AC20**　`result.md` 完整：§2.1 补列（`title` / `failure_reason`）单独列出；
      非数据库执行的不变式如实标注；密钥轮换缺口如实记录；未触发 [D015](../../../docs/v2/DECISIONS.md#d015) 之外的新决策。

**记法约定**（延续批次 1）：验收条件只有通过与不通过，**部分通过必须记为部分通过**，
不得为了凑绿而放宽措辞。
