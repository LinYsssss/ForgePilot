# 批次 2 结果（Phase 4 + Phase 5）

任务：`08-21-batch-2-ai-knowledge-scm`
授权依据：[D012](../../../docs/v2/DECISIONS.md#d012)（批次划分）、[D014](../../../docs/v2/DECISIONS.md#d014)（闸门由编排会话执行）。
上一批次：`.trellis/tasks/archive/2026-08/08-21-batch-1-auth-project-requirement/result.md`。

> **这份报告的记法**（延续批次 1）：验收条件只有通过与不通过，**部分通过必须记为部分通过**。
> §7 的每一条缺口都是有意如实记录的，不是遗漏。

## 1. 完成情况

七张新表全部落地，AI Gateway、Knowledge、GitHub SCM 三条切片可运行且被真实 PostgreSQL 与真实 socket 覆盖。

| 项 | 结果 |
|---|---|
| 业务表 | **13 张**（批次 1 六张 + 本批次七张），十六张的门槛未被触碰 |
| 后端测试 | **155 个，0 失败 0 错误 0 跳过** |
| 新增运行时依赖 | **零**（`backend/pom.xml` 自 `f1d02e1` 起未再改动） |
| 顶层包 | 仍 8 个；子包只有 `scm.github` |
| 前端 | **未改动**（`git diff 1c6922f..HEAD -- frontend/` 为空） |
| CI | `2892059` 四 job 全绿；`ci.yml` 中仍无 `secrets.*` |

## 2. §2.1 列清单的唯一一处扩充（必须单独列出）

`knowledge_document` 增加两列，**这是本批次对 ARCHITECTURE.md §2.1 列清单的唯一扩充**：

| 列 | 类型 | 为什么必须加 |
|---|---|---|
| `title` | `varchar(512) NOT NULL` | 上传文件名 / 展示标题。§2.1 的列清单没有承载它的地方，而文档列表不显示名字就不可用 |
| `failure_reason` | `text` | §6 明文要求展示失败原因；`ck_knowledge_document_failure_reason` 使 `FAILED` 文档必须带原因 |

两列均**不新增表、不改变任何既有语义**，属 §2.1 结尾允许的「同表补列」。裁定见 `design.md` §2.1（OPEN-2/OPEN-3）。

除此之外，`ai_call_log.token` 拆成 `prompt_token`/`completion_token`/`total_token` 三列（OPEN-1），
以及 `knowledge_document` **不设** `model`/`version` 列（OPEN-4）——这两项是对 §2.1 内已有条目的具体化，
不是扩充，但一并在此声明以免日后对不上账。

## 3. 非数据库执行的不变式（必须如实标注）

按 `database-guidelines.md`「只由 Service 执行的约束不算被执行」，以下两条**不由数据库执行**：

1. **稳定身份三元组在有 PR 后冻结**（AC15 / `design.md` §3.7）。
   这是跨行规则——「本行的列能不能改」取决于 `pull_request` 有没有行——没有任何 immediate 约束能表达，
   而 §2.1 只为 `finding` 授权了约束触发器。实现形态：更新前对 `scm_repository` 行加悲观锁，
   若已存在任何 PR 则拒绝改动 provider / instance_identity / external_id，返回 `409`。
   **由 `ScmRepositoryApiTest.oncePullRequestsExistTheIdentityIsFrozenButTheApiBaseStillMoves` 单线程覆盖；
   并发路径未测**（见 §7.5）。

2. **与项目既有维度不符的向量被拒**（AC4 / [D015.3](../../../docs/v2/DECISIONS.md#d015)）。
   数据库只有「自洽 CHECK」（`dimension = vector_dims(embedding)`），管不了跨行一致。
   真正的防线在 `ChunkSearchRepository.writeEmbedding`，由 `KnowledgeGuardTest` 覆盖。
   `KnowledgeAndScmConstraintTest.aDimensionlessColumnAcceptsAnythingUntilAQueryNeedsThem`
   是记录性测试，把「混维度会让整个项目的 TopK 失败」这件事钉在测试里而不是留在注释里。

三元组唯一性本身（`UNIQUE (provider, instance_identity, external_id)`）**是**数据库执行的，全局生效，
由 `repositoryIdentityIsUniqueAcrossEveryProject` 证明。冻结与唯一是两件事，不要混为一谈。

## 4. 实际执行的命令与结果

### 后端全量

```bash
cd backend && flock …/maven.lock docker run --rm --network host \
  -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

```
[INFO] Tests run: 155, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

退出码 `0`。跑在当前工作树（`2892059`）上，不是复用早先的构建。

### 依赖零新增

```bash
git log --oneline -- backend/pom.xml | head -1
# f1d02e1 feat(auth): add local accounts, sessions and CSRF   ← 批次 1 的提交
```

`pom.xml` 自批次 1 起未再改动。[D015.8](../../../docs/v2/DECISIONS.md#d015) 的前提成立：
`jdk.httpserver` 与 `MockRestServiceServer` 都已在 classpath 上，WireMock/MockWebServer 一个都没加。

### Compose 空库冷启动

```bash
scripts/phase1-compose-smoke.sh forgepilot-phase1-batch2-1787333496
```

```
Compose smoke passed for project forgepilot-phase1-batch2-1787333496 (pgvector 0.8.6, 13 application tables).
```

退出码 `0`。三个容器全部 healthy；`expected_tables` 已改为十三张**全名**逐名比对，不是只比数量。
卷是新建的，跑完即删——这是空库冷启动，不是复用既有数据。

### CI

| 提交 | run | 结果 |
|---|---|---|
| `2892059` | 32507656571 | **success**（2m53s） |
| `635d78f` | 32504570833 | success |
| `2da43e3` | 32501632069 | success |
| `e560f22` | 32496649469 | success |
| `7daf632` | 32493525957 | **failure**（23s）——见 §7.1 |

### 边界检查（逐条实跑）

```bash
find …/com/forgepilot -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort
#   ai auth common knowledge project requirement review scm        ← 8 个
find …/com/forgepilot -mindepth 2 -type d
#   …/scm/github                                                    ← 唯一子包
grep -rn "Logger\|slf4j" …/main/java | grep -v "/common/"           → 无
grep -rln "::vector\|<=>" …/main/java
#   ChunkSearchRepository.java（代码）+ KnowledgeChunk.java（仅 javadoc 提及）
grep -rn "api\.openai\|api\.anthropic\|dashscope\|generativelanguage" main/{java,resources} → 无
grep -rn "ON DELETE" db/migration/                                  → 仅 V5:81 author_user_id
grep -n "secrets\." .github/workflows/ci.yml                        → 无
ls …/com/forgepilot/review/                                         → 只有 package-info.java
grep -rni "^create table" db/migration/*.sql | wc -l                → 13
grep -rn "AiUseCase.REVIEW" …/main/java                             → 无（批次 3 预留）
```

## 5. 关键实现形态（与决策的对应）

| 形态 | 决策 | 落点 |
|---|---|---|
| `ai_call_log.review_id` 建列不建外键，且全为 NULL | [D015.1](../../../docs/v2/DECISIONS.md#d015) | `aiCallLogHasNoReviewForeignKeyYetAndNoRowsUsingIt` |
| `requirement_attachment` 两列 NOT NULL 是承重的 | [D015.2](../../../docs/v2/DECISIONS.md#d015) | `aNullableRequirementIdWouldMakeTheOwnershipCheckEvaporate`（临时表反证，不改真实 schema） |
| 不建向量索引、不绑定维度 | [D001](../../../docs/v2/DECISIONS.md#d001) / [D015.3](../../../docs/v2/DECISIONS.md#d015) | 迁移中无 `USING ivfflat/hnsw`；`vector` 列无维度参数 |
| `embedding` 与 `dimension` 都不映射进实体 | [D015.4](../../../docs/v2/DECISIONS.md#d015) | `KnowledgeChunk` javadoc 记录了「映射 dimension 是陷阱」的实测理由 |
| 孤立代理项在应用层拒绝 | [D015.5](../../../docs/v2/DECISIONS.md#d015) | `aLoneSurrogateIsRejectedBecauseTheDatabaseNeverSeesIt` |
| `scm` 只经只读 facade 用 `requirement` | [D015.6](../../../docs/v2/DECISIONS.md#d015) | `RequirementDirectory`；grep 确认 `scm` 未注入 `RequirementRepository` |
| 无凭据测试打到 JDK 自带 HTTP 服务器 | [D015.8](../../../docs/v2/DECISIONS.md#d015) | `AiGatewayTest`、`GitHubWebhookIngestionTest` 均起真实 socket |
| 提升为公共知识是复制 | [D005](../../../docs/v2/DECISIONS.md#d005) | `promotingCopiesTheDocumentAndLeavesTheOriginalAttachmentAlone` |
| 授权键是 `scm_external_user_id`，不读用户名 | [D010](../../../docs/v2/DECISIONS.md#d010) / P11 | `removingAMemberClearsOnlyThePullRequestAuthorColumn` + grep |

**加密密钥的往返正确性是被证明的，但不是被直接断言的**：`ScmRepositoryApiTest` 只断言了
「响应体不含明文」「列里不是明文」。真正证明 `decrypt(encrypt(x)) == x` 的是 webhook 路径——
夹具用 `cipher.encrypt(secret)` 落库，再用**明文** `secret` 算 HMAC 签名，
生产代码必须解密出同一个 secret 才可能验签通过。解密若有任何偏差，13 个 ingestion 测试会全红。
这比一句直接的往返断言更强，但它是**间接**的，在此写明以免日后误读。

## 6. 边界（有意不做）

- 无 `review` / `finding` / `finding_event` 表，无 Review 引擎、无 Finding、无人工决策闭环——批次 3。
- 无 GitLab（Phase 8）。`scm.gitlab` 虽在子包白名单内，本批次未落地。
- 无向量索引、无维度绑定、无 Conversation / SSE / 多轮会话 / Prompt Registry / 通用 ContextBuilder。
- 未新增第 17 张表、未新增顶层包、未新增一级菜单、未新增运行时依赖、未改动前端。
- 迁移中除 `pull_request.author_user_id` 外无 `ON DELETE`——它是 §2.3 唯一规定了删除语义的地方，
  与批次 1「全表不写 ON DELETE」不矛盾：批次 1 涉及的表，§2.3 都没规定删除语义。

## 7. 风险与已知缺口

### 7.1 `7daf632` 被推上去时是红的

**这是本批次最严重的过程失误，如实记录。** 该提交的 CI（run 32493525957）在 23 秒内失败：

```
[ERROR] .../ScmRepositoryApiTest.java:[203,28] cannot find symbol
[ERROR]   symbol:   method sameShapeAs(org.springframework.test.web.servlet.MvcResult)
[ERROR] .../ScmRepositoryApiTest.java:[208,28] cannot find symbol
```

**成因**：我先跑了一次绿构建，然后用 `git add -A` 提交——而代理在那次构建**之后**又写了测试文件。
于是我提交了自己从未编译过的代码。测试编译失败，四个 job 里的 backend job 直接挂掉。

**已修复**：下一个提交 `e560f22` 起 CI 恢复绿，此后每一次推送都是绿的。
**教训已成规矩**：`git add -A` 之后必须重跑构建再推，不能复用推送前的绿。

### 7.2 D015.7 的「在 PR 行上标记超限」未实现

[D015.7](../../../docs/v2/DECISIONS.md#d015) 原文要求「超限显式失败并**在 PR 行上标记**，不静默截断」。
实现只做到前半条：超过 `ChangedFile.MAX_TOTAL_CHARS`（4,000,000 字符）时抛 `422`，**整条投递什么都不写**。
`PullRequestSyncService.manifest()` 的 javadoc 自己写明了原因：`pull_request` 上没有可以标记的列。

**判断**：不静默截断这一条的实质（Review 绝不会被告知一份残缺的 diff 是完整的）已经达成，
但「标记」这半条确实没做。补它需要在 `pull_request` 上再加一列——那超出本批次授权的 §2.1 扩充范围。
**这半条记为未实现，不记为通过。**

**并且该路径完全没有测试**：`MAX_TOTAL_CHARS` 在测试代码中零引用。超限分支从未被执行过。

### 7.3 P1 的 DEVELOPER 半条授权未实现

`PUT /api/projects/{p}/pull-requests/{id}/requirement` 目前**只有 LEADER 能到达**。
PRD P1 的另一半——「本人 PR 且当前 head 尚无人工终局 Decision」——**故意没做**：
批次 2 没有 `review` 表，「尚无终局 Decision」无法表达；写一个恒答「没有终局」的判断会**多授权**，
比不做更危险。理由已写进 service javadoc 与 `design.md` §4.1。**批次 3 建 `review` 后必须补上。**

对照：`POST …/requirements/{id}/guidance` 的 DEVELOPER 规则**已实现**（限本人被指派的需求），
因为 `assignee_id` 今天就存在，规则可以完整表达。这个不对称是有理由的，不是随手。

### 7.4 密钥轮换缺口

`encrypted_token` / `encrypted_secret` 用单个对称密钥（AES-256-GCM），由 `FORGEPILOT_SCM_SECRET_KEY` 注入，
**没有兜底默认值**——缺失时应用启动失败。**本批次不做密钥轮换**：轮换需要密钥版本列与重加密流程，属新增结构。

**「缺失即启动失败」这条 fail-closed 属性本身没有测试。** `ScmSecretCipher` 的构造器会在空值时抛异常，
`application.yml` 里也确实没有默认值，但没有任何测试断言「不给密钥则上下文起不来」。
这是一条被声明却未被测量的属性。

### 7.5 未测的路径（诚实清单）

| 路径 | 现状 |
|---|---|
| 三元组冻结的**并发**竞争 | 悲观锁只有单线程测试；两个请求同时改身份的交错未测 |
| `GitHubClient.required()` 的**拒绝**分支 | 该守卫被 13 个 ingestion 测试证明「会跑且接受良构载荷」，但「缺字段则 422」从未被断言 |
| changed-file 超限（§7.2） | 分支从未执行 |
| 缺密钥启动失败（§7.4） | 未断言 |

前两条都属于「守卫存在且在happy path上被证明运行，但其拒绝分支未被测量」。
`required()` 那条尤其值得写下来：本批次它曾有过一个真实 bug（把 `"base.sha"` 当成字段名去 `base` 里找），
是被 happy path 测试挡下来的——**那证明守卫会跑，不证明守卫会正确拒绝**，两件事不能混。

### 7.6 继承自批次 1 的缺口（未解决，不重复论证）

批次 1 `result.md` §7 的第 1、2、4、5、6、7 条全部仍然成立：进程重启会话失效、
`session_version` 每请求两次索引查询、`ac_key` 退休编号的窄缺口、需求正文无乐观锁、
**无自动化浏览器点击闭环**、smoke 脚本名与内容错位。本批次未改善也未恶化其中任何一条。

第 3 条（禁用账户不踢已有会话）仍不可达：本批次未引入禁用账户接口。

## 8. 验收条件逐条

| AC | 结论 | 证据 |
|---|---|---|
| AC1 13 张表、复合外键、跨项目写入被**数据库**拒绝 | 通过 | `FoundationDatabaseTest`（表集合）、`noProjectScopedRowMayReachAcrossProjects`（绕过 Service 直写，逐表断言 23503） |
| AC2 公共知识不能挂需求附件；附件钉在自身归属；**并有反证** | 通过 | `publicKnowledgeCannotBeAttachedToARequirement`、`anAttachmentIsPinnedToExactlyTheDocumentsOwnRequirement`、`aNullableRequirementIdWouldMakeTheOwnershipCheckEvaporate` |
| AC3 类型与归属不匹配 23514；`FAILED` 必带原因 | 通过 | `aDocumentsScopeMustAgreeWithItsType`、`aFailedDocumentMustCarryItsReason` |
| AC4 维度自洽 CHECK；异维度被应用层拒；混维度记录性测试 | 通过 | `aChunksDeclaredDimensionMustMatchItsVector`、`aVectorWhoseDimensionDisagreesWithTheProjectIsRefused`、`aDimensionlessColumnAcceptsAnythingUntilAQueryNeedsThem` |
| AC5 NUL / 非法 UTF-8 / 孤立代理项 / 超限显式失败 | 通过 | `KnowledgeGuardTest` 4 条；孤立代理项测试先断言「驱动确实会静默改成 `?`」，故不空转 |
| AC6 检索一律带 `projectId`；`::vector`/`<=>` 只一处 | 通过 | `searchNeverReachesAnotherProject`；grep 确认代码层面只有 `ChunkSearchRepository` |
| AC7 提升产生**新** Document，原行未被改写 | 通过 | `promotingCopiesTheDocumentAndLeavesTheOriginalAttachmentAlone` |
| AC8 超时 / 恰好重试一次 / 永久错误不重试 / 两次都落库 / 畸形 JSON 判失败 / Authorization 头 | 通过 | `AiGatewayTest` 11 个方法（含两个参数化，覆盖 429/500/503 与 400/401/404/422），断言的是 stub 的**请求计数** |
| AC9 无硬编码 provider host；base URI 取自 `api_base` | 通过 | grep 无命中；`GitHubClient` 由 `api_base` 逐仓库构建，测试正是靠这一点指向 loopback stub |
| AC10 `OutboundUrlPolicy` 逐条拒绝，测试中策略**始终开启** | 通过 | `OutboundUrlPolicyTest` 4 条；`onlyAnExplicitAllowlistEntryReopensADeniedHost` 证明例外是窄的 |
| AC11 原始字节验签；改一字节被拒；**同结构不同字节**被拒；401 零写入且不可区分 | 通过 | `WebhookSignatureVerifierTest` 5 条（含 GitHub 官方发布向量）、`anInvalidSignatureWritesNothingAndCallsNothing`、`anUnknownRepositoryAnswersExactlyLikeABadSignature` |
| AC12 fingerprint 确定性；排序字段不参与 | 通过 | `ReviewInputFingerprintTest` 6 条，含钉死规范形的字面量断言 |
| AC13 重放幂等；旧事件不回退 | 通过 | `replayingTheSameDeliveryChangesNothing`、`anOlderDeliveryNeverRollsTheSnapshotBackwards`、`anEqualTimestampStillWritesBecauseTheReadIsAuthoritative` |
| AC14 `REQ-<n>` 按项目解析；外项目 id 不阻断入库 | 通过 | `aReferenceToAnotherProjectsRequirementLeavesThePullRequestUnlinked`、`aReferenceNobodyCanResolveIsSilentlyNoAssociation`；grep 确认 `scm` 未注入 `RequirementRepository` |
| AC15 三元组全局唯一；有 PR 后冻结（**须记为非数据库执行**） | 通过 | 唯一性由库执行（`repositoryIdentityIsUniqueAcrossEveryProject`）；冻结由 Service 执行，**已按要求记入 §3.1**；并发路径未测（§7.5） |
| AC16 移除成员置空 `author_user_id`，快照不变；不读 `scm_username` | 通过 | `removingAMemberClearsOnlyThePullRequestAuthorColumn` + grep |
| AC17 `review_id` 建列未建外键且全为 NULL | 通过 | `aiCallLogHasNoReviewForeignKeyYetAndNoRowsUsingIt`：查 `pg_constraint`×`pg_attribute` 按**列**判定（不按约束名，改名的批次 3 约束也躲不过），并同时断言其它复合外键仍在——否则「没有外键」这个断言会因表上一个外键都没有而空转 |
| AC18 ArchUnit 七条；八个顶层包；子包只有 `scm.github` | 通过 | `ArchitectureRulesTest` 8 个测试 + §4 的四条 grep |
| AC19 `verify` 全绿无 skip；**pom 零改动**；Compose 十三表；CI 四 job 绿且无 `secrets.*` | 通过 | 见 §4。`7daf632` 曾红（§7.1），但**当前提交** `2892059` 四 job 全绿 |
| AC20 `result.md` 完整：§2.1 补列单列、非库执行标注、密钥轮换记录、未触发 D015 外新决策 | **部分通过** | 前三项见 §2/§3/§7.4，均已落实；但本批次出现了 **D015 未覆盖的两处实现级偏离**——见下 |

**AC20 只记部分通过。** 它要求「未触发 [D015](../../../docs/v2/DECISIONS.md#d015) 之外的新决策」，
而本批次实际做了两个 D015 没有授权、也没有回写成 D0xx 的判断：

1. **D015.7 的「标记」半条被放弃**（§7.2）。这不是执行 D015.7，是偏离它。
2. **P1 的 DEVELOPER 半条被判定为不可实现并跳过**（§7.3）。理由充分（多授权比不授权危险），
   但它是一个产品级授权范围的收窄，只写在 `design.md` §4.1 和 javadoc 里，没有升格为决策。

把 AC20 记成「通过」会让这份报告在**它自己要求诚实的那一条上**失真。两处偏离都已在上面写清，
批次 3 开始前应当补一条 D016 把它们正式化——尤其是 §7.3，因为批次 3 建 `review` 之后它必须被补上。

## 9. 回滚

按文件组独立回滚：`V4`/`V5` 迁移+实体、`ai`、`knowledge`、`scm`、`requirement` 的 facade 与 guidance 各自成组。
`V2`–`V5` 一旦被任何环境应用过就不得编辑或重编号——只能追加 `V6`。
数据库回滚等价于重建空库（尚无生产数据，`clean-disabled: true`，靠丢卷而非 `flyway clean`）。

## 10. 批次 3 的前置条件

1. **必须先补 D016**，把 §7.2、§7.3 两处偏离正式化（见 AC20）。
2. 建 `review` 表的同一批次内，**必须**补上 P1 的 DEVELOPER 半条授权（§7.3），并补 `ai_call_log.review_id`
   的外键（[D015.1](../../../docs/v2/DECISIONS.md#d015)）——`aiCallLogHasNoReviewForeignKeyYetAndNoRowsUsingIt`
   已经把「此刻全为 NULL」这个前置条件钉死，补外键不会撞上历史数据。
3. `review` 是十六张表里的第 14 张；`finding`、`finding_event` 是第 15、16 张。**批次 3 之后不得再有新表。**
4. `PullRequestChanged` 事件已在写 PR 的同一事务内发布（`theChangedEventIsPublishedInsideTheTransactionThatWroteTheRow`
   与 `aFailingListenerRollsTheWholeIngestionBack` 共同证明），批次 3 的 Review 触发直接挂在它上面。
5. §7.5 的四条未测路径中，**三元组冻结的并发竞争**最值得在批次 3 补——批次 3 会引入更多并发写入点。
