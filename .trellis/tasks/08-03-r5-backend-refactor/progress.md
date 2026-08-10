# Progress：后端分批重构

## 批A 零风险清理（2026-08-10，已完成，待提交）

证据清单：[batch-a-deadcode.md](./batch-a-deadcode.md)（普查方法、逐项证据与处置全量留档）。

### 改动摘要

**删除（52 行）**

- `agent/orchestration/steps/AbstractCheckpointAgentStepExecutor.java`（28 行）：无任何子类的孤儿抽象基类，全仓零引用。
- `agent/tool/git/UnavailableSandboxToolGateway.java`（17 行）：历史兜底实现，从未成为 Bean；沙箱通道已由 `RabbitSandboxToolGateway` 接通。
- `config/app-agent.yml` 的 `app.agent.budget.*` 块（7 行 6 键）：全仓零读点；实际预算约束走 `app.agent.model.*`（`AgentModelBudgetPolicy`），deploy 亦未设置对应环境变量。

**修正（不改行为）**

- 8 处 `docs/并行实施拆分方案.md` → `docs/archive/并行实施拆分方案.md` 路径引用（4 个 Java 文件 Javadoc + 4 个 yml 注释；冻结契约类仅动注释）。
- `.github/workflows/ci.yml`：`actions/setup-java@v4` → `@v5`（消除弃用告警，三输入原样，YAML 解析校验通过）。

**核查后零处置（写实）**

- 方法级：全部 `*Service.java` 普查后无死方法（2 个初筛候选均为注解假阳性）。
- 依赖级：`dependency:analyze` 咬合的 11 项 unused-declared 全为 starter POM / 自动配置 / ServiceLoader 假阳性，逐项 grep 佐证后零移除。
- `.worktrees/pr-gatekeeper-agent`：已不存在（`git worktree list` 仅主工作树），记录在案。
- scripts/ 九个脚本引用路径与 compose 服务名逐一核对有效，无失效脚本。
- docs/ 存在性扫描无死链（两处"缺失"命中均为 demo 仓库内部相对路径示例）。

### 验证

- 容器化 `mvn -s .mvn/settings.xml -B clean verify`：**BUILD SUCCESS**
  `Tests run: 575, Failures: 0, Errors: 0, Skipped: 3`
- 注：不带 clean 的首跑测试同为 575 全绿，但 JaCoCo report 阶段因仓内遗留 `backend/target/` 的
  `jacoco.exec` 多次构建追加写损坏报 "Unknown block type c0"（与本批改动无关），`clean` 后消除。
- 契约零改动复核：diff 仅含上述删除/注释/CI 项；REST 路径、DTO 字段、Flyway 迁移、
  ErrorCode/PageResponse/ProjectAuthorization 签名、MQ 载荷零触碰。

## 批B 超长类/方法拆分（2026-08-10，已完成，待提交）

普查口径、逐项处置(拆分依据/保留理由)与前后对照全量留档:
[batch-b-structure.md](./batch-b-structure.md);普查脚本 `scripts/scan-structure.py`(可复跑)。

### 改动摘要

**类拆分(唯一 >500 行类)**

- `git/GitCliService`(512→328):进程执行管道逐字搬出为同包包级私有 `GitCommandRunner`
  (221 行,非 Spring Bean):命令组装/safe.directory、askpass 凭据注入、超时、限量抽干
  (OutputDrain)、失败输出脱敏(sanitize)。GitCliService 保留全部公共 API 与 git 语义,
  对运行器只经一行私有委托——调用方不再知道 askpass/抽干/脱敏细节(深模块缝)。

**方法拆分(9 个 >60 体行方法中 6 拆 3 留)**

- `ExecutingToolsStepExecutor.execute` 138→48:`runPlannedTools` / `finalizeReceipt` / `receiptPrompt`。
- `PlanningStepExecutor.execute` 95→31:`planningPrompt` / `persistPlanAndAdvance`。
- `ReviewPlanValidator.validate` 83→57:`intrinsicIssues`(errorsBefore 计数技巧显式化,
  与代码注释既有的"内在/预算两阶段"概念对齐)。
