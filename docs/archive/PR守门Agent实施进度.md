# PR 守门 Agent 实施进度

> 本文是长任务的权威续开发入口。继续开发前先读取设计、Roadmap、当前阶段计划、本文、Git 状态和最近提交。

## 1. 工作位置

- 功能分支：`feat/pr-gatekeeper-agent`
- 隔离工作树：`F:\202605New\.worktrees\pr-gatekeeper-agent`
- 基线分支提交：`fad60d2 chore: ignore isolated worktrees`
- 当前阶段：Phase 5 Task 9 已完成，下一步 Task 10 Approval-aware SCM Publication
- 最新完成任务：Phase 5 Task 9（受 Finding Gate 约束的 Patch 生成、独立 sandbox 验证与人工审批前置）

## 2. 已完成范围

### Phase 1：工程基线

已全部完成：可复现前端构建、Java/Node/Maven 版本基线、Flyway、Testcontainers 测试骨架、核心回归测试、CI 和统一质量门。

### Phase 2：Agent 控制面

已全部完成：

- `agent_run`、`agent_step`、`tool_invocation` 持久化和状态机。
- 预算、类型化工具、Review Plan 和结构化模型输出校验。
- 事务 Outbox、Agent 专用 MQ、幂等消费和失败重试。
- 服务重启恢复、Timeline/SSE、旧报告兼容投影和 Micrometer 指标。

对应后续提交：

```text
5bab09d feat: publish agent steps through transactional outbox
6ca9676 feat: schedule idempotent agent steps
b9bff2a feat: recover interrupted agent runs
78e5bbb feat: expose agent run timeline
bf9446f feat: project agent results to legacy reports
426aa48 feat: instrument agent control plane
```

### Phase 3：SCM 与 Sandbox

已完成 Task 1 至 Task 12 的代码与静态范围；Task 12 的 Docker 动态验收待补跑：

1. SCM installation 和 webhook delivery 持久化。
2. GitHub/GitLab 中立契约。
3. GitHub PR Webhook 验签、归一化和幂等。
4. GitLab MR Webhook 验证、归一化和幂等。
5. Webhook 事件持久化创建 Agent Run。
6. 后端与 Runner 字节兼容的签名 Sandbox Job 协议。
7. 独立 Spring Boot Sandbox Runner：专用 RabbitMQ 队列、签名/过期/重放校验、可替换执行器、无 HTTP 端口、Dockerfile 和 Compose 服务。
8. 固定命令 ID 白名单、Pinned image 校验、`--network none`、只读根文件系统、非 root、CPU/内存/PID 限制、临时工作区、路径/符号链接逃逸防护、超时/取消 kill 和幂等清理。
9. Maven/Gradle/Python/npm/pnpm/yarn 锁文件内容寻址缓存键、独立联网准备策略、安全环境变量白名单、分析任务只读缓存挂载和缺缓存 `ENVIRONMENT_INCOMPLETE` 结果。
10. ZIP/TAR/压缩归档安全解包、绝对路径/遍历/符号链接和私网子模块 URL 防护；Runner `repo.unpack`、`git.file`、`git.diff`、`code.search` 有界处理器；后端三个只读 Agent 工具及签名 RabbitMQ 网关。
11. GitHub Check Run/PR comment 与 GitLab Commit Status/MR Note 发布客户端；发布内容包含摘要、阻断 Finding、证据链接、Agent Run URL 和 Patch 状态；暴露 Patch 内容必须审批；WireMock 契约测试覆盖请求头、关键字段和未审批零请求拒绝。
12. 已新增 PostgreSQL/RabbitMQ Testcontainers 的 GitHub Webhook→Agent Run 集成测试，Compose 改为必填数据库/MQ 密码并将 RabbitMQ、backend、model-service 宿主端口绑定回环地址；新增 SCM/Sandbox 运维与安全验收手册。当前主机无 Docker，集成测试和容器隔离动态验收仍未通过。

依赖缓存只有存在 `.complete` 标记才视为可用；准备请求受命令、时限、大小和环境变量白名单约束。真实依赖下载、Docker 挂载和 RabbitMQ Runner 请求/响应联调仍需在有 Docker/RabbitMQ 的环境补跑。

