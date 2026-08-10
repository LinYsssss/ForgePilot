# 批C 模块边界:依赖普查、处置表、纯移动(Stage 1)与反转/收敛(Stage 2)

> 普查脚本:`scripts/scan-package-deps.py`(可复跑)。扫描口径:`backend/src/main/java` 全部
> 347 个类的 `import com.example.codereview.*` 语句,按 `com.example.codereview` 下第一级包聚合成
> 有向边;同顶层包内引用不计;额外扫描"未经 import 的内联 FQN 使用"防漏(结果见 §1.3)。
> 边界裁量以 r4 规范 `.trellis/spec/backend/directory-structure.md` 与 `frozen-contracts.md` 为准。

---

## 1. 现状依赖图(移动前基线,2026-08-10)

### 1.1 原始边表(src 顶层包 → dst 顶层包 : import 数)

25 个顶层包之间共 90 条有向边、361 个跨包 import。

```text
agent -> ai : 2          agent -> common : 12     agent -> config : 3
agent -> context : 3     agent -> finding : 30    agent -> git : 1
agent -> language : 11   agent -> patch : 16      agent -> project : 1
agent -> pullrequest : 2 agent -> repo : 2        agent -> report : 2
agent -> sandbox : 5     agent -> scm : 11
ai -> agent : 3          ai -> common : 10        ai -> context : 2
ai -> project : 1        ai -> rag : 1            ai -> review : 2
auth -> common : 8
common -> ai : 1         common -> auth : 6       common -> project : 2
config -> common : 5
context -> knowledge : 2 context -> rag : 1
evaluation -> common : 1
feedback -> auth : 2     feedback -> common : 4   feedback -> project : 1
feedback -> report : 4
finding -> agent : 2     finding -> common : 4    finding -> project : 1
git -> common : 6        git -> repo : 5
knowledge -> ai : 1      knowledge -> common : 7  knowledge -> project : 1
knowledge -> rag : 4
language -> finding : 14
model -> ai : 1
mq -> common : 7         mq -> config : 3         mq -> review : 3
patch -> agent : 8       patch -> common : 4      patch -> finding : 2
patch -> project : 1
project -> ai : 1        project -> common : 4    project -> feedback : 1
project -> git : 1       project -> knowledge : 2 project -> mq : 1
project -> pullrequest : 2                        project -> rag : 1
project -> repo : 2      project -> report : 4    project -> review : 2
pullrequest -> common : 4                         pullrequest -> git : 1
pullrequest -> project : 1                        pullrequest -> repo : 2
pullrequest -> report : 4                         pullrequest -> review : 3
rag -> ai : 1            rag -> common : 3        rag -> knowledge : 6
repo -> common : 5       repo -> git : 2          repo -> project : 1
review -> ai : 7         review -> common : 11    review -> feedback : 1
review -> model : 2      review -> mq : 3         review -> notify : 2
review -> project : 2    review -> pullrequest : 2                review -> rag : 1
review -> repo : 4       review -> report : 10
scm -> agent : 7         scm -> common : 20       scm -> git : 1
scm -> project : 1       scm -> repo : 1          scm -> webhook : 1
```

### 1.2 环报告(Tarjan SCC)

- **1 个强连通分量,含 20 个包**:agent, ai, auth, common, config, context, feedback, finding,
  git, knowledge, language, model, mq, patch, project, pullrequest, rag, repo, review, scm。
- **环外(纯叶子/纯上游)5 个包**:sandbox、report、notify、webhook、evaluation。
- 大 SCC 的成因不是一团乱麻,而是三类回边把本来分层的图缝成一片(处置见 §2):
  1. `common -> {ai, auth, project}`——**唯一违反 r4 规范明文规则**
     ("common/ 不得反向依赖领域包")的边,9 个 import、4 个类;
  2. 各域 → `project.ProjectService`(对象级鉴权)与 `project` → 各域仓库
     (`ProjectCleanupService` 级联清理)构成的辐辏;
  3. 业务上真双向的域对:agent↔scm(触发进/发布出)、agent↔patch(补丁生命周期在
     Agent 闭环内)、mq↔review、git↔repo、knowledge↔rag 等。