- `AgentPublicationService.publish` 78→55:`deliverToRemote`;@Transactional 留在 publish
  入口,无自调用失效面(批内唯一事务方法,已核查)。
- `StructuredAgentModelService.generateInternal` 72→46:28 行内联修复 lambda 具名化为
  `attemptRepair`。
- `PreparingRepositoryStepExecutor.execute` 72→50:`readBackPreparedDiff`(F-04 取证读回)。
- **保留(写实,数字不动)**:ValidatingPatchStepExecutor.execute(80,线性降级管道,外拆
  强制 Either 式返回协议)、MockAiReviewClient.review(73,数据重逻辑轻的 mock 规则表)、
  GitHubWebhookController.receive(63,安全时序即契约)。理由逐条见留档。

**特征测试与命名**

- 新增 `characterization/GitCredentialFlowCharacterizationTest`:askpass 凭据路径此前零覆盖
  (离线不可达 http 远端),重构前对未改动代码先跑绿再动手;存量测试仅 2 处 sanitize 静态
  引用同包改指新类,断言零改动。
- 命名对齐:逐项核对 r4 规范,无包级私有名被规范点名为错,改名 0(不猎取)。

### 验证

- 容器化 `mvn -s .mvn/settings.xml -B clean verify`:**BUILD SUCCESS**
  `Tests run: 576, Failures: 0, Errors: 0, Skipped: 3`(批A 基线 575 全保留 + 1 新特征测试)。
- 契约零改动复核:resources(迁移/yml)、common/(冻结契约)、pom、REST 注解与 DTO record
  行零变更;公共类名/包/签名全部不动,抽出协作对象均为包级私有或 private。

## 批C 模块边界(Stage 1:普查 + 处置表 + 纯移动;Stage 2:反转/收敛。2026-08-10,两阶段均已完成,待提交)

普查口径、原始边表、环报告、逐项处置(移动/反转/保留+理由)全量留档:
[batch-c-boundaries.md](./batch-c-boundaries.md);普查脚本 `scripts/scan-package-deps.py`(可复跑)。

### 依赖图(写实数字)

- 移动前:25 顶层包、90 条跨包边、361 个 import;Tarjan 检出 **1 个 20 包强连通分量**
  (环外仅 sandbox/report/notify/webhook/evaluation)。
- r4 规范明文规则("common/ 不得反向依赖领域包")的违规:common → {ai:1, auth:6, project:2},
  共 9 import / 4 类。其余环边为域间双向缝与 project 鉴权/清理辐辏,无明文规则约束。

### 处置(三选一计数:移动归位 2 / 接口反转 2→Stage 2 / 合理保留 14)

**纯移动 2 项(已执行,git 识别 rename 93%/91%,diff 内零逻辑改动)**

- `ai/AiCallTransientException` → `common/exception/`:BusinessException 的重试分类学孪生,
  ai 产、common 全局处理、agent 分类消费;连带 `application.yml` resilience4j 两处 FQN 字符串
  同步更新(retry-exceptions/record-exceptions,漏改即重试熔断静默失配)+ 9 处 Java import 校正。
- `common/security/TokenAuthenticationFilter` → `auth/`:三个协作者全在 auth,规范清单未把它
  钉在 common;无 yml/反射 FQN 引用(全仓 grep 佐证);`SecurityConfig` import 随迁。

**效果**:common 的领域依赖 9 import/4 类 → **3 import/2 类**,仅剩者均具名有主——
`SecurityAuditFilter`→auth(I1,Stage 2 反转)与 `ProjectAuthorization`→project(冻结契约例外,留档)。
SCC 成员不变(余环即 Stage 2 工单 + 14 项留档保留缝,物理消环需破坏冻结契约,不做)。

**Stage 2 工单(另批)**:I1 cookie 名下沉属性源;I2 agent 检索端口反转(照 AgentModelClient 范式);
重复收敛 1 项达 ≥3 线(AI 瞬时失败分类 ×3:两个 langchain4j `classify` 近同 + OpenAiCompatibleReviewClient
catch 阶梯);明确不抽象 2 项(两处线,留案)。

