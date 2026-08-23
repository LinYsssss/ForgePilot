# 批次 2 执行计划

## 0. 启动前闸门

- [ ] `prd.md`、`design.md`、本文件与 `validation.md` 就绪；[D015](../../../../../docs/v2/DECISIONS.md#d015) 已提交。
- [ ] `implement.jsonl` / `check.jsonl` 填入真实条目。
- [ ] 运行 `python3 ./.trellis/scripts/task.py start 08-21-batch-2-ai-knowledge-scm`。
- [ ] 记录 `git status --short`，识别并隔离既有改动。
- [ ] 派发提示第一行为 `Active task: .trellis/tasks/08-21-batch-2-ai-knowledge-scm`；
      子代理不得再派子代理，不得提交/推送/reset/checkout/删除未授权文件。

**本批次是纯后端。** 不新增任何前端界面、不新增一级菜单——Phase 6/7 才建人工闭环。
文档上传与检索在本批次只经 API 验证。

## 1. 迁移与实体映射（必须最先完成，单一执行者串行）

**范围**：`V4__knowledge_ai.sql`、`V5__scm.sql`，七个实体，以及仅验证约束的集成测试。

- [ ] 按 `design.md` §2 写两条迁移；枚举用 `varchar + CHECK`；除 §2.3 为 `author_user_id` 规定的
      `ON DELETE SET NULL` 外不写任何 `ON DELETE`；不建向量索引。
- [ ] `requirement_attachment.requirement_id` / `document_id` 必须 `NOT NULL`（[D015.2](../../../../../docs/v2/DECISIONS.md#d015)，承重）。
- [ ] `knowledge_chunk.embedding` **不映射**进实体（[D015.4](../../../../../docs/v2/DECISIONS.md#d015)）；先让应用启动成功再往下走。
- [ ] `ai_call_log.review_id` 建列不建外键，迁移里写明批次 3 补（[D015.1](../../../../../docs/v2/DECISIONS.md#d015)）。
- [ ] 集成测试逐条断言约束真的生效：公共知识挂 Requirement 被 `23503`；`requirement_id` 可空时
      整条检查蒸发（反证 fixture）；维度自洽 CHECK 被 `23514`；`(document_id, seq)` 唯一；
      `(provider, instance_identity, external_id)` 全局唯一。
- [ ] 更新 `FoundationDatabaseTest.EXPECTED_TABLES` 与 `scripts/phase1-compose-smoke.sh` 为**十三张表全名**。
- [ ] 运行容器内 `./mvnw -B -ntp verify`。

**闸门 A（必须）**：若任一约束行为与 `research/pgvector-hibernate-measured.md` 的实测不符，
**停止**并回到决策，不得改用 Service 校验绕过（[D006](../../../../../docs/v2/DECISIONS.md#d006)）。

## 2. AI Gateway 切片

**范围**：`backend/**/com/forgepilot/ai/**`。

- [ ] `AiGateway`（chat/embed 唯一入口）、`AiCallContext`（不透明 id）、`PromptSanitizer`、
      `AiFailurePolicy`（超时 + **恰好一次** retry）、`AiCallLog` + Repository。
- [ ] base URI 来自配置，**禁止硬编码 provider host**。
- [ ] 测试用 `com.sun.net.httpserver.HttpServer`，**请求计数**证明恰好重试一次、永久错误不重试。
- [ ] 两次调用都落 `ai_call_log`；prompt/response 载荷**不得**进应用日志。

**验收点**：AC-AI-*。

## 3. Knowledge 切片

**范围**：`backend/**/com/forgepilot/knowledge/**`。前置：步骤 1、2。

- [ ] `KnowledgeDocument` / `KnowledgeChunk` + Repository；`ChunkSearchRepository` 是**唯一**
      出现 `::vector` 与 `<=>` 的地方。
- [ ] `KnowledgeUploadValidator`：大小上限、**孤立代理项拒绝**（[D015.5](../../../../../docs/v2/DECISIONS.md#d015)）、NUL 与非法 UTF-8。
- [ ] 写入 embedding 前校验维度一致（[D015.3](../../../../../docs/v2/DECISIONS.md#d015)：这是唯一防线）。
- [ ] 附件关系与 Document 同事务写入；提升为公共知识是**复制**新 Document，不就地改写。
- [ ] 检索一律带 `projectId`；一次性 Implementation Guidance。

**验收点**：AC-KN-*。

## 4. SCM 切片

**范围**：`backend/**/com/forgepilot/scm/**`（含 `scm.github` 子包）。前置：步骤 1。

- [ ] `ScmRepository` / `PullRequest` / `PullRequestRequirementEvent` + Repository。
- [ ] `OutboundUrlPolicy`（`design.md` §3.5）——先写它和它的独立测试，再写 `GitHubClient`。
- [ ] `WebhookSignatureVerifier`：原始字节验签；失败 `401` 且不写任何数据。
- [ ] `ReviewInputFingerprint`：按 `design.md` §3.2 规范化，测试钉死确定性。
- [ ] `RequirementReferenceParser`：经 `requirement` 只读 facade 解析（[D015.6](../../../../../docs/v2/DECISIONS.md#d015)），
      解析失败不阻断入库。
- [ ] 乱序保护：旧 `source_updated_at` 不得回退 head/base/patch；重放幂等。
- [ ] `PullRequestChanged` 同步进程内事件——批次 2 没有 `review` 监听者，只证明它在同事务内发布。

**验收点**：AC-SCM-*。

## 5. 架构与全范围复核

- [ ] ArchUnit 七条仍全绿；`scm.github` 在子包白名单内；**`scm` 仍不依赖 `review`**。
- [ ] 顶层包仍严格八个；`ai` 不依赖 `knowledge`/`requirement`；`knowledge` 不反查 `requirement`。
- [ ] 主会话逐个检查实际 `git diff`，不以代理总结代替事实。
- [ ] Compose 空库冷启动；CI 四个 job 全绿，且**仍不依赖任何凭据**。
- [ ] 运行 `validation.md` 全部命令。

## 6. Finish 与提交闸门

- [ ] 更新 `result.md`：完成/未完成、命令与真实结果、偏差、**§2.1 补列必须单独列出**、
      非数据库执行的不变式（`design.md` §3.7）、密钥轮换缺口、风险、批次 3 前置条件。
- [ ] Trellis spec update：把向量列不映射、`::vector` 只在一处、无凭据测试形态写入 `.trellis/spec/backend/`。
- [ ] 按 [D014](../../../../../docs/v2/DECISIONS.md#d014) 逐条自证退出闸门，不合格就停。

## 文件所有权与派发顺序

1. 步骤 1（迁移 + 实体）**由单一执行者串行完成**，是地基，不并行。
2. 步骤 2 与步骤 3 有依赖（Knowledge 要 embed），按序。
3. 步骤 4 只依赖步骤 1，可与步骤 2/3 并行——文件范围零重叠。
4. 并发不超过 5 个子代理，可多轮分派。构建用 `flock` 串行化。