### 1.3 内联 FQN 使用(未经 import,防漏扫描)

37 处命中,逐条核对后全部为以下三类,无隐藏边遗漏:
`common.api.PageResponse` 的惯用全限定调用(冻结契约,各列表端点)、
`config.RabbitMqConfig` 内 `new com.example.codereview.sandbox.SandboxJobSigner()`(组合根装配 Bean)、
`PatchApprovalService` → `agent.queue.AgentStepWakeupService` / `ai.langchain4j.LangChain4jRolloutPolicy`
(计入 §2 对应行处置)。

---

## 2. 反向依赖处置表(design.md 三选一:移动归位 / 接口反转 / 合理保留)

裁量线:r4 规范明文规则 > 冻结契约 > "新人能答出这个类该放哪",不追求教科书式纯净。

### 2.1 移动类归位(2 项,本阶段已执行,见 §3)

| # | 违规边 | 涉及类 | 处置与依据 |
|---|--------|--------|-----------|
| M1 | common → ai(1 import) | `common/exception/GlobalExceptionHandler` import `ai.AiCallTransientException` | **移动 `AiCallTransientException` → `common/exception/`**。该异常是重试分类学里 `BusinessException` 的孪生(其类头 Javadoc 即以两者对举:瞬时可重试 vs 确定性不重试),由 ai 产、common 全局处理、agent.queue 分类消费,是三方共享词汇;common 不得知道 ai,但人人可知道 common。ErrorCode 已冻结 `AI_CALL_FAILED/AI_CIRCUIT_OPEN`,common 层本就说"AI 调用"这门词汇。连带修复 agent → ai 的一条边(`AgentStepExecutionService`)。 |
| M2 | common → auth(5 imports) | `common/security/TokenAuthenticationFilter` import `auth.{TokenService, ParsedToken, UserAccount, UserAccountRepository, AuthCookieService}` | **移动 `TokenAuthenticationFilter` → `auth/`**。它的全部三个协作者都是 auth 类,职责就是"令牌→用户→认证上下文";r4 规范对 common/security 的清单只点名 CurrentUser/ProjectAuthorization(+密码学与审计工具),未把它钉在 common;非冻结类,无 yml/反射 FQN 引用。新人问"令牌认证过滤器放哪"——auth。副作用:新增 config → auth 1 条(SecurityConfig 装配),组合根引域属正常(见 R2)。 |

### 2.2 引入接口反转(2 项,**Stage 2 已执行**,结果见 §7)

| # | 违规边 | 涉及类 | Stage 2 方案 |
|---|--------|--------|--------------|
| I1 | common → auth(1 import,M2 后仅剩这条) | `common/web/SecurityAuditFilter` import `auth.AuthCookieService`——只用 `getCookieName()` 一个字符串,判断"出示过凭据的 401 才记审计" | SecurityAuditFilter 被 r4 规范清单钉在 common/web(它审计所有域,搬走反而错)。反转:cookie 名下沉为共享配置属性(`@Value`)或 common 持有的提供者,auth 与 common 各自读同一属性源。改动极小但属代码修改,归 Stage 2。 |
| I2 | agent → ai(1 import,M1 后仅剩这条) | `agent/orchestration/steps/RetrievingContextStepExecutor` 持字段并静态调用 `ai.langchain4j.LangChain4jReviewContentRetriever.toQuery(...)`——跨域直引具体实现 | 仓内已有现成范式:`ai.langchain4j.LangChain4jAgentModelClient` implements `agent.model.AgentModelClient`(端口在消费域、适配器在 ai)。同构反转:agent 侧定义检索端口,langchain4j 侧实现,`toQuery` 收进适配器。归 Stage 2。 |