### 验证(Stage 1)

- 容器化 `mvn -s .mvn/settings.xml -B clean verify`:**BUILD SUCCESS**
  `Tests run: 576, Failures: 0, Errors: 0, Skipped: 3`(与批B 基线逐数相同);
  Spring 上下文在测试内完整拉起 = 组件扫描冒烟(两个被移类均仍在 `com.example.codereview` 扫描根下)。
- 契约零改动复核:迁移/REST 路径/DTO 字段/MQ 载荷零触碰;冻结四类
  (ErrorCode/PageResponse/ApiResponse/ProjectAuthorization)零触碰;
  `application.yml` 仅 resilience4j 两行 FQN 随迁,`app-agent.yml`/`app-boundary.yml` 零改动。

### Stage 2:反转 + 重复收敛(2026-08-10,已完成,待提交)

逐项过程、行为保全证据与终态分层图留档:[batch-c-boundaries.md](./batch-c-boundaries.md) §7–§10。

**I1(common→auth 1→0)**:`SecurityAuditFilter` 摘除 `AuthCookieService` 注入,cookie 名改
`@Value` 直读共享属性源 `app.security.auth-cookie-name`(唯一定义在 app-boundary.yml:27,
双方同表达式同默认值 `reposage_auth`);测试 9 例断言零改动。

**I2(agent→ai 1→0)**:新端口 `agent/orchestration/AgentContextRetriever`(域类型签名,照
AgentModelClient「端口在消费域」范式);适配器 = 既有 `LangChain4jReviewContentRetriever`
增实现端口,`toQuery` 组装与 Content→Evidence 映射逐字收进适配器(公共 `toQuery`/
`retrieve(Query)` 签名原样);执行器甩掉全部 ai 与 langchain4j import;注册维持无条件
`@Component`(移进条件配置类会让 bean 随 runtime 条件消失,属行为变更,写实留档)。

**重复收敛(≥3 线)**:AI 瞬时失败分类 3 处 → `ai/AiTransientFailureClassifier` 单类双入口
(langchain4j 族沿因果链遍历、RestClient 族顶层类型分发——同质化会改包装路径行为,不做);
参数化保留的既有行为差:消息前缀、永久码 6004/6003、兜底命名(最深层 vs 顶层,枚举写实);
**传统 int 构造保留**(6004/6003 落 HTTP 400,换 ErrorCode 枚举会静默变 503,error-handling.md
禁止顺手规整);429 字面判定在 4xx 分支保留。resilience4j 匹配的 `AiCallTransientException`
FQN 零触碰。**特征测试先行**:catch 阶梯此前零覆盖,新增
`characterization/AiReviewFailureClassificationCharacterizationTest`(6 例)改前跑绿再动;
首跑纠正预设——提取阶段读超时现状走**永久**分支,原样保留。明确不抽象 2 项复核后维持
(cookie 嗅探两循环语义不同:取值 vs 判存在)。

**终态依赖(复跑 scan-package-deps.py)**:88 边/363 import(Stage 1 后 90/360;消失的 2 条
恰是两张工单边);common 领域依赖 → **2 import/1 类**(仅 R1 冻结例外);SCC 仍 20 包但
每条余边均有留档理由(工单项清零);5 层逻辑分层图 + 8 束具名上行回边(25 import)+
层内环团逐项有主,层间图无环——AC「可画出无环图」以此口径达成,留档 §9。

### 验证(Stage 2)

- 容器化 `mvn -s .mvn/settings.xml -B clean verify`:**BUILD SUCCESS**
  `Tests run: 583, Failures: 0, Errors: 0, Skipped: 3`(Stage 1 基线 576 全保留 + 7 新增)。
- 契约零改动复核:`backend/src/main/resources` 与 `pom.xml` diff 为空(迁移/yml/REST 无涉);
  冻结面(common/api、ProjectAuthorization、common/exception)零触碰;公共 API 变更仅
  新端口接口一处(任务允许项);@Transactional 自调用核查:被迁移代码均不含、不触及事务方法。
