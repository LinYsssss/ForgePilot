# Phase 1 最小绿地底座

## Goal

在不实现任何业务能力的前提下，为 ForgePilot V2 建立可重复构建、可在真实 PostgreSQL 15 + pgvector 上启动、可由 CI 验证、可在目标 4 GB 主机上运行的最小工程底座；同时冻结后续纵向切片必须遵守的前端视觉契约和评测契约。

本阶段的价值不是交付登录、项目或审查功能，而是提前消除四类高返工风险：工程结构与架构边界失控、数据库/pgvector 环境不可复现、前端视觉方向持续漂移、评测口径或 4 GB 部署容量到后期才失败。

## Confirmed Facts

- Phase 0 与 R2.3 文档基线已完成；Phase 1 仅获准进入任务级规划，必须在本任务规划获用户确认并执行 `task.py start` 后才能修改应用代码。
- 后端目标是单个 Spring Boot 模块化单体，顶层包仅允许 `common/auth/project/requirement/scm/knowledge/ai/review`；Finding 仍属于 `review`，且只有一个 Review Engine。
- PostgreSQL 最低版本为 15，pgvector 是硬依赖；Testcontainers、Compose 与部署环境必须保持同一主版本约束。
- Phase 1 禁止登录、项目、成员、需求、知识、SCM、Review、Finding 等业务实体、业务表、业务 API、业务状态机和业务 UI。
- 前端一级信息架构仍只有项目、研发需求、代码审查；Phase 1 只交付路由/请求/样式/组件底座及视觉方案，不增加业务页面或一级菜单。
- 评测只允许从 Legacy 固定的 26 个 development 用例中选定本任务的 12 个快速集；Phase 8 前不得运行或据此查看 12 个 holdout 用例结果。
- Legacy RepoSage 固定参考提交为 `96137dd3b43e14c5e8881c99688663afd979cf4e`。评测工具属于 `KEEP TOOL / ADAPT`，不得带回 Agent、Patch、旧运行时字段或整套旧架构。
- 当前目标主机为约 3.8 GiB 内存、4 GiB swap，并已有 `cpa-manager-plus`、`cli-proxy-api` 与系统级 `cloudflared` 常驻；现有瞬时资源数据只能作为测量前参考。用户于 2026-08-20 将本任务稳定窗口从 30 分钟明确缩短为 4 分钟；该证据只支持空栈短时容量结论。

## In Scope

1. 后端工程底座：Java/Spring Boot 构建、应用入口、八个允许顶层包的包契约、Actuator 健康检查、Flyway、真实 PostgreSQL/pgvector 集成测试、ArchUnit。
2. 数据库与本地运行：仅启用 pgvector 所需的基础 migration、PostgreSQL 15+ 容器、后端与前端镜像、可重复的空库 Compose 启动。
3. 前端工程底座：Vue 3 + TypeScript 构建、路由外壳、最小请求层、设计令牌、基础展示组件、测试与构建配置。
4. 视觉契约：三套结构和性格均不同的可交互 HTML 方向稿；用户选择后，将设计、组件、动效、无障碍与漂移规则写入 `.trellis/spec/frontend/`。
5. 评测底座：ForgePilot V2 评测 manifest/结果契约、确定性评分器、自测矩阵，以及从 Legacy development 集选择的 12 例快速集；不调用 Review Engine。
6. 基础 CI：后端、前端、评测、架构规则与 Compose smoke 的最小发布闸门。
7. 容量验证：在目标 4 GB 主机上完成 5 分钟既有服务基线、2 分钟预热和至少 4 分钟空栈稳定窗口，保存原始命令、采样数据和结论。
8. Trellis 验收：定向验证、实际 diff 审查、`result.md`、风险与提交分组；完成后停在 Phase 1 评审闸门。

## Requirements

### R1. 范围纯度

- Phase 1 的源码只能表达工程、运行、测试、架构和视觉/评测契约，不得表达业务用例或业务状态。
- 不创建 16 张业务表中的任何一张；唯一允许的数据库变化是启用/验证 pgvector 所需的基础结构。
- 不增加 Agent、Patch、MQ/Outbox、第二 AI runtime、第二 Review Pipeline、本地 Git/clone、代码向量库或额外一级菜单。

### R2. 可重复的后端与数据库底座

- 新环境可通过仓库内 wrapper/容器命令构建后端，不依赖开发者机器预装的全局 Maven。
- 空数据库启动时 Flyway 成功执行，PostgreSQL 主版本与 pgvector extension 均被自动化测试验证。
- 后端只暴露框架级健康检查，不新增业务 Controller。

### R3. 架构边界自动化

- ArchUnit 至少证明顶层包无环、禁止顶层包不存在、`scm` 不依赖 `review`、跨 feature 不直接注入其他模块 Repository、Controller 不直接访问跨模块 Repository。
- 规则应在后续加入业务代码后继续生效，不能只针对当前空骨架写成恒真测试。

### R4. 最小前端基础

- 前端具备类型检查、测试、构建与路由外壳；请求层默认同源、支持 cookie/CSRF 后续扩展，但本阶段不实现认证。
- 只呈现信息架构和通用空状态，不使用虚构业务数据模拟已完成功能。
- 不预装无当前依据的全局状态框架、CSS/UI 大型框架或图表体系。

### R5. 视觉选择与规范固化