对应提交：

```text
24e03c0 feat: persist scm installations and deliveries
1b8423d feat: define scm provider contracts
c22b393 feat: receive github pull request webhooks
e098562 feat: receive gitlab merge request webhooks
944bbf1 feat: start agent runs from scm events
c9ee713 feat: define signed sandbox job protocol
484ee1f feat: add isolated sandbox runner service
aed5f6e feat: enforce sandbox container policy
05e69c3 feat: prepare isolated dependency caches
a4a6f6f feat: execute repository read tools in sandbox
52efc6b feat: publish agent reviews to scm providers
827ebd7 docs: document scm and sandbox operations
```

### Phase 4：插件、Patch 与评测

已完成 Task 1 至 Task 13 的可执行代码与静态验证：

- `RepositoryProfile`、`ChangeSet`、`ChangeAnalysis`、`ToolCommand` 和 `LanguagePlugin` 契约。
- 纯语言、混合语言和构建文件变更的确定性插件选择。
- `FindingCandidate`、`FindingEvidence`、Finding/Evidence JPA 持久化；证据包含类型、来源版本、文件/行、2048 字符有界摘录、分数和原文 SHA-256。
- 因仓库已存在 SCM `V7__scm_webhooks.sql`，Finding/Evidence 迁移安全顺延为 `V8__findings_and_evidence.sql`；后续 Patch 迁移顺延为 V9，不修改既有迁移。
- Java 插件检测 Maven/Gradle，使用 JavaParser 提取变更类、方法、注解和调用；PMD、SpotBugs、Checkstyle XML 及 SARIF 归一化为 `FindingCandidate`。
- backend 和 Runner 同步注册 Maven/Gradle compile/test、PMD、SpotBugs、Checkstyle 固定命令 ID；Runner 使用固定可执行路径与参数，不调用 shell 解释器。
- Java Maven/Gradle 评测夹具位于 `demo-repos/evaluation/java/`。
- Python 插件检测 `pyproject.toml`、`requirements.txt` 和 `.py` 变更，注册 Ruff、Bandit、Pytest 固定命令；Ruff/Bandit JSON 归一化为 Finding，Pytest JUnit 归一化为独立验证结果而非缺陷。
- Runner 同步注册固定 Ruff/Bandit/Pytest 可执行路径；Python 评测夹具位于 `demo-repos/evaluation/python/`。
- JavaScript/TypeScript 插件检测 npm/pnpm/yarn、TypeScript、Jest/Vitest；ESLint/Semgrep/tsc/Jest/Vitest 均通过 Runner 固定二进制执行，不调用 `npm run`、`npx` 或 payload 中的 scripts。
- ESLint/Semgrep/TypeScript 输出归一化为 Finding，Jest/Vitest JSON 归一化为验证结果；恶意 `package.json` script 夹具证明其不会进入命令契约。
- 证据置信度使用版本 `evidence-confidence-v1` 和固定权重：tool `0.35`、location `0.20`、knowledge `0.20`、verifier `0.15`、test `0.10`；冲突与过期位置负向扣分并 clamp 到 `[0,1]`。
- Gate 仅阻断 HIGH/CRITICAL、置信度达到可配置阈值且代码位置有效的 Finding；模型单独信号无法阻断。
- `V9__finding_confidence_decisions.sql` 持久化决策版本、阈值、结果及每项贡献；Patch 迁移继续顺延为 V10。
- Finding fingerprint 由 category、规范化路径、symbol 和行邻域 hash 构成；语义重复候选合并时以证据类型、来源版本和内容 hash 去重，避免同一来源重复增信。
- Verifier 冲突会拒绝候选；`V10__finding_verification.sql` 为 Finding 增加 fingerprint 和 rejection reason，保留 rejected 候选供评测。Patch 迁移继续顺延为 V11。
- Hybrid Review Context 从 changed paths、symbols、imports、annotations、字符串和工具 rule ID 构造确定性查询，并通过 project/document 双重范围调用 RAG。
- 混合排序固定组合 vector `0.40`、lexical `0.25`、changed-symbol `0.20` 和 document-type `0.15`；结果经过阈值过滤、规范化内容去重和 UTF-8 字节预算约束。
- 每个上下文来源均标记为不可信证据，携带 PR head SHA/source version 及 `source#chunk-N` 精确引用；知识文档索引优先 Markdown 标题和 fenced code 边界，无法识别结构时保留固定字符分块回退。
- `V11__patch_candidates_and_approvals.sql` 新增不可变 Patch Candidate、Finding 关联和审批请求表，不修改 V1-V10；Patch 绑定 Agent Run、head SHA、Finding IDs、generator model、prompt version 和内容 SHA-256。
- Unified diff 策略拒绝绝对路径、路径遍历、二进制内容、rename、伪造 `---/+++` 标记、CI/CODEOWNERS/Flyway 等受保护文件，以及文件数和新增+删除行数超限；stale head SHA 和跨 Agent Run Finding 绑定在持久化前拒绝。
- Runner 固定注册 `patch.apply.check`、`patch.apply` 和 `patch.validate`，安全解包后在同一临时工作区依次执行 baseline、apply check、apply 和 patched validation；验证命令、镜像与 CPU/内存/PID/超时限制保持一致，不接受任意 Shell。
- `V12__patch_validation_results.sql` 持久化 apply/build/test/scan 独立状态、目标 fingerprint/reproducer 是否消失、结构化 before/after 结果、有界日志和验证时间；仅 apply 成功且目标消失的 Patch 具备审批资格，构建和测试失败仍独立保留。
- `V13__patch_approval_constraints.sql` 与审批实体/API 记录 approver、APPROVED/REJECTED、不可变 Patch hash、head SHA、comment 和时间；项目所有者授权、Agent Run/Patch 归属和 stale head 每次决定前重新校验，相同决定幂等、冲突决定不可变。
- Vue 新增并实际挂载 Agent 审批工作区，拆分 Timeline、Findings/证据、Patch diff/下载/验证日志和 Approval 组件；只有 apply 成功、目标消失且未 stale 的 Patch 可批准，无效 Patch 禁用批准按钮。
- `evaluation/manifest.json` 固定 corpus/schema、digest-pinned 工具镜像、模型、prompt/finding schema、temperature=0 和预算；6 个 Java/Python/TypeScript 案例覆盖 TP、TN、歧义、Prompt Injection、broken build 与 known patch，并隔离 development/holdout。
- `EvaluationCorpusService` 校验每个案例的 category、severity、location、non-findings、可选 Patch、fixture、唯一 ID 和 split；`run-agent-evaluation.ps1` 默认 development 且剥离标签，生成物写入已忽略的 `evaluation/results/`。
- `EvaluationMetrics` 计算 precision、recall、F1、high-risk recall、false-positive rate、location accuracy、Patch apply/build/test rate、平均耗时和总成本；固定质量门为 recall 0.80、precision 0.70、location 0.90、repairable Patch apply 0.70。
- `EvaluationReportExporter` 输出稳定 JSON/Markdown；仓库 baseline 明确标注为已知混淆矩阵验证，不冒充 Docker corpus 实跑结果，timestamp 本地产物保持忽略。
- Backend 与 Runner 接入 Micrometer OpenTelemetry bridge/OTLP exporter，Spring HTTP/RabbitMQ observation 传播 W3C trace context，现有 Outbox trace ID 保留跨事务关联；指标标签静态测试拒绝 run/job/trace/project/repository 等无界 ID。
- Compose 新增 digest/version 固定的 OTel Collector 与 Prometheus 服务、回环绑定的 OTLP/Prometheus 端口和 Collector memory limiter/batch；当前主机没有 Docker，因此配置解析、镜像构建、真实 trace/metrics 流和安全隔离仍未动态验收。

