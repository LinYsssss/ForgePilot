# 批A 死代码普查与清理证据(2026-08-10)

> 方法:类级 = 对 `backend/src/main/java` 全部 348 个文件,统计其简单类名在 `backend/src`(main+test)
> 中除自身文件外的词边界引用数;零引用者逐个人工甄别(Spring 扫描装配/HTTP 路由/条件 Bean/监听器
> 均为假阳性)。方法级 = 对全部 `*Service.java` 提取 public/package 方法,统计 `name(` 调用点
> (排除 @Override/@RabbitListener/@EventListener/@Scheduled/@PostConstruct 等)。配置级 = 提取
> 五个 yml 的全部 `app.*` 叶子键(94 个),对照 Java 侧 `${...}` 占位符、`@ConditionalOnProperty`
> 与字符串字面量(仓内无 `@ConfigurationProperties`/`Binder`/`getProperty("app…")`,已单独验证),
> 并 grep deploy/ scripts/ docs/ .github/ 的环境变量名。依赖级 = 容器内
> `mvn dependency:analyze -DignoreNonCompile=true`。

## 1. 类级普查(348 文件,零外部引用 32 个,删除 2 个)

### 删除(证据充分)

| 项 | 类型 | 证据 | 处置 |
|---|---|---|---|
| `agent/orchestration/steps/AbstractCheckpointAgentStepExecutor.java`(28 行) | 抽象类 | 全仓(含 test、yml、脚本)零引用 → 无任何子类继承;package-private 且无注解,Spring 不可见;唯一出现处为 `target/` 构建产物。引入于 7d36851(feat: dispatch persisted agent steps by state),后续各 StepExecutor 均直接 implements `AgentStepExecutor`,该基类被绕过成为孤儿 | 删除 |
| `agent/tool/git/UnavailableSandboxToolGateway.java`(18 行) | package-private final 类 | 全仓零引用;无 `@Component`/无 `@Bean` 实例化点,从未成为 Spring Bean。引入于 a4a6f6f 作为"沙箱结果通道接通前的安全默认",现 `RabbitSandboxToolGateway`(`@Component @Primary`)即该通道,历史兜底失效。删除后 main 侧 `SandboxToolGateway` 实现恰余 Rabbit 一个,装配面无变化 | 删除 |

两者均无对应测试文件,无需联动删测试。

### 保留(零直接引用但活跃,按甄别原因分组)

| 项 | 保留理由 |
|---|---|
| `CodereviewApplication` | Spring Boot 入口(main + @SpringBootApplication) |
| 13 个 `*Controller`(Repository/ScmInstallation/PullRequest/AgentFinding/MqLog/AiCallLog/Feedback/PatchApproval/Knowledge/Project/Review/Auth/GitLabWebhook/GitHubWebhook) | `@RestController`,HTTP 路由装配,测试经 MockMvc 走路径不点名类 |
| `FindingDomainConfiguration` / `RestClientConfig` / `LanguagePluginConfiguration` | `@Configuration`,Spring 扫描 |
| `AiProviderHealthIndicator` / `ModelServiceHealthIndicator` | 健康指示器 Bean,actuator 按类型收集 |
| `HttpModelRiskClient` / `NoopModelRiskClient` | `@Service` + `@ConditionalOnProperty(app.model-service.enabled)` 互斥双实现,按接口注入 |
| `OpenAiCompatibleReviewClient` | `@Service` + `@ConditionalOnProperty(app.ai.provider=openai-compatible)`,部署态激活 |
| `MockEmbeddingClient` / `OpenAiCompatibleEmbeddingClient` | `@Service` + `@ConditionalOnProperty(app.ai.embedding-provider)` 条件实现 |
| `PgVectorIndexService` | `@Service` + `@ConditionalOnProperty(app.rag.mode=pgvector)`,生产态激活 |
| `AuthSeedRunner` | `@Component implements ApplicationRunner`,启动种子 |
| `PatchValidateTool` / `LanguageCommandTool` | `@Component implements AgentTool<...>`,经 `List<AgentTool>` 按类型收集进工具注册表,Agent 运行时按工具名调用 |
| `RabbitSandboxToolGateway` | `@Component @Primary`,按 `SandboxToolGateway` 接口注入 |

## 2. 方法级普查(全部 *Service.java):无死方法

初筛 2 个候选,均为假阳性:

| 项 | 甄别结果 |
|---|---|
| `agent/api/AgentEventService.onStepRecorded()` | `@TransactionalEventListener(AFTER_COMMIT)`,Spring 事件回调 → 保留 |
| `agent/run/AgentRecoveryService.recoverPeriodically()` | 多行 `@Scheduled(fixedDelayString=…)` 注解被初筛脚本漏识 → 保留 |