### 2.3 合理保留(14 项,写明理由)

| # | 边/环 | 理由(写实) |
|---|-------|-------------|
| R1 | common → project(2 imports):`ProjectAuthorization` → `ProjectEntity/ProjectRepository` | **冻结契约**:frozen-contracts.md §4 钉死文件路径 `common/security/ProjectAuthorization.java` 与方法面;它就是"common 向全体域提供项目级鉴权"的缝,天然要读 project 数据。反转(common 定端口、project 实现)= 为教科书纯净翻搅冻结类,零清晰度收益。这是 M2 后 common 仅存的领域依赖之二(共 3 import:R1 两条 + I1 一条),作为已知例外留档。 |
| R2 | config → {common:4, auth:1, sandbox:1(内联 FQN)} | config 是组合根(SecurityConfig 装过滤链、RabbitMqConfig 声明队列与 SandboxJobSigner Bean),装配即其职责,规范无 config 禁引域之规。 |
| R3 | agent → config(3)、mq → config(3):引 `RabbitMqConfig` 队列名常量 | frozen-contracts.md §7 明文"队列名常量在 config/RabbitMqConfig.java",常量的家被契约钉死,消费方只能来引。 |
| R4 | mq ↔ review(3/3) | mq/ 现实是"MQ 任务日志域(MqLog 五件套)+ 旧审查线队列端点(ReviewTask{Consumer,Publisher,Message})"。与 agent 自持 `agent/queue` 存在不对称;但把三件套搬进 review/ 并不能消环(MqLogQueryService 仍要 join ReviewTask 做项目域鉴权、ReviewService 仍写 MqTaskLog),只是在冻结相邻的 MQ 载荷代码上白翻搅。r4 规范树本就把 mq/ 列为常设包。新人可答:"审查队列端点在 mq/,Agent 队列端点在 agent/queue/"。 |
| R5 | ai → agent(3):LangChain4j 客户端 implements `agent.model.AgentModelClient`、用 `PromptEnvelope` | 端口在消费域、适配器在 ai——方向正确的依赖反转既成事实,是 I2 要效仿的范式而非要消除的债。 |
| R6 | agent ↔ patch(16/8,含 `PatchApprovalService` 内联 FQN 引 `AgentStepWakeupService`/`LangChain4jRolloutPolicy`) | 补丁生命周期本就在 Agent 闭环内(步骤产/验补丁,审批唤醒等待中的 run);`PatchValidationService` 走的是 `agent.tool.git.SandboxToolGateway` **接口**而非实现。一个限界上下文按体量拆两包,缝上是接口与 record,保留。 |
| R7 | agent ↔ scm(11/7) | 真双向:webhook 触发进(`scm.WebhookAgentRunService` 建 run——r4 规范树的注释就把"Agent Run 触发"划给 scm/webhook),发布出走 scm 持有的缝(`ScmReviewPublisher` 接口 + 结果 record)。两向都过定义好的缝,保留。 |
| R8 | agent ↔ finding(30/2) | 30 条正向是"Agent 产出发现"的本意;2 条反向是 `finding.AgentFindingQueryService` 查询侧 join `agent.run` 做 REST 域权与过滤,为此设端口纯属仪式。 |
| R9 | ai ↔ review(2/7) | 7 条正向是审查线用 AI 客户端(本意);2 条反向是 `AiCallLogService` 按 ReviewTask 归属做日志查询鉴权,查询侧 join,保留。 |
| R10 | ai ↔ rag(1/1) | `rag.EmbeddingClient` 端口由 `ai.langchain4j` 实现(方向正确);`rag.RagService` → `ai.AiCallLogService` 是调用留痕。保留。 |
| R11 | knowledge ↔ rag(4/6) | `KnowledgeChunk` 实体归 knowledge(knowledge_chunk 表其所有);rag 对 chunk 做检索并回 `KnowledgeDtos.SearchMatch`(REST DTO 按规范钉在聚合 Dtos 类内)。实体与 DTO 所有权都摆对了,环是检索本身横跨两域的实相。 |
| R12 | git ↔ repo(5/2) | `GitCliService` 公共签名收 `CodeRepositoryEntity`、吐 `RepositoryDtos` 线格式(冻结 REST 形状)——改签名违反本批"公共签名不变"硬约束;深模块的代价已在批B 留档,保留。 |
| R13 | project 辐辏(project→9 个域的仓库 + 8 个域→`ProjectService`) | 出边全部集中在 `ProjectCleanupService`:项目删除必须级联清到每个从属聚合,这是所有权的表达;入边是 frozen-contracts.md §4 点名的鉴权缝(`ProjectService.getRequired` 为 ProjectAuthorization 的实体版)。辐辏即枢纽地位的写照,保留。 |
| R14 | pullrequest ↔ review(3/2) | PR 控制器上"对 PR 发起审查"端点是冻结 REST 路径,review 读 PR 仓库做展示,双向皆本意。保留。 |