对应提交：

```text
92dcd30 feat: define language plugin and evidence contracts
881c066 feat: add java analysis plugin
1c2fa5b feat: add python analysis plugin
c24117f feat: add javascript typescript analysis plugin
7e95b48 feat: calculate evidence-based gate decisions
1c73d51 feat: verify and deduplicate findings
a14674d feat: add hybrid review context retrieval
72a9f61 feat: validate generated patch candidates
b7f0f3b feat: verify candidate patches in sandbox
b6e0abe feat: add human patch approval workflow
fdc38ed test: add versioned agent evaluation corpus
3684993 feat: report agent evaluation metrics
e18b5e6 feat: add agent observability and release verification
c3b3c95 feat: complete observable pr gatekeeper agent
```

### Phase 5：LangChain4j + Agent + RAG 集成

Task 1 已完成：

- 通过 Maven Central 元数据和实际 JAR 核查选择 LangChain4j `1.8.0`。这是已核实与 Spring Boot 3.5.8 管理的 Jackson 2.19.x 对齐的最高稳定版本；LangChain4j 1.9.1 起要求 Jackson 2.20.x，1.12.2 起要求 2.21.x，因此未盲目采用最新 1.17.2，也未覆盖 Spring Boot 的 Jackson 版本。
- 使用 LangChain4j BOM 固定版本，直接引入 `langchain4j-core`、`langchain4j-open-ai` 和实际生产适配所需的 `langchain4j-http-client-jdk`；未引入会额外带入 OpenNLP 的高层聚合模块。
- 已固定并加载 `ChatModel`、`EmbeddingModel`、`ContentRetriever`、`ToolSpecification`、`OpenAiChatModel`、`OpenAiEmbeddingModel` 与 JDK HTTP Client API，字节码在 Java 17 上加载通过。
- 项目 Maven settings 显式使用 HTTPS Maven Central，消除宿主机全局 HTTP 阿里云镜像被 Maven blocker 拒绝、导致干净环境无法解析新依赖的问题。
- 新增 `app.ai.runtime`，仅允许 `legacy` 和 `langchain4j`。本地/测试允许确定性 mock；生产选择 LangChain4j 时必须使用真实 Chat provider，并按选择的 Embedding provider 校验 URL、Key 和模型名。
- 配置异常只输出缺失的属性名称，不回显密钥或不可信 runtime 输入。
- 依赖树确认 Jackson 仍由 Spring Boot 固定为 2.19.4；LangChain4j 使用 JDK HTTP Client；唯一 SLF4J provider 仍为 Logback。现有 OTel OkHttp sender 不属于 LangChain4j 模型调用栈。
- Task 1 聚焦测试 12 项通过，覆盖版本、Java 17 字节码、完整 Spring Boot mock runtime 上下文、Jackson、SLF4J、HTTP Client、无关 OpenNLP 排除、runtime 枚举和生产配置失败策略。

