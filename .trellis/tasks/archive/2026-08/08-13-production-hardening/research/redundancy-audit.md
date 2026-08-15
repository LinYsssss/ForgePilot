# 冗余审计（步骤 6）——backend

> 结论先行：**本轮不删除任何类**。358 个 main 源类全部有存活理由，无死代码可清。
> 本文档是步骤 6 的交付物本身（「已核未删」清单），不是待办。
>
> 复现命令（脚本与本文件同目录）：
> ```bash
> python .trellis/tasks/08-13-production-hardening/research/classify-redundancy.py
> ```
> 原始结果留档：`redundancy-scan.json`。

## 1. 方法与口径

两步走，刻意做成「宁可漏报不可误报」：

1. `scan-redundancy.py`：对每个 main 源类的**简单类名**做全词匹配，搜索范围覆盖
   `backend/src/main` 与 `backend/src/test` 下的 java/yml/yaml/properties/sql/xml/json，
   排除定义文件自身。匹配故意粗糙（注释、字符串、YAML 里出现也算引用）——这里的
   一次误判等于删掉活代码，代价不对称。
2. `classify-redundancy.py`：对「零引用」的类按 Spring 装配标记二次分类。
   **零直接引用不等于死代码**：控制器、配置类、实体、健康指示器、启动类都由类路径扫描
   实例化，调用点本来就是零。

## 2. 实测结果（2026-08-14）

| 桶 | 数量 | 判定 |
|---|---|---|
| main 源类总数 | 358 | — |
| 零直接引用 | 30 | **全部框架托管**，无一可删 |
| 仅被测试引用 | 31 | 全部为 Spring 按接口/注册表/监听器装配的生产类 |
| 纯粹无引用（真候选） | **0** | 无 |

### 2.1 零引用但框架托管（30）

覆盖 `@SpringBootApplication`（`CodereviewApplication`）、各 `@RestController`
（Auth/Knowledge/MqLog/AiCallLog/AgentFinding/ClientError/Feedback/两个 Webhook…）、
`@Configuration`（`FindingDomainConfiguration`、`LanguagePluginConfiguration`…）、
`HealthIndicator`（`AiProviderHealthIndicator`、`ModelServiceHealthIndicator`）、
`CommandLineRunner`（`AuthSeedRunner`）等。删任何一个都会直接掉功能。

### 2.2 仅被测试引用（31）——同样全部存活

这一桶最容易被误判成「只有测试在用」，实际是**装配方式不产生直接引用**：

- **步骤执行器 8 个**（`PlanningStepExecutor`、`AnalyzingChangeStepExecutor`、
  `ExecutingToolsStepExecutor`、`GeneratingPatchStepExecutor`、`ValidatingPatchStepExecutor`、
  `VerifyingFindingsStepExecutor`、`PublishingResultStepExecutor`、`WaitingApprovalStepExecutor`）：
  均为 `@Component implements AgentStepExecutor`，由 Spring 汇集成集合后按步骤类型分发，
  没有任何地方 `new` 它们——这正是插件式注册的预期形态。
- **消息消费者**：`AgentStepConsumer`、`ReviewTaskConsumer`（`@Component` + 监听注解）。
- **Web 基础设施**：`GlobalExceptionHandler`（`@ControllerAdvice`）、
  `SecurityAuditFilter`（`OncePerRequestFilter` + `@Order`）。
- **后台作业**：`AgentOutboxScheduler`、`AgentRecoveryService`（`@ConditionalOnProperty` 门控，
  生产开、测试关）。
- **对外适配器**：`GitHubReviewPublisher`、`GitLabReviewPublisher`、`DingTalkNotifier`、
  Git 工具三件（`GitDiffTool`/`GitFileTool`/`CodeSearchTool`）、`JavaChangeContextExtractor`
  ——都按接口实现被收集。
- **配置校验**：`ProdSecretValidator`（prod profile 启动即校验密钥，测试里引用最多是因为
  负向用例最密集）。

### 2.3 计划点名的两个保留项

- **`LegacyReviewProjectionService`（保留）**：把完成的 Agent Run 投影回旧 `review_report` 表，
  让存量审查 UI 在 Agent 管线并行运行期间继续可用。投影幂等（`review_report.agent_run_id`
  唯一键）。**旧 UI 尚未退役，删它等于直接打断存量用户路径**；退役条件是前端全量切到
  Agent 视图（墨境重构完成）之后，届时连同旧表一起处理。
- **`MockAiReviewClient`（保留）**：`@ConditionalOnProperty(app.ai.provider=mock,
  matchIfMissing=true)`，即**缺省实现**。没有它，未配置真实 provider 的环境（本地起服、
  CI 上下文、演示）会直接因缺 `AiReviewClient` bean 起不来。同理保留
  `NoopVectorIndexService`、`NoopModelRiskClient`、`MockEmbeddingClient`——都是条件化降级实现，
  不是测试替身。

## 3. 本轮未做的部分（明确边界）

- **方法/字段级死代码未扫**。类粒度可以用「谁引用了这个名字」低成本判定；方法级要准，
  需要调用图分析（重载、接口实现、反射、Spring 代理都会让文本匹配失真），
  拿文本匹配去删方法是净负收益。若要做，应接入编译期工具（如 IDE 的 unused declaration
  或 error-prone）而不是继续堆 grep。
- **重复代码未做归并**。上一轮已收敛过一处真重复（run 级授权守卫 → 
  `AgentRunService.requireOwnedRun` 单一实现），其余未发现同类。

## 4. 复查建议

这份结论是**时点结论**。类被删到只剩测试引用、或新增的条件化实现被误当死代码，都会随时间出现。
下次大改后重跑上面两个脚本即可，判据不变：
**零引用不等于死，先问「谁负责实例化它」**。