其余单向边(context→knowledge/rag、language→finding、model→ai、review→{model,mq,notify,…}、
scm→webhook、evaluation→common、agent→sandbox 等)逐条过目,方向无悖,不列行。
另核两处"看着可疑"的摆位,结论不动:`webhook/` 单类包(`WebhookSignatures`,仅 scm.github 使用)
——r4 规范树明文把"webhook 验签"划给 webhook/;`common/PinnedImageDigests`(common 根)——被
config 与 evaluation 两域共用、ADR-0001 点名的单一事实源,common 现有四个子包无一贴合,不为它发明新子包。

**处置计数:移动归位 2 / 接口反转(Stage 2)2 / 合理保留 14。**

---

## 3. 已执行纯移动(Stage 1,本批)

每项 = `git mv` + 包声明 + 全部 import 校正,diff 内零逻辑改动、零改名、零签名变更。

| 移动 | 旧 → 新 | 连带一致性修正(逐处列明) |
|------|---------|---------------------------|
| M1 `AiCallTransientException` | `ai/AiCallTransientException.java` → `common/exception/AiCallTransientException.java` | import 改指新包 7 处:main 3(`agent/queue/AgentStepExecutionService`、`ai/langchain4j/LangChain4jAgentModelClient`、`ai/langchain4j/LangChain4jEmbeddingClient`)+ test 4(`StructuredAgentModelServiceTest`、`AgentStepExecutionServiceTest`、`LangChain4jAgentModelClientTest`、`LangChain4jEmbeddingClientTest`);`GlobalExceptionHandler` 删 import(同包化);`ai/OpenAiCompatibleReviewClient` 增 import(原同包免 import)。**FQN 字符串 2 处**:`application.yml` resilience4j `retry-exceptions`(:58)与 `record-exceptions`(:69)——此二串是重试/熔断匹配的生死线,漏改即静默失配,已同步更新并由全量测试兜底。 |
| M2 `TokenAuthenticationFilter` | `common/security/TokenAuthenticationFilter.java` → `auth/TokenAuthenticationFilter.java` | `config/SecurityConfig` import 改指 `auth.`;类内 auth 五 import 同包化删除,增 `common.security.{CurrentUser, SecurityAuditLogger}` 两 import(原同包免 import)。无 yml/properties/反射 FQN 引用(全仓 grep 佐证,含 sandbox-runner/deploy/docs/frontend/.github)。`docs/archive/` 两份历史文档按简单名提及它——归档记述的是 Phase-0 当时的事实,不改(改了反而伪造历史)。 |

**移动为零的候选也写实**:除上述 2 项外,分析未支持更多移动(mq 三件套之议在 R4 否决,
webhook/、PinnedImageDigests 核后不动)。规范树与现实的其余差异为零——现有 25 包
布局即 r4 规范所录布局。