Task 2 已完成：

- `LangChain4jAgentModelClient` 通过 OpenAI-compatible Chat Completions API 保持 system/user 消息边界，固定 temperature、关闭框架重试和请求/响应日志，并使用 JDK HTTP Client 的连接/读取超时。
- 401/其他 4xx、429/5xx、连接与读取超时、malformed JSON、空 choices 和缺失 usage 均有 WireMock 契约覆盖；错误只记录低敏类别，不回显 provider body、Key 或 Authorization。
- `AgentModelClient` 仍是 provider-neutral 边界；LangChain4j 类型没有进入 Agent 状态机、持久化实体或控制面。
- generation 与单次 JSON repair 分别持久化 provider、model、tokens、finish reason、latency、response SHA-256、prompt/schema version 与终态；provider 失败审计使用 `REQUIRES_NEW`，不会随外层异常回滚。
- 新增 `V14__agent_model_call_metadata.sql`，未修改 V1-V13。Task 3 的 Embedding metadata 迁移顺延为 V15。
- 模型 observation 仅使用 provider、model、purpose 三个有界标签。

Task 3 已完成：

- 新增 `LangChain4jEmbeddingClient`，使用 LangChain4j 1.8.0 `EmbeddingModel` 与 JDK HTTP Client；固定 provider/model/version，限制最大输入，关闭框架重试与请求/响应日志。
- WireMock 覆盖真实数值向量、空输入、超限、401、429、5xx、timeout、malformed response、维度不符以及 NaN/Infinity 拒绝；错误不回显 provider body 或密钥。
- `EmbeddingClient` 改为 provider-neutral 的 descriptor/result 契约；mock 与 legacy client 保持兼容，但 `prod + langchain4j` 已禁止 mock embedding，mock 仅保留给非生产测试和显式演示配置。
- `V15__embedding_model_metadata.sql` 为每个 chunk 增加 nullable provider/model/version/dimension，并将既有向量标为 `legacy-unknown`；未修改 V1-V14。
- memory 与 pgvector 搜索均强制 provider/model/version/dimension 兼容；不兼容或 legacy 数据返回明确 re-index-required 错误，不再静默计算无意义余弦分数。
- pgvector SQL 同时约束 project 与 embedding metadata；真实 WireMock provider 已覆盖上传索引→memory RAG 检索已知向量。
- 新增项目级 `/api/projects/{projectId}/knowledge/reindex`：逐文档 `REQUIRES_NEW`、跨项目隔离、当前版本幂等跳过、失败文档可在下一轮单独恢复。
- 文档删除继续先删除 pgvector 行，再删除 chunk metadata；测试覆盖两者。

