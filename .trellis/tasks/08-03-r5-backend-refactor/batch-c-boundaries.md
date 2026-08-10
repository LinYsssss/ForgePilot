# 批C 模块边界:依赖普查、处置表与纯移动(Stage 1)

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

### 2.2 引入接口反转(2 项,**Stage 2 执行**,本阶段不动代码)

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

## 6. Stage 2 工单(反转/收敛,另批派发)

1. **I1 反转**:SecurityAuditFilter 摘除 `AuthCookieService` 依赖(cookie 名下沉共享属性源);完成后 common 的领域依赖仅剩 R1 冻结例外。
2. **I2 反转**:agent 侧检索端口 + langchain4j 适配器实现(照 `AgentModelClient` 范式,`toQuery` 收进适配器)。
3. **重复收敛(达 ≥3 处准入线)**:AI 瞬时失败分类逻辑 3 处——`LangChain4jAgentModelClient.classify` 与 `LangChain4jEmbeddingClient.classify` 文本级近同(因果链遍历:RetriableException/HttpTimeout/SocketTimeout/ConnectException + HTTP 429/5xx 判定),`OpenAiCompatibleReviewClient` 的 catch 阶梯是同一决策表在 Spring RestClient 异常族上的第三份实现(429→瞬时、5xx→瞬时、ResourceAccess→瞬时、余者永久)。候选:ai 包内包级私有分类器,消息前缀作参数。
4. **明确不抽象(两处重复,写实留案)**:cookie 凭据嗅探循环(`TokenAuthenticationFilter.resolveToken` vs `SecurityAuditFilter.hasCredential`,I1 落地后再看是否自然合一)、GitHub/GitLab webhook 验签-入队形状(2 处,各自安全时序即契约)。