---

## 4. 移动后状态(复跑 `scan-package-deps.py`)

- 90 条边、360 个跨包 import(-1:GlobalExceptionHandler 的 import 同包化消失,其余重分布到合法方向)。
- 边变化全景:`common→ai 1→0`、`common→auth 6→1`(余 I1)、`agent→ai 2→1`(余 I2)、
  `config→common 5→4` + `config→auth 0→1`(M2 装配随迁)、`agent→common 12→13`、
  `ai→common 10→13`、`auth→common 8→10`(被移类的合法 import 计入新家)。
- **common 的领域依赖:9 import/4 类 → 3 import/2 类**,且仅剩者均具名有主:
  I1(Stage 2 反转)+ R1(冻结契约例外,留档)。
- SCC 仍为 20 包(成员不变):余环由 I1/I2(Stage 2)与 §2.3 的 14 项合理保留缝构成。
  **写实**:PRD 的"无环图"以处置表口径达成——图上每条回边要么有 Stage 2 工单、要么有
  留档理由,而非物理无环;物理消环需破坏冻结契约(R1/R3)与查询侧 join 缝(R8/R9),不做。

## 5. 验证(Stage 1)

- 容器化 `mvn -s .mvn/settings.xml -B test-compile`:通过(移动后快检)。
- 容器化 `mvn -s .mvn/settings.xml -B clean verify`:见 progress.md 批C 段(Tests run 行以终跑为准;
  Spring 上下文在测试内完整拉起,即组件扫描冒烟——两个被移类均仍在 `com.example.codereview` 扫描根下)。
- 契约复核:REST 路径/DTO 字段/Flyway 迁移零改动;冻结四类(ErrorCode/PageResponse/ApiResponse/
  ProjectAuthorization)零触碰;MQ 载荷零触碰;`application.yml` 仅 resilience4j 两行 FQN 随迁。

## 6. Stage 2 工单(反转/收敛,已全部执行,过程与证据见 §7–§10)

1. **I1 反转**:SecurityAuditFilter 摘除 `AuthCookieService` 依赖(cookie 名下沉共享属性源);完成后 common 的领域依赖仅剩 R1 冻结例外。✅ 见 §7.1
2. **I2 反转**:agent 侧检索端口 + langchain4j 适配器实现(照 `AgentModelClient` 范式,`toQuery` 收进适配器)。✅ 见 §7.2
3. **重复收敛(达 ≥3 处准入线)**:AI 瞬时失败分类逻辑 3 处——`LangChain4jAgentModelClient.classify` 与 `LangChain4jEmbeddingClient.classify` 文本级近同(因果链遍历:RetriableException/HttpTimeout/SocketTimeout/ConnectException + HTTP 429/5xx 判定),`OpenAiCompatibleReviewClient` 的 catch 阶梯是同一决策表在 Spring RestClient 异常族上的第三份实现(429→瞬时、5xx→瞬时、ResourceAccess→瞬时、余者永久)。候选:ai 包内包级私有分类器,消息前缀作参数。✅ 见 §7.3(包级私有不可行的原因也在该节写实)
4. **明确不抽象(两处重复,写实留案)**:cookie 凭据嗅探循环(`TokenAuthenticationFilter.resolveToken` vs `SecurityAuditFilter.hasCredential`,I1 落地后复核:两个循环各自 6 行、语义不同——前者要取值后者只判存在,合一需引入回调或返回 Optional 的共享 helper,抽象成本高于重复成本,维持不抽象)、GitHub/GitLab webhook 验签-入队形状(2 处,各自安全时序即契约)。✅ 零代码改动

---

## 7. Stage 2 执行结果(2026-08-10)

### 7.1 I1:SecurityAuditFilter 摘除 common→auth

- `common/web/SecurityAuditFilter` 不再注入 `AuthCookieService`,改为
  `@Value("${app.security.auth-cookie-name:reposage_auth}")` 直读共享属性源。