Task 4 已完成：

- 新增 provider-neutral `ReviewRetrievalQuery`，强制 project ID、source version、正数 UTF-8 byte budget、阈值和有界 top-K。
- 新增 `LangChain4jReviewContentRetriever`，项目/document scope 只从 typed invocation parameters 获取，不解析不可信 Query 文本；伪造 `projectId=...` 或扩大 document scope 的文本无法改变实际检索范围。
- Adapter 继续委托现有 `ReviewContextService` 与 `RagService`，没有引入全局 `EmbeddingStoreContentRetriever`，因此 project/document 隔离、版本化向量门禁和既有 RAG 路径保持权威。
- changed paths、symbols、imports、annotations、strings 和 tool rule IDs 均完整进入确定性查询；hybrid 权重精确保持 vector `0.40`、lexical `0.25`、symbol `0.20`、document type `0.15`。
- threshold、规范化内容去重、稳定排序、top-K 和 UTF-8 byte budget 均在 domain service 执行。
- LangChain4j `Content` metadata 携带精确 citation、source name、chunk index、document type、source version、hybrid score 和 `untrusted` 标记，供后续 Finding/SCM 引用复用。

Task 5 已完成：

- 新增版本化 `review-v1` Prompt 模板与 `PromptTemplateRegistry`，未知版本拒绝，不允许通过路径输入加载任意资源。
- `AgentPromptAssembler` 将 trusted policy、task instruction、changed diff、code context、tool evidence、retrieved knowledge 和 output schema 放入独立标签区；代码、注释、diff、工具日志及 RAG 文档均明确标为不可信数据。
- 模板明确禁止不可信文本新增工具、扩大 project/document scope、请求秘密、关闭证据规则、批准 Patch 或授权 SCM 发布。
- diff/code/tool/RAG 分别执行独立 UTF-8 byte 与近似 token budget；按 Unicode code point 确定性截断，保留已纳入的 citation header，并记录 truncated sections。
- Authorization、token/password/secret/API key/environment assignment、GitHub token 与 private key 内容均在进入 Prompt 前脱敏。
- `StructuredModelResponse.CitedClaim` 与 `ModelOutputValidator` 要求 knowledge-backed claim 至少引用一个已供应 ID，并拒绝空、未知、重复或伪造 Citation。
- `PromptEnvelope` 计算稳定 SHA-256；`V16__agent_model_prompt_hash.sql` 只持久化 prompt version/hash，不持久化完整私有 Prompt。
- 新增代码注释与恶意 RAG 文档双重 Prompt Injection 评测夹具。

Task 6 已完成：

- `AgentStepHandler` 已由占位字符串实现替换为 typed registry dispatch，所有可执行状态均有且仅有一个 executor。
- executor 输入输出保持 provider-neutral、JSON 可序列化和版本化；checkpoint 限制为 8000 UTF-8 bytes，并持久化供恢复与幂等判断使用。
- `AgentStepExecutionService` 继续统一负责锁、attempt、取消检查、retry 分类、指标和终态失败；executor 不绕过状态机私自修改状态。
- 新增 retryable/permanent provider error 与 environment incomplete 分类，并覆盖取消、重复投递、非法模型输出和不同失败类型。
- 当前各状态 executor 仅写入 state-specific typed checkpoint，不自动推进状态；这是 Task 7-10 接入真实 planning、tool、RAG、Patch、审批和发布链路的安全基座，避免未实现业务空跑完整流程。