- 三个方向必须在布局密度、字体层级、视觉性格和交互记忆点上有实质差异，不能只是换配色。
- 每个方向均说明商业契合度、密度、字体策略、语义色角色、一个记忆点及明确禁用模式，并以可交互 HTML artifact 供用户比较。
- 用户选择是执行中的强制暂停点。选择前不得把任一方向固化为生产前端；选择后必须补齐设计令牌、组件、动效、`prefers-reduced-motion` 和设计漂移检查。

### R6. 可复现评测契约

- 评分器保持确定性 1:1 匹配、Finding 漏报/误报、AC verdict、结构失败、Token/耗时和 `notRun` 等独立口径，不合成误导性的单一总分。
- 快速集只能包含 development 用例，并在 manifest 中固定 ID、来源提交、选择理由与 schema 版本。
- Phase 1 只运行评分器自测和版本化的参考输入重算；禁止调用不存在的 Review Engine，禁止运行 holdout。

### R7. 4 GB 容量证据

- PostgreSQL、空后端、前端静态服务与目标机现有常驻服务共同稳定运行不少于 4 分钟；每 15 秒采样，至少 17 组。
- 记录进程/容器 RSS 与 PSS、JVM heap/direct memory、PostgreSQL 内存参数、系统 `MemAvailable`、swap 变化和 OOM 证据。
- 空载稳定期结束时至少保留 1 GiB `MemAvailable`；若不满足，必须调整明确的 JVM/PostgreSQL 上限并完整重测，不能以瞬时数据或估算代替。

### R8. 验收与阶段停止

- CI 与本地定向验证使用同一组可复现命令，失败项不得标为完成。
- `result.md` 必须记录完成项、未完成项、命令及结果、架构/产品边界、Legacy 使用、容量结论、风险与回滚方法。
- Phase 1 退出后停止；不得自动创建或启动 Phase 2。

## Acceptance Criteria

- [ ] AC1：从干净 checkout 可使用仓库内命令完成后端构建、前端安装/检查/构建、评测自测，无需全局 Maven。
- [ ] AC2：真实 PostgreSQL 15+ / pgvector Testcontainers 测试证明 Flyway 可从空库启动、`server_version_num >= 150000` 且 `vector` extension 可用。
- [ ] AC3：基础 migration 不包含任何业务表；全仓检查不存在业务实体、业务 Controller、业务服务或业务状态机。
- [ ] AC4：ArchUnit 五条规则全部通过，规则扫描范围覆盖未来 `com.forgepilot` 业务类而非只匹配测试 fixture。
- [ ] AC5：Compose 能以独立 project name 构建并启动 PostgreSQL、后端、前端；健康检查全绿，销毁命令仅作用于该项目及其命名卷。
- [ ] AC6：前端类型检查、单元/交互检查和生产构建通过；只有三项既定信息架构与通用空状态，无新增一级页面或业务交互。
- [ ] AC7：三套可交互视觉方向完成并经用户选择；选定方向已写入 `.trellis/spec/frontend/design-contract.md`、`motion.md` 及现有前端规范文件，包含 reduced-motion 和漂移检查。
- [ ] AC8：评测 manifest/评分器契约可验证；Legacy development 快速集固定为 12 例，评分器 `--selftest` 与参考运行重算结果稳定；仓库和执行记录均无 holdout 运行产物。
- [ ] AC9：基础 CI 对后端、前端、评测、架构和 Compose smoke 全绿，不依赖本地秘密或外部 AI 服务。
- [ ] AC10：目标 4 GB 主机完成 5 分钟基线、2 分钟预热和连续不少于 4 分钟（至少 17 组）的版本化稳定采样；所有稳定样本 `MemAvailable >= 1 GiB`，无 OOM/重启，swap 无相对基线的异常持续增长。结论明确限定为短时空栈容量。
- [ ] AC11：`git diff --check`、受影响模块的编译/类型检查、定向测试和边界检索全部通过，实际 diff 经主会话与审查代理核验。
- [ ] AC12：任务 `result.md` 完整，明确无新产品/架构决策（若实际触发则先停下请求批准），并确认未进入 Phase 2。

## Out of Scope

- 登录、Session/Cookie/CSRF 业务流程、账户、项目、成员和角色。
- Requirement、AC、Revision、知识上传/检索、SCM/Webhook、PR、Review、Finding 及任何业务表/API/UI。
- AI Gateway、真实模型调用、Embedding、向量索引、Prompt、Review Engine 或评测实验臂。
- GitLab/GitHub 集成、本地 clone、Agent、Patch、消息队列、Outbox、Redis、完整 Observability。
- 运行 holdout、依据 holdout 调参、把 Legacy 工程或迁移历史整包复制进来。
- Phase 2 或后续阶段的实现、提交或授权。

## Execution Checkpoints

1. 本规划经用户明确确认后，主会话才能运行 `task.py start`。
2. 视觉比较 artifact 完成后必须暂停，由用户选定方向；未选择时只允许保留方向稿，不得继续固化前端规范。
3. 容量验证必须在集成 smoke 全绿后执行；容量失败即 Phase 1 未完成。
4. 全部验收证据齐备后更新 `result.md`，展示提交分组与 commit message，等待用户确认；不自动推送。
5. Phase 1 完成后停止并等待 Phase 2 的单独授权。

## Planning Status

- Blocking open questions: none for entering implementation after final plan approval.
- Staged user decision: the final visual direction is intentionally selected after the three interactive artifacts exist; it blocks frontend contract finalization, not the initial creation of the comparison artifacts.