- **两处读数永远一致的依据**:该属性唯一定义在 `config/app-boundary.yml:27`
  (`${AUTH_COOKIE_NAME:reposage_auth}`),`AuthCookieService` 用同一表达式同一默认值;
  过滤器本就只消费 `getCookieName()` 这一个字符串,反转后语义零变。
- 行为锁:`SecurityAuditFilterTest` 9 例断言零改动(仅构造函数换参,常量仍 `reposage_auth`),
  测试类同时甩掉了对 auth 的 import。
- 效果:`common → auth` 1→**0**;common 的领域依赖仅剩 R1(见 §8)。

### 7.2 I2:agent 检索端口反转(照 AgentModelClient 范式)

- 新端口 `agent/orchestration/AgentContextRetriever`(public,消费域持有;放 orchestration
  是因为它回的 `AgentRetrievedContextCheckpoint.Evidence` 词汇就住在这里,与
  `agent/model/AgentModelClient` 的"端口住在能力子包"同构):
  `List<Evidence> retrieve(ReviewRetrievalQuery scope)`——签名只有域类型,零框架泄漏。
- 适配器 = 既有 `ai/langchain4j/LangChain4jReviewContentRetriever` 增实现该端口;
  框架 `Query` 组装(`toQuery`,公共静态方法**原样保留**,既有测试与签名不动)与
  Content→Evidence 映射(自 `RetrievingContextStepExecutor` **逐字迁入**,diff 可核)
  都收进适配器;端口方法内部调用链 = 执行器原调用链(`retrieve(toQuery(scope))` + 同一 map)。
- 执行器不再 import `ai.*` 与 `dev.langchain4j.*`(连 `ContentMetadata` 也一并甩掉)。
- **注册机制写实偏差**:`AgentModelClient` 适配器走 `LangChain4jModelConfiguration` 的
  条件 `@Bean`(它需要装配 provider 条件配置);检索适配器无条件面,维持既有 `@Component`
  注册——若移进该条件配置类,bean 会在 `app.ai.runtime != langchain4j` 下消失,
  执行器构造注入失败,属行为变更,不做。
- 行为锁与覆盖保全:执行器测试改 mock 端口并**新增** scope 捕获断言(projectId/sourceVersion/
  changedPaths);适配器测试新增端口路径逐字段映射锁定用例——原先经执行器测试间接覆盖的
  Content→Evidence 映射不失覆盖。
- @Transactional 自调用核查:被迁移的映射代码与端口方法均无事务注解、不触及事务方法(全文件 grep 佐证)。
- 效果:`agent → ai` 1→**0**;`ai → agent` 3→5(端口/Evidence 引用,方向正确,即 R5 范式);
  `agent → context` 3→4(端口签名引 `ReviewRetrievalQuery`,合法方向)。

### 7.3 重复收敛:AI 瞬时失败分类 3 处 → `ai/AiTransientFailureClassifier`

一张决策表(429/5xx/超时/连接层→瞬时,余者→永久)收敛为一个类、两个入口——两个入口对应
两个异常族**各自的分发语义**,同质化会改行为,不做:

- **causalChain 实例入口**(langchain4j 双胞胎):两个 `classify` 逐字收敛,按调用点参数化
  (消息前缀 `LangChain4j provider`/`Embedding provider`、永久错误码 6004/6003、兜底命名)。
  **diff 双胞胎时发现并写实保留的行为差**:因果链走完无一命中时,"returned an invalid response"
  消息点名的异常层不同——模型客户端点名链上**最深层**、Embedding 点名**顶层抛出**异常;
  以 `UnrecognizedNaming.{DEEPEST_CAUSE,THROWN_FAILURE}` 枚举参数保留,不统一。
