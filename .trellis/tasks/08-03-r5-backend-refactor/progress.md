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

## 批C 模块边界（未开始）
