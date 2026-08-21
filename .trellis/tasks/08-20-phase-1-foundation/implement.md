# Phase 1 执行计划

## 0. 启动前闸门

- [ ] 用户已审阅最新 `prd.md`、`design.md`、本文件与 `validation.md`，并明确授权进入实现。
- [ ] 主会话运行 `python3 ./.trellis/scripts/task.py start 08-20-phase-1-foundation`，确认状态为 `in_progress`。
- [ ] 主会话加载相关 spec/research；派发提示第一行包含 `Active task: .trellis/tasks/08-20-phase-1-foundation`。
- [ ] 实现代理不得创建子代理、提交、推送、reset、checkout 或删除未授权文件。
- [ ] 先记录 `git status --short`，识别并隔离用户已有改动。

## 1. 后端与数据库底座

**负责范围**：`backend/**`，以及只与后端构建直接相关的根忽略项。不得修改前端、评测、Compose、CI 或 Trellis spec。

- [ ] 用稳定版本初始化 Java 21 + Spring Boot 4.1.x Maven 工程及 Maven Wrapper。
- [ ] 只加入 MVC、Actuator、JPA/JDBC、Flyway、PostgreSQL、Testcontainers、ArchUnit 所需依赖。
- [ ] 创建 `ForgePilotApplication` 和八个允许顶层包的 `package-info.java`；不建空四层或业务类。
- [ ] 配置 `application.yml`：环境变量数据源、Flyway、禁止 ORM 自动建表、最小 Actuator health。
- [ ] 添加 `V1__foundation.sql`，内容仅限 pgvector extension/注释；执行表名边界检查。
- [ ] 添加 Spring context、真实 PostgreSQL/pgvector/Flyway 集成测试。
- [ ] 添加 ArchUnit 五条规则与能证明规则非恒真的测试 fixture。
- [ ] 添加多阶段后端 Dockerfile 和容器健康契约。
- [ ] 运行 `./mvnw -B verify`，记录镜像与依赖版本。

**验收点**：PRD AC1–AC4。

## 2. 前端脚手架与视觉比较 artifact

**负责范围**：`frontend/**` 与任务目录 `artifacts/visual-directions/**`。在用户选择前不得修改 `.trellis/spec/frontend/**`。

- [ ] 初始化 Vue 3 + TypeScript + Vite + Vue Router，固定 npm lockfile。
- [ ] 建立三项既定信息架构的路由外壳、通用空状态和薄 `fetch` 请求层。
- [ ] 建立 CSS token 基础和最小组件；不加入 Pinia、Axios、UI/CSS 大框架或图表库。
- [ ] 添加 typecheck、unit/interaction、semantic/a11y/reduced-motion 定向测试与生产构建。
- [ ] 添加前端多阶段 Dockerfile、静态服务器配置和健康端点。
- [ ] 在任务 artifact 中制作三个实质不同、可交互的视觉方向，并逐项附商业契合度、密度、字体、语义色、记忆点和禁用模式。
- [ ] 主会话检查三个 artifact 的实际文件与交互，确认不是同布局换色。

**暂停点 A（必须）**：向用户展示三个方向并等待选择。选择前只允许修复 artifact 自身问题，不得继续下一节。

## 3. 固化选定视觉契约

**负责范围**：`frontend/**` 与 `.trellis/spec/frontend/**`。

- [ ] 将用户明确选择的方向 tokens/组件样式应用到 Vue scaffold。
- [ ] 新增 `design-contract.md` 和 `motion.md`。
- [ ] 用本仓库实际约定填充现有 frontend spec：目录、组件、hook/composable、状态、类型和质量。
- [ ] 将 `prefers-reduced-motion` 规则与设计漂移清单写入长期规范。
- [ ] 运行前端 typecheck、test、build；主会话检查 spec 与实际代码一致。

**验收点**：PRD AC6–AC7。

## 4. 评测契约与快速集

**负责范围**：`evaluation/**`。不得读取/运行 Legacy holdout fixture；不得添加 Review Engine 或模型调用。

- [ ] 从固定 Legacy commit 提取 `score.py`、类别别名、仅 12 个 development fixture 和对应 manifest 条目。
- [ ] 先运行迁入前的 Legacy scorer selftest，记录基线；再按 ForgePilot V2 envelope 最小适配。
- [ ] 清除 Agent、Patch、双运行时、旧后端 report 等 Phase 1 无关字段；保留确定性 1:1 匹配、AC verdict、notRun、结构失败和元数据能力。
- [ ] 为 quick manifest 添加 schema、source commit、选择理由和 `split=development` 强校验。
- [ ] 建立 synthetic/reference runs，明确标识非真实模型结果，用于可重算 snapshot。
- [ ] 添加 manifest validator、`score.py --selftest` 和 reference recomputation 命令。
- [ ] 运行全仓 holdout 泄漏检查，确认无 holdout fixture 或结果被迁入/执行。

**验收点**：PRD AC8。

## 5. Compose 与基础 CI 集成