## 3. 配置键普查(94 个 app.* 叶子键,死键 6 个,已删)

| 项 | 证据 | 处置 |
|---|---|---|
| `app.agent.budget.max-elapsed` / `max-tool-calls` / `max-model-calls` / `max-input-tokens` / `max-output-tokens` / `max-estimated-cost`(app-agent.yml 原 32–38 行) | 字符串 `app.agent.budget` 全仓(backend/src、deploy/、scripts/、docs/、.github/)零出现;实际预算约束由 `AgentModelBudgetPolicy` 构造器读 **`app.agent.model.*`** 键(max-elapsed-ms/max-calls/max-input-tokens/max-output-tokens/max-estimated-cost);其环境变量 `AGENT_MAX_ELAPSED` 等仅存在于该 yml 占位符自身,deploy compose 亦未设置 | 删除整个 `budget:` 块(7 行) |

其余 88 键均有 Java 读点(`${key}` 占位或 `@ConditionalOnProperty`),逐键核对通过。
附注(不属批A处置面):代码里另有约 20 个仅靠 `@Value` 默认值、未在 yml 显式落键的键
(`app.agent.events.*`、`app.agent.model.*`、`app.patch.*` 等),属"未落文档"而非"死配置",不动。

## 4. 依赖普查(`mvn dependency:analyze -DignoreNonCompile=true`):零移除

"Unused declared" 咬合 11 项,逐项判为假阳性,全部保留:

| 项 | 保留理由 |
|---|---|
| `spring-boot-starter-web/security/validation/actuator/data-jpa/amqp/aop`(7 个 starter POM) | starter 为传递依赖聚合 POM,字节码分析永远看不到 starter 自身被"使用";任务口径明示为假阳性 |
| `io.opentelemetry:opentelemetry-exporter-otlp` | Boot 自动配置装配,application.yml `management.otlp.tracing.endpoint` 实证接线 |
| `org.flywaydb:flyway-database-postgresql` | ServiceLoader 运行时数据库支持,任务口径明示为假阳性 |
| `io.github.resilience4j:resilience4j-spring-boot3` | 注解驱动:`OpenAiCompatibleReviewClient` 有 `@Retry`/`@CircuitBreaker` import 实证,yml 有 `resilience4j.retry.instances.aiReview`;该模块供 aspect 自动配置 |
| `io.micrometer:micrometer-tracing-bridge-otel` | tracing 桥自动配置,`management.tracing.sampling.probability` 实证接线 |

"Non-test scoped test only"(amqp-client/logback/flyway-core)为运行时必需、main 无直接 import 的常规形态,不动。
runtime-scope 四件(h2/postgresql/micrometer-registry-prometheus/flyway-database-postgresql)按任务口径为假阳性,未列入处置。

## 5. 陈旧资源

| 项 | 证据/处置 |
|---|---|
| `.worktrees/pr-gatekeeper-agent` 残留 | **已不存在**:`ls .worktrees` → No such file;`git worktree list` 仅主工作树(记录在案,无需操作) |
| `docs/并行实施拆分方案.md` 旧路径引用(文件已迁 `docs/archive/`) | backend/src 共 8 处,全部改为 `docs/archive/并行实施拆分方案.md`(纯注释):`ErrorCode.java`、`PageResponse.java`、`ProjectAuthorization.java`、`SharedApiContractTest.java` 的 Javadoc;`application.yml`、`application-prod.yml`、`config/app-agent.yml`、`config/app-boundary.yml` 的 yml 注释。冻结契约类仅动 Javadoc 路径字符串,签名/枚举/字段零改动。另 `frontend/src/api/page.js:2` 同病但属前端,批A不动(留给 r6 或另行处理) |
| `.github/workflows/ci.yml` | `actions/setup-java@v4` → `@v5`(v4 在 CI 日志刷弃用告警),distribution/java-version/cache 三输入原样;改后 `yaml.safe_load` 校验通过 |
| scripts/ 失效脚本 | 逐个核对 9 个脚本引用的路径与 compose 服务名:`demo-repos/patches`、`demo-repos/mall-order-service(/docs/security-policy.md)`、`evaluation/manifest.json`、`deploy/docker-compose.yml`、`backend/.mvn/settings.xml`、`model-service/requirements.txt` 均存在,compose 服务名均有效 → **无失效脚本** |
| docs/ 死链 | 对 docs/*.md 内全部仓内文件引用做存在性检查:仅 `docs/order-flow.md`、`docs/security-policy.md` 两个"缺失"命中,核对上下文均为 demo 仓库(mall-order-service)内部相对路径的示例载荷,非本仓死链 → **不动** |