- **classifyRestClientFailure 静态入口**(OpenAiCompatibleReviewClient):保留原 catch 阶梯的
  **顶层类型分发**(不沿因果链遍历——遍历会让"包着 429 的未知异常"从永久变瞬时,反向同理);
  逐条消息逐字节一致;4xx 面上保留**字面 429** 判定(不复用 `429||5xx` 共享行,手工构造 5xx
  `HttpClientErrorException` 的假想路径维持既有永久走向);`describeFailure`(链扁平化 +
  「读超时,请增大 AI_READ_TIMEOUT_MS」运维提示)留在客户端——它是站点专属文案,不进共享类。
- **传统 int 构造保留(重要)**:`BusinessException(6004/6003, msg)` 经 `resolveHttpStatus`
  落 HTTP **400**;若"顺手"换成 `ErrorCode` 枚举构造会静默变 **503**——error-handling.md
  明文禁止在重构里规整 legacy 码与状态的对应,分类器类注释留档。
- **可见性写实**:三调用点横跨 `ai` 与 `ai.langchain4j` 两个 Java 包,类无法 package-private,
  取 ai 顶层包 public;`classifyRestClientFailure` 仅同包使用,收窄为包级私有方法。
  家选 ai 而非 common 的依据:分类逻辑的词汇是提供方异常分类学(langchain4j/Spring Web),
  r4 明文 common 不得反向依赖领域知识;common 只共享纯标记异常 `AiCallTransientException`
  (其 FQN 被 `application.yml` resilience4j retry/record-exceptions 钉死,本批零触碰零移动,
  重试/熔断语义不变)。
- **特征测试先行**(PRD 硬约束:无覆盖先补锁):catch 阶梯此前零直接覆盖,新增
  `characterization/AiReviewFailureClassificationCharacterizationTest`(6 例)**先对未改动
  代码跑绿**再动手。首跑纠正一处预设并写进测试:响应体提取阶段的读超时被 Spring 包为
  `RestClientException`(非 `ResourceAccessException`),现状落兜底**永久**分支(仅消息带
  「读超时」提示)——收敛原样保留该走向,未"顺手修正"为瞬时。双胞胎侧的既有 WireMock
  分类测试(8+5 例)断言零改动,即行为锁。

### 7.4 契约复核(Stage 2)

`git status` 全量:6 个 main 文件修改 + 3 个测试修改 + 3 个新文件,`backend/src/main/resources`
与 `pom.xml` **零改动**(迁移/yml/REST 无涉);冻结面(`common/api/*`、`ProjectAuthorization`、
`common/exception/*` 含 `AiCallTransientException`)diff 为空;公共 API 变更仅新端口接口一处
(任务允许项),`toQuery`/`retrieve(Query)` 等既有公共签名原样。

---

## 8. 终态依赖图(Stage 2 后复跑 `scan-package-deps.py`,349 类)

- **88 条边、363 个跨包 import**(§4 基线 90/360)。消失的 2 条边恰是两张工单:
  `common→auth`(I1)与 `agent→ai`(I2)。import 净 +3 的构成:+1 `agent→context`
  (端口签名)、+2 `ai→agent`(适配器引端口与 Evidence)、+2 `ai→common`(分类器引
  ErrorCode/BusinessException/AiCallTransientException,扣除 OpenAiCompatibleReviewClient
  甩掉的 1 条)、-2(工单两边)。
- **common 的领域依赖:3 import/2 类 → 2 import/1 类**——仅剩 `ProjectAuthorization` →
  `project.{ProjectEntity,ProjectRepository}`,即 R1 冻结契约例外,别无其他。
  r4 明文规则("common/ 不得反向依赖领域包")的违规清单至此**只剩具名冻结例外**。
- **SCC 写实**:仍 1 个 20 包强连通分量,成员与 §1.2 相同。余环全部由 §2.3 的 14 项留档
  保留缝构成——Stage 2 前"每条回边要么有工单、要么有留档理由"中的工单项已清零,
  现在**每条回边都有留档理由**。物理消环需破坏冻结契约(R1/R3)与查询侧 join 缝(R8/R9),
  维持不做的结论。

