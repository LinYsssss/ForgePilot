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

## 批B 超长类/方法拆分（未开始）

## 批C 模块边界（未开始）
