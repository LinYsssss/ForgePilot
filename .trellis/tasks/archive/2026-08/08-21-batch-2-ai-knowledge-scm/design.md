# 批次 2 技术设计

依据：`prd.md`、`docs/v2/ARCHITECTURE.md`、[D015](../../../../../docs/v2/DECISIONS.md#d015)，以及 `research/` 下三份研究。
本文只写实现形态，不复述权威文档已定义的事实。[D015](../../../../../docs/v2/DECISIONS.md#d015) 已裁定的不再重复理由，只写结论。

## 1. 设计原则（延续批次 1）

- **数据库是隔离与完整性的执行者**；Service 不做数据库已经能拒绝的重复校验。
- **零新增表、零新增依赖**：七张表全部来自 §2.1，测试用 JDK 自带 HTTP 服务器（[D015.8](../../../../../docs/v2/DECISIONS.md#d015)）。
- **先约束后代码**：迁移与实体映射先过真实 PostgreSQL 集成测试，再写业务逻辑。
- 复合外键关联沿用 [D013.1](../../../../../docs/v2/DECISIONS.md#d013) 变体 A（关联只读、标量写入）。

## 2. 迁移

新增 `V4__knowledge_ai.sql` 与 `V5__scm.sql`。拆两条的理由：Phase 4 与 Phase 5 是两个独立退出闸门，
分开便于按闸门回滚；且 `pull_request.requirement_id` 需要 `requirement` 已存在（V3），与 V4 无依赖。

### 2.1 本批次仍未定的枚举，就地定死

数据库存 `varchar + CHECK`（§2.4），因此这些值在迁移那一刻起冻结，必须现在定：

| 列 | 取值 | 依据 |
|---|---|---|
| `knowledge_document.source_type` | `REQUIREMENT_ATTACHMENT` / `PROJECT_KNOWLEDGE` | §2.1 只点名了前者；后者是「公共知识」的唯一其它可能 |
| `knowledge_document.status` | `PENDING` / `READY` / `FAILED` | §6 要求展示失败原因，故必须有 `FAILED` |
| `ai_call_log.use_case` | `REQUIREMENT_QUALITY` / `IMPLEMENTATION_GUIDANCE` / `EMBEDDING` / `REVIEW` | §4 列出的 AI 场景；`REVIEW` 预留给批次 3，本批次不写 |
| `ai_call_log.status` | `SUCCESS` / `FAILED` / `TIMEOUT` | 「一次 retry」需要区分超时与其它失败 |
| `pull_request_requirement_event.actor_type` | `USER` / `SYSTEM` | §2.1 原文 |

**OPEN-1/OPEN-4 的裁定**：`knowledge_document` 不设 `model`/`version` 列——§2.1 的那对列与
`knowledge_chunk` 的 `provider/model/version/dimension` 四列语义重复，而 embedding 属于 chunk 而非 document。
`ai_call_log.token` 拆为 `prompt_token` / `completion_token` / `total_token` 三列：Phase 8 要报 Token 成本，
一个合计数字事后无法拆开，而迁移是只增不改的。

**OPEN-2/OPEN-3 的裁定**：`knowledge_document` 增加 `title`（上传文件名/展示标题）与 `failure_reason`。
§6 明文要求展示失败原因，§2.1 的列清单没有承载它的地方；这两列不新增表、不改变任何既有语义，
属 §2.1 结尾允许的「同表补列」，而非新增第 17 张表。**这是本批次唯一一处对 §2.1 列清单的扩充，
在 `result.md` 中必须单独列出。**

**OPEN-9**：`knowledge_chunk` 加 `UNIQUE(document_id, seq)`，与 `requirement_revision` 的
`UNIQUE(requirement_id, seq)` 同型。现在加是一行 DDL，日后加是一条迁移。

**OPEN-11**：检索用 **cosine**（`<=>` / 日后 `vector_cosine_ops`）。文本 embedding 的通行选择，
且与 Phase 6 的表达式索引必须同算子——现在选错，日后索引就是死重。
`FoundationDatabaseTest` 里那句 `<->` 只是探测扩展是否可用，与业务算子无关，保持不动。

### 2.2 `V4__knowledge_ai.sql` 的承重项

```sql
-- D015.2：这两列 NOT NULL 是承重的。一旦可空，MATCH SIMPLE 会跳过下面那条三列外键，
-- 不存在的 document_id 也能落库，D005 的附件归属就失去唯一执行者。
CREATE TABLE requirement_attachment (
    ...
    requirement_id BIGINT NOT NULL,
    document_id    BIGINT NOT NULL,
    CONSTRAINT uq_requirement_attachment_pair   UNIQUE (requirement_id, document_id),
    -- 一个 Document 最多属于一个 Requirement
    CONSTRAINT uq_requirement_attachment_doc    UNIQUE (project_id, document_id),
    CONSTRAINT fk_requirement_attachment_req
        FOREIGN KEY (project_id, requirement_id) REFERENCES requirement (project_id, id),
    -- 双复合 FK：锁定 document 的归属恰好等于本行的 requirement
    CONSTRAINT fk_requirement_attachment_doc_scope
        FOREIGN KEY (project_id, document_id, requirement_id)
        REFERENCES knowledge_document (project_id, id, source_requirement_id)
);

-- D015.3：不绑定任何具体维度，与 D001 兼容；只保证自洽，挡不住"整列都是错维度"。
CONSTRAINT ck_knowledge_chunk_dimension
    CHECK (embedding IS NULL OR dimension = vector_dims(embedding))
```

- `knowledge_document` 必须有 `UNIQUE (project_id, id, source_requirement_id)`，否则上面的三列外键无目标。
- `embedding vector`（无维度）；**不建任何向量索引**（[D015.3](../../../../../docs/v2/DECISIONS.md#d015)）。
- `ai_call_log.review_id` 建列不建外键，迁移中写明批次 3 补（[D015.1](../../../../../docs/v2/DECISIONS.md#d015)）；
  `requirement_id` / `requirement_revision_id` 的复合外键**现在就建**，它们指向的表已存在。
- 不写 `ON DELETE`（延续批次 1）。

### 2.3 `V5__scm.sql` 的承重项

- `scm_repository`：`UNIQUE(project_id)`（一个项目一个活动仓库）+ `UNIQUE(project_id, id)`（供 PR 复合外键）。
- `pull_request`：`UNIQUE(repository_id, external_number)`、`UNIQUE(project_id, id)`；
  `requirement_id` 可空 + 普通索引；`author_user_id` 复合外键指向 `project_member`，
  **列级 `ON DELETE SET NULL`**。
- **与批次 1「全表不写 ON DELETE」并不矛盾**：批次 1 的理由是 §2.3 没有为那些表规定删除语义。
  §2.3 **恰恰只为这一列规定了** `ON DELETE SET NULL`。规则始终是「照 §2.3 写」，不是「一律不写」。
- changed-file manifest 与 patch 存 JSONB（[D015.7](../../../../../docs/v2/DECISIONS.md#d015)），带大小上限，超限显式失败。

## 3. 仍未定且本设计就地裁定的 SCM 细节

权威文档对这三条**完全没有定义**，而它们一旦写进已应用的迁移就冻结，因此必须现在定并写清理由：

### 3.1 规范化 instance identity（OQ-5）

**规则**：取 `api_base` 的 host，小写化，去掉端口若为该协议默认端口，去掉尾部 `/`，IDN 转 punycode；
`api.github.com` 与 `github.com` 归一为 **`github.com`**。存进 `instance_identity` 列，不即时推导。

**理由**：它是稳定身份三元组的一部分，有 PR 后冻结；写错则该项目永远无法修正，只能重建。
归一到面向用户的 host（而非 API host）是因为同一实例的 API host 可能随版本变化，而站点 host 不会。

### 3.2 `review_input_fingerprint` 的规范化（OQ-6）

**输入**：provider、instance_identity、external_id（repository 身份）+ base_sha + head_sha
+ changed-file manifest + 每个文件的 patch。**明确不含** `source_revision` / `source_updated_at`
（§3.1 原文：仅用于事件排序，不得单独制造新身份）。

**规范化**：文件按路径的**字节序**排序（非 locale 排序）；路径大小写敏感；patch 以 UTF-8 字节参与；
行尾不做任何归一（`\r\n` 与 `\n` 是不同的 diff，归一会让两个真实不同的输入撞成同一个身份）；
字段之间用不可能出现在路径或 patch 中的分隔符连接；最后 SHA-256。

**理由**：这条规则一旦改变，**所有已存储的 fingerprint 失效，进而所有 Review 身份失效**（D003）。
因此它必须在第一版就写死并有测试钉住：同样输入必得同样值、任一输入变一个字节必得不同值。

### 3.3 `encrypted_token/secret` 的密钥（OQ-13）

**规则**：单个对称密钥由环境变量注入，**没有兜底默认值**——缺失时应用启动失败，
与 `FORGEPILOT_DB_PASSWORD` 的 fail-closed 形态一致（批次 1 已确立）。本批次**不做密钥轮换**。

**理由**：轮换需要密钥版本列与重加密流程，属新增结构；MVP 单节点部署下,
「缺失即启动失败」已经消除了最危险的形态(用弱默认密钥静默加密)。轮换缺口在 `result.md` 如实记录。

### 3.4 无效签名的响应（OQ-7）

**规则**：验签失败返回 `401`,**不写任何数据**,响应体不区分「仓库不存在」与「签名错误」。
Webhook 路径不带 `{projectId}`,按 payload 里的 repository 身份路由——但**必须先按身份取到 secret 才能验签**,
因此路由查表发生在验签之前、写入之前。查不到仓库同样返回 `401`。

**理由**：区分两者会让攻击者用响应差异枚举「哪些仓库已接入本系统」。与批次 1「不存在与无权限同解」同型。

### 3.5 SSRF 策略与测试接缝的冲突（OQ-9）

`api_base` 是 LEADER 可配置的,因此是 SSRF 入口:指向 `169.254.169.254` 就能读云元数据,
指向内网地址就能拿本系统当跳板。策略必须拒绝 loopback、私有网段与 link-local。
**但所有 stub 测试都跑在 `127.0.0.1`。** 这两条直接冲突。

**规则**:

- `OutboundUrlPolicy` 是一个始终生效的 bean,**默认拒绝** loopback、`10/8`、`172.16/12`、`192.168/16`、
  `169.254/16`、`::1` 与非 `http(s)` 协议。
- 它额外读一份**显式主机白名单**,**生产默认为空**。
- 测试用 `@DynamicPropertySource`（`PostgresTestBase` 已在用的同一机制）把 `127.0.0.1` 加进白名单。
- **策略本身必须有一条白名单为空的独立测试**,逐条断言上述网段被拒——包括 `169.254.169.254`。

**理由**:最坏的解法是"测试时把策略关掉",那样策略上线时从未被执行过一次。
本形态下策略在集成测试里**始终是打开的**,测试只是加了一条窄而显式的例外;
而"默认拒绝"这件事由那条白名单为空的测试单独钉死。配置错误的风险换来的是策略真的被测过。

### 3.6 稳定身份三元组全局唯一（OQ-3）

**规则**:`UNIQUE (provider, instance_identity, external_id)` 是**全局**唯一,不带 `project_id`。

**理由**:这不是选择,是 §3.4 的路由方式倒逼的结果。Webhook 不带 `{projectId}`、按 payload 里的仓库身份路由,
若两个项目能注册同一个仓库,一次投递就有两个目标和两份 secret,验签用哪一份都说不通。
代价是同一个仓库不能同时接入两个项目——MVP 接受,且与 §2.1「一个项目一个活动仓库」方向一致。

### 3.7 「有 PR 后三元组冻结」由 Service 保证（OQ-4）

**规则**:更新 `scm_repository` 时先对该行加锁,若已存在任何 PR 则拒绝修改 provider / instance_identity /
external_id,返回 `409`。`api_base` 可改,但必须验证仍指向同一实例（规范化后 `instance_identity` 不变）。

**理由**:这是跨行规则(「本行的列能不能改」取决于另一张表有没有行),没有任何 immediate 约束能表达,
而 §2.1 只为 `finding` 授权了约束触发器。因此它与 [D013.9](../../../../../docs/v2/DECISIONS.md#d013)
「至少一个 LEADER」属同一类:**每次提交后的 Service 不变式**,由集成测试覆盖而非由数据库执行。
`database-guidelines.md` 那句「只由 Service 执行的约束不算被执行」的用意是禁止用 Service 校验去**替代**
数据库能做的事;此处数据库做不到,不属该禁令范围——但必须在 `result.md` 如实记为「非数据库执行」。

## 4. 模块与类布局

```text
ai/         AiGateway（chat/embed 唯一入口）· AiCallContext（不透明 id）· PromptSanitizer
            AiCallLog · AiCallLogRepository · AiFailurePolicy（超时 + 恰好一次 retry）
knowledge/  KnowledgeDocument · KnowledgeChunk · 各自 Repository
            KnowledgeService（上传、分块、embed、检索）· KnowledgeUploadValidator
            ChunkSearchRepository（唯一写原生向量 SQL 的地方）
scm/        ScmRepository · PullRequest · PullRequestRequirementEvent · 各自 Repository
            scm.github/：GitHubClient（base URI 取自 api_base）· GitHubWebhookController
            WebhookSignatureVerifier · ReviewInputFingerprint · RequirementReferenceParser
requirement/  新增 RequirementDirectory（只读 facade，供 scm 用，D015.6）
```

- `scm.github` 是子包白名单允许的两个之一（ArchUnit 规则 6），其余一律直接放在 feature 包下。
- `ChunkSearchRepository` 是**唯一**允许出现 `::vector` 与 `<=>` 的地方（[D015.4](../../../../../docs/v2/DECISIONS.md#d015)）。

## 4.1 批次 2 新增的两个端点（供 Phase 7 前端冻结形状）

```text
PUT  /api/projects/{projectId}/pull-requests/{pullRequestId}/requirement
     body {requirementId: number|null, reason?: string}   仅 LEADER
     同事务写一条 pull_request_requirement_event(actor_type=USER)
     置空是合法纠正，同样留痕；无变化的纠正被 ck_..._is_a_change 拒为 409
     外项目 requirementId → 422，与从未存在的 id 字节相同

POST /api/projects/{projectId}/requirements/{requirementId}/guidance
     一次性实现建议；不落表、不建会话、无 SSE
     LEADER 恒可；DEVELOPER 仅限指派给自己的需求（PRD §3）
```

**P1 的 DEVELOPER 半条未实现**：「本人 PR 且当前 head 尚无人工终局 Decision」——
批次 2 没有 `review`，「尚无终局」无法表达，写一个恒答「没有」的判断会**多授权**。
只有 LEADER 能到达该端点，已写进 service javadoc，且必须在 `result.md` 记为部分实现。

## 5. 测试策略

**必须有的集成测试**（真实 PostgreSQL + JDK HTTP 服务器，无凭据）：

1. 附件归属：公共知识挂到 Requirement 被 `23503` 拒绝；跨项目 document 被拒；子表两列 NOT NULL 有反证。
2. 维度：错维度写入被应用层显式拒绝；自洽 CHECK 被 `23514` 拒绝；混维度导致 TopK 失败的毒丸行为有记录性测试。
3. 文本边界：NUL(`22021`)、非法 UTF-8(`22021`)、**孤立代理项(应用层拒绝)**、超限(应用层拒绝)。
4. AI Gateway：超时真的触发；**恰好重试一次**（用请求计数证明）；永久错误不重试（计数停在 1）；两次调用都落 `ai_call_log`。
5. `OutboundUrlPolicy` 自身：白名单为空时逐条拒绝 loopback、私有网段、`169.254.169.254`、非 http(s) 协议。
6. Webhook：正确签名通过；改一个字节被拒；**解析后结构相同但字节不同**（重排键/加空白）被拒——这条证明的是真按原始字节验签。
7. 幂等与乱序：同一事件重放不产生新状态；旧 `source_updated_at` 不得回退 head/base/patch。
8. fingerprint：同输入同值；任一输入变一字节则变值；`source_revision` 变化**不**改变它。
9. 跨项目隔离：A 项目用 B 项目的 document / chunk / repository / PR id 读写全部被拒且不泄漏存在性。

## 6. 已知会被打破的既有断言

`FoundationDatabaseTest.EXPECTED_TABLES` 与 `scripts/phase1-compose-smoke.sh` 的 `expected_tables`
都按「恰好六张表」写死，本批次新增七张必然使其失败。两处都改成新的**完整十三张表名列表**，
不得退化成只比数量。