Task 7 已完成：

- `PlanningStepExecutor` 已接入生产 `StructuredAgentModelService`，生成 schema-valid、带 model request ID 的受控工具计划，并持久化版本化 planning checkpoint。
- `LangChainToolSchemaMapper` 将 provider-neutral `AgentTool` 输入 record 转为 LangChain4j `ToolSpecification`，禁止 command、shell 和 executable 字段；只暴露当前 Planning 状态允许且项目已授权的只读工具。
- `AgentToolLoop` 在任何外部调用前校验计划成员、工具名、JSON 对象、审批、参数字节、路径穿越、重复 request ID、调用次数和取消状态。
- model request ID 被纳入 `ToolInvocation` 幂等键；重复投递复用已持久化成功结果，不重复执行工具。
- 工具结果以 success、environment-incomplete、policy-rejected、execution-failed 或 canceled 显式状态返回，并沿用 Registry 的输入输出脱敏和限长。
- `ExecutingToolsStepExecutor` 将持久化工具结果重新送入模型，最终计划再次经过 schema、工具权限和 Citation 校验，形成模型→工具→模型闭环。
- `AgentModelBudgetPolicy` 按整个 conversation 累计模型调用、输入/输出 token、provider latency 和可配置单价估算成本；provider retry 仍为 0，超限统一为 `BUDGET_EXCEEDED`。
- 成功 step 使用 `AgentStepResult.ADVANCE`，由既有 `AgentStepPublisher`、状态机、事务 Outbox 和 MQ 调度下一状态，不允许 executor 私自修改 AgentRun。

Task 8 已完成：

- 新增 `V17__agent_analysis_context.sql`，按 Agent Run 单行持久化并以 head SHA 绑定 repository、ChangeSet、RAG、Finding 和 Gate 的版本化 checkpoint；V1-V16 未修改。
- `PreparingRepositoryStepExecutor` 从持久化 PR 读取权威 base/head SHA，只构造受限 `workspace://` archive ref，并通过 `AgentToolRegistry` 的固定 `git.diff` 调用 signed sandbox gateway。
- `AnalyzingChangeStepExecutor` 从有界 diff 构建 RepositoryProfile/ChangeSet，确定性选择 Java、Python、JavaScript/TypeScript 插件，并执行插件声明的固定 command ID。
- 新增只读 `language.command` AgentTool；请求只能携带 plugin command ID、固定参数和 sha256-pinned image，Rabbit gateway 继续签名 SandboxJob，不接受 executable 或 Shell 字符串。
- PMD/SARIF、SpotBugs、Checkstyle、Ruff、Bandit、ESLint、Semgrep 和 TypeScript 输出复用 Phase 4 normalizer，形成 STATIC_ANALYZER evidence；无效/缺失环境结果不会伪造成 Finding。
- `RetrievingContextStepExecutor` 使用 changed paths 和插件 rule IDs 构建 typed scope，通过 `LangChain4jReviewContentRetriever` 调用 ReviewContextService，并持久化 head-bound Citation。
- `AgentFindingModelService` 使用独立 Finding schema，要求每个模型候选引用已供应 Citation，拒绝未知、重复或伪造引用。
- `AgentFindingPipeline` 在后端执行证据去重、代码位置重现、独立 verifier、确定性 confidence 和 GateDecision；持久化 MODEL、STATIC_ANALYZER、KNOWLEDGE、CODE_LOCATION、VERIFIER evidence、决策与分项贡献。
- 模型单独声明、陈旧位置或跨 head-SHA 证据不能阻断 PR；clean/rejected Finding 直接进入 PUBLISHING_RESULT，只有 verified blocking Finding 才进入 GENERATING_PATCH。
- 当前主机仍未动态验证真实 workspace archive provisioning、RabbitMQ→Runner 和容器内三语言命令，不能宣称 Docker 安全验收通过。

Task 9 已完成：