**负责范围**：`compose.yaml`、`.env.example`、`.github/workflows/**`、必要的根级运行说明。不得实现业务功能。

前置：步骤 1、3、4 已完成。

- [ ] 编写仅含 postgres/backend/frontend 的 Compose，固定 PostgreSQL 15 + pgvector 镜像线、健康检查、资源上限和命名卷。
- [ ] 使用两个不同的 `forgepilot-phase1-*` 独立 project name 与全新卷，连续执行两次 cold-start，验证 Flyway、backend health、frontend health/API proxy 契约和空库可重复性。
- [ ] 编写 backend/frontend/evaluation/compose-smoke CI jobs，使用与本地相同的核心命令。
- [ ] 确保 smoke 清理只作用于 CI/Phase 1 project，失败路径也执行清理。
- [ ] 验证 CI 不需要 AI/SCM 凭据或真实 secret。

**验收点**：PRD AC5、AC9。

## 6. 目标 4 GB 主机容量测量

**负责范围**：任务目录 `evidence/capacity/**`，必要的非破坏性测量脚本，以及 Compose 资源参数。不得停止/重启/改配现有 cpa、cli-proxy 或 cloudflared。

前置：步骤 5 smoke 全绿。

- [ ] 保存目标机 5 分钟基线：现有服务、MemAvailable、memory pressure、swap、OOM、RSS/PSS/cgroup 与重启数。
- [ ] 以明确 JVM/PostgreSQL/容器内存上限启动 ForgePilot 空栈，完成健康 smoke 与 2 分钟预热。
- [ ] 每 15 秒采样至少 17 次且稳定窗口不少于 240 秒，覆盖系统、现有服务和 ForgePilot 三服务。
- [ ] 保存 JVM heap/non-heap/direct、PostgreSQL参数/进程内存、容器重启/健康和 OOM 证据。
- [ ] 以 `PASS / FAIL / INVALID` 汇总结论：稳定窗口全部有效样本 `MemAvailable >= 1 GiB`、持续健康、无 OOM/重启、swap 无异常持续增长；采集缺失、时长不足或并发环境噪声为 INVALID。
- [ ] 若失败，仅调整 Phase 1 明确资源上限并重新执行完整 5+2+4 分钟协议；保留失败运行证据，不用瞬时快照替代。
- [ ] 测量结束仅清理 `forgepilot-phase1` project；确认现有服务仍健康。

**验收点**：PRD AC10。

## 7. 全范围审查与修复

- [ ] 主会话收集每个实现代理的完成项、文件、命令结果、风险和假设。
- [ ] 主会话逐个检查实际文件和 `git diff`，不以代理总结代替事实。
- [ ] 派发定向审查代理：架构/范围、前端契约、评测/容量证据；文件修改范围不得互相重叠。
- [ ] 对每个 finding 复核实际代码后再修复；不接受无证据的“防御性”扩张。
- [ ] 运行 `validation.md` 的最终命令集，包括 `git diff --check`、编译/类型检查、定向测试、Compose smoke、边界与 holdout 检索。
- [ ] 确认无业务源码、业务表、额外顶层包、额外一级菜单或 Phase 2 内容。

**验收点**：PRD AC11。

## 8. Finish 与提交闸门

- [ ] 更新 `result.md`：完成/未完成、代理分工、文件范围、测试证据、产品/架构边界、Legacy、风险、回滚与 Phase 2 前置条件。
- [ ] 执行 Trellis spec update 判断；只有实际形成的新长期规范才写入 spec，且不得修改已冻结产品/架构决策。
- [ ] 检查 `git status --porcelain` 与最近提交风格。
- [ ] 按逻辑单元展示提交分组和 commit message；列出未识别 dirty files。
- [ ] 等待用户确认后才提交；不 amend、不自动推送。
- [ ] Phase 1 验收后停止，不创建、不启动 Phase 2。

**验收点**：PRD AC12。

## 文件所有权与推荐代理顺序

1. 后端实现代理：`backend/**`。
2. 前端/视觉代理：`frontend/**` + `artifacts/visual-directions/**`；视觉选择后再独占 `.trellis/spec/frontend/**`。
3. 评测代理：`evaluation/**`。
4. 集成代理：根 Compose/CI，只在前三个切片稳定后执行。
5. 审查代理：默认只读；若需修复，由主会话按 finding 分配不重叠文件。

前三个实现切片可以并行；Compose/CI 依赖其产物，容量依赖 Compose；视觉规范固化依赖用户选择。实际并发不得超过平台上限，子代理不得再派生代理。

## 回滚点

- Backend、frontend、evaluation 各自可以按独立文件组回滚，不影响其他切片。
- 视觉选择前，生产 frontend 不绑定任一方向；方向 artifact 可整体删除并重做。
- Compose smoke 或容量失败时，优先回滚根集成/资源参数，不删除用户现有容器或服务。
- 若发现产品/架构决策冲突，停止实现并回到规划/决策文档，不在代码中增加兼容分支绕过。