## 9. 逻辑分层图(AC「包依赖方向可画出无环图」的达成口径)

25 包按职责落 5 层;**层间图无环**(把每层缩为一点、下表具名回边剔除后,所有跨层边
严格由高层指向低层);层内环团逐项有主。逐边核对口径:88 条边全数过筛,无未具名回边。

```text
层4 审查与 Agent 执行域   review  ai  agent  patch  scm  pullrequest
层3 资源/知识/产物域      repo  knowledge  rag  context  language  finding  feedback  model  evaluation
层2 项目枢纽             project
层1 平台设施             auth  config  git  mq
层0 契约基座与叶子        common  |  report  notify  webhook  sandbox(无出边)
```

**具名上行回边(低层→高层,全部有主,共 8 束 25 import)**

| 回边 | import 数 | 依据 |
|------|-----------|------|
| common→project | 2 | R1 冻结契约(ProjectAuthorization 鉴权缝) |
| git→repo | 5 | R12(GitCliService 公共签名收实体,批B 留档的深模块代价) |
| mq→review | 3 | R4(MqLogQueryService 按 ReviewTask 做项目域鉴权 join) |
| project→{repo,knowledge,rag,feedback,ai,pullrequest,review} | 11 | R13 清理辐辏束(ProjectCleanupService 级联清到每个从属聚合) |
| finding→agent | 2 | R8(查询侧 join run 做域权过滤) |
| rag→ai | 1 | R10(RagService→AiCallLogService 调用留痕) |
| knowledge→ai | 1 | 同 R10 性质:AI 调用留痕缝(KnowledgeService→AiCallLogService) |
| model→ai | 1 | 同 R10 性质(HttpModelRiskClient→AiCallLogService) |

**层内环团(写实,不假装成对消除)**

- 层4 核:R5(ai→agent,端口方向正确的单向,I2 后 agent→ai 已消,不再成对)、
  R6(agent↔patch)、R7(agent↔scm)、R9(ai↔review)、R14(pullrequest↔review)
  加 agent→pullrequest(读 PR 上下文,单向)共同构成一个层内连通环团——这就是 20 包 SCC
  的核,缝上是接口与 record(R6/R7)或查询侧 join(R9/R14),逐项理由见 §2.3。
- 层3 内:knowledge↔rag(R11,检索横跨两域的实相);其余层3 边
  (context→{knowledge,rag}、language→finding)无环。
- 层1 内:mq→config→auth 线性无环(R2/R3)。

新人口径自测:"这个类该放哪"——契约/横切进 common,装配进 config,凭据进 auth,
项目所有权进 project,产物词汇进 finding,AI 提供方适配进 ai,Agent 闭环进 agent 及其
机制子包——25 包布局与 r4 规范树零差异(§3 结论在 Stage 2 后依然成立,本阶段零移动)。

## 10. 验证(Stage 2)

- 特征测试先行:`AiReviewFailureClassificationCharacterizationTest` 对未改动代码
  `Tests run: 6, Failures: 0`(改前基线),重构后原样不动继续绿。
- 改动面定向回归(6 个测试类):`Tests run: 33, Failures: 0, Errors: 0`。
- 容器化 `mvn -s .mvn/settings.xml -B clean verify`:**BUILD SUCCESS**
  `Tests run: 583, Failures: 0, Errors: 0, Skipped: 3`
  (Stage 1 基线 576 全保留 + 6 特征测试 + 1 适配器端口映射锁定用例 = 583);
  Spring 上下文在测试内完整拉起 = 组件扫描冒烟(新端口与分类器均在扫描根下,
  检索适配器仍为无条件 @Component)。
- 契约复核见 §7.4:resources/pom 零改动,冻结面 diff 为空,resilience4j 匹配的
  `AiCallTransientException` FQN 零触碰。