- `GeneratingPatchStepExecutor` 只选择已 verified 且 Gate blocking 的 Finding；clean、rejected、nonblocking Finding 直接跳过 Patch 并进入 publication。
- Patch 模型只能返回严格 `{unifiedDiff}` schema；生成结果绑定 Agent Run、当前 head SHA、Finding IDs、model、prompt version，并由既有 `PatchCandidateService` 计算 patch hash 和执行 UnifiedDiffValidator。
- 既有路径穿越、绝对路径、二进制、rename、protected CI/CODEOWNERS/Flyway/.git、文件数和行数限制仍是唯一 Patch scope 门禁。
- `ValidatingPatchStepExecutor` 从插件声明的 pinned command 中分别选择 BUILD/TEST/SCAN，通过 `PatchValidationService` 运行 baseline、apply-check、apply、patched checks，并保留独立状态。
- Patch validation 请求可携带 plugin 的 sha256-pinned image；target finding fingerprint 必须消失，只有 `PatchCandidate.isApprovable()` 才能进入 WAITING_APPROVAL。
- 模型和 LangChain4j callback 没有审批、上传、提交、推送或合并能力；不合格 Patch 只发布安全摘要，不暴露 Patch 内容。
- 当前主机仍未动态验证 patch archive provisioning、真实容器 baseline/patched checks 和 Docker 安全策略，不能宣称最终 Patch 验收通过。

下一步严格执行 Task 10，接入 approval-aware SCM publication 和 idempotent recovery。

## 3. 最新验证证据

```text
backend: mvn test
结果: 274 tests, 0 failures, 0 errors, 3 skipped（修复 Webhook installation 测试夹具后复验）

frontend: npm test
结果: 4 passed

frontend: npm run build
结果: PASS

sandbox-runner: mvn test
结果: 37 tests, 0 failures, 0 errors, 0 skipped

git diff --check
结果: PASS（当前 Task）
```

后端跳过项仍为：

- `InfrastructureIntegrationTest`
- `LegacySchemaMigrationIntegrationTest`
- `GitHubWebhookAgentRunIntegrationTest`

本机没有 `docker` 命令，因此以下项目尚未验证，不能记录为通过：

- 已新增 `scripts/verify-langchain4j-agent.ps1` 作为统一验收入口；无 Docker 时输出 `DYNAMIC_ACCEPTANCE_INCOMPLETE` 并失败退出，不会误报放行。
- 2026-07-18 已实际执行该脚本：backend、frontend test/build、sandbox-runner、legacy/langchain4j development comparison input 与 `git diff --check` 全部通过，随后按设计在 Docker 门返回 `DYNAMIC_ACCEPTANCE_INCOMPLETE`。

- `docker compose config` 和镜像构建。
- RabbitMQ 到 Runner 的真实消息联调。
- Docker Socket、容器网络和宿主路径隔离。
- PostgreSQL/RabbitMQ Testcontainers 集成测试。

## 4. 安全边界与已知限制

- V1 至 V15 Flyway 迁移已冻结，不得修改；V16 已用于 prompt hash 审计，后续新增迁移从 V17 开始。
- 后端不得运行仓库控制的命令，也不得挂载 Docker Socket。
- Sandbox Runner 是受信任的单机演示编排组件；Compose 不是恶意多租户隔离边界。
- 分析容器不得继承 Docker Socket、SCM Token、LLM Key 或数据库凭据。
- 当前 nonce 重放保护是 Runner 进程内存级；持久化/分布式防重需在后续安全集成阶段补强。
- Runner 仍只执行固定注册的命令 ID；仓库读取路由已逐项注册，未注册命令和任意 Shell 输入都会被拒绝。

## 5. 下一步严格顺序

新增实施计划：

- docs/superpowers/plans/2026-07-17-pr-gatekeeper-phase-5-langchain4j-agent-rag.md

Task 1 前的源码核查确认 AgentStepHandler 当时仍是占位实现，StructuredAgentModelService 与 ReviewContextService 尚未进入生产 Agent 步骤链路。Task 6 已用 typed state executors 替换占位 handler；后续仍不能重写现有控制面，而应继续以 LangChain4j 作为模型、Embedding、Retriever 和受控 Tool Calling 适配层，将真实业务逐状态接入。

Phase 5 Task 1-10 已按 TDD 完成。Task 10 已接入 approval-aware SCM publication、持久化幂等键、发布前 head/授权/Patch hash 复核，以及 WAITING_APPROVAL 外部唤醒；提交为 `3312058 feat: publish completed langchain4j agent reviews`。V1-V16 均未修改，V17/V18 已使用，后续新增迁移从 V19 开始。Task 10 目标测试通过；完整门禁仍需重跑 backend、frontend 和 sandbox-runner 全量测试及 git diff --check。

Task 11 正在实施：`app.ai.rollout` 支持 `disabled`、`shadow`、`selected-projects` 和 `default`。shadow 模式允许运行同 corpus 的脱敏评测，但 PatchApprovalService 与 SCM publication 均拒绝写操作。回滚只需把 `app.ai.runtime` 改回 `legacy`（或把 rollout 改为 `disabled`）并重启服务；不得回滚 V14-V18，也不得删除 LangChain4j 已生成的调用日志、Citation、Finding、Patch 和发布审计记录。评测输入同时保存 LangChain4j、Prompt、Embedding 与 Retrieval 版本，并比较质量、延迟、成本以及四项零容忍安全率。

前端优化已直接落地（提交 `0358714`、`5bd41c1`、`0e7b4e9`、`89defda`、`83e5e86`、`5e3aa1b`、`830a281`、`78949f1`、`0c05fc2`、`35bff40`、`4722458`、`1557283`、`0e83b9d`、`9cb9b2e`）：Agent Run 项目列表、状态筛选与计数、自动刷新、取消/重试二次确认、Timeline 状态摘要、Finding citation 证据抽屉与定位、带行号 Patch Diff、运行状态本地化和契约测试。最新前端门禁为 5 tests passed、生产构建通过、git diff --check 通过。前端只读取后端权威状态，不执行模型、工具、Sandbox 或 SCM 发布。

Phase 1-4 计划代码 Task 已完成；Phase 5 Task 1-11 已完成，Task 12 的动态验收已执行可运行检查但 Docker/Testcontainers 仍待具备 Docker 的环境。最终发布仍必须在具备 Docker 的环境执行以下动态验收：

1. `docker compose config`、全部镜像构建与服务健康检查。
2. PostgreSQL/RabbitMQ Testcontainers 三个跳过测试、RabbitMQ→Runner、Patch apply/validate 和依赖缓存真实联调。
3. Docker Socket、网络、只读根文件系统、非 root、资源限制、宿主路径与凭据隔离动态安全验收。
4. Webhook→Agent→工具/模型→Sandbox→SCM 的真实 trace，以及 Prometheus 指标采集。
5. 真实 development/holdout corpus 执行与质量门；当前 baseline 仅为确定性混淆矩阵验证。
6. Phase 5 完成后再执行 LangChain4j Webhook→Agent→RAG/工具→Finding/Patch→SCM 的真实端到端验收。
7. 每个 Task 独立提交；Docker 动态验收未完成时不得宣称 Phase 3、Phase 4 或 Phase 5 最终放行。

## 6. 继续开发提示词

```text
请在 F:\202605New\.worktrees\pr-gatekeeper-agent 的 feat/pr-gatekeeper-agent 分支继续 PR 守门 Agent。

先读取 docs/PR守门Agent实施进度.md、Phase 3/4 计划、git status 和最近 20 个提交。
Phase 1、Phase 2 和 Phase 3 Task 1-11 已完成；Task 12 的代码、静态加固和运维文档已完成，但 Docker 动态验收待补跑。不要重复实现，不要修改冻结的 V1-V4。

Phase 1-4 的代码 Task 已实现。Phase 5 Task 1-6 已完成：固定 LangChain4j 1.8.0 与 runtime 边界，实现 Chat/Embedding adapter、调用审计、版本化向量、项目级 re-index、typed-scope Retriever、安全 Prompt/Citation 组装，以及 typed state executor 注册、版本化 checkpoint 和统一失败分类；V14-V16 已使用。下一步从 Phase 5 Task 7 开始，严格 TDD 实现 Planning 与 bounded LangChain4j tool loop；后续迁移从 V17 开始，不修改 V1-V16。当前主机无 Docker，不能宣称 Phase 3/4/5 最终发布验收通过。
```
