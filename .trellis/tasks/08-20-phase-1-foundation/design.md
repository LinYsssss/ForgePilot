# Phase 1 技术设计

## 1. 设计目标与边界

Phase 1 只建立底座，不建立业务垂直切片。工程必须能真实连接 PostgreSQL/pgvector、被 CI 与架构测试约束、被容器化启动，并在目标 4 GB 主机上留下可审计容量证据。任何需要业务实体、业务表、权限、事务状态机或 AI/SCM 协议才能证明的内容均延后到对应 Phase。

设计遵循以下最小化原则：

- 一个 Spring Boot 应用、一个 Vue 应用、一个 PostgreSQL 实例，不引入第二运行时或中间件。
- 只添加 Phase 1 退出条件直接要求的依赖和文件。
- 架构规则先编码，业务类后加入；不为目录对称创建空分层。
- 临时视觉比较与容量原始证据放在本 Trellis 任务内，选定后的长期规范放入 `.trellis/spec/`。
- Legacy 只提取已被迁移矩阵允许的数据、测试思想和最小工具实现，不复制旧工程边界。

## 2. 目标仓库形态

```text
backend/
  pom.xml + mvnw + .mvn/wrapper/
  Dockerfile
  src/main/java/com/forgepilot/
    ForgePilotApplication.java
    common|auth|project|requirement|scm|knowledge|ai|review/package-info.java
  src/main/resources/
    application.yml
    db/migration/V1__foundation.sql
  src/test/java/com/forgepilot/
    FoundationApplicationTest.java
    FoundationDatabaseTest.java
    ArchitectureRulesTest.java

frontend/
  package.json + package-lock.json
  Dockerfile + nginx.conf
  src/
    app/router.ts
    lib/http.ts
    components/
    styles/
    views/
  tests/

evaluation/
  manifest.quick.json
  schema/
  cases/<12 development case ids>/
  reference-runs/
  tools/score.py
  tools/category-aliases.json

.github/workflows/ci.yml
compose.yaml
.env.example

.trellis/tasks/08-20-phase-1-foundation/
  artifacts/visual-directions/
  evidence/capacity/
  research/
  prd.md design.md implement.md validation.md result.md
```

最终文件名可因生成器的标准输出有小幅差异，但不得改变职责边界或引入额外顶层产品模块。

## 3. 技术基线

### 3.1 后端

- Java 21 LTS。
- Spring Boot `4.1.0`（Spring Initializr displays the same stable release as `4.1.0.RELEASE`）；Maven Central publishes the coordinate without the display suffix. Milestone, RC, and snapshot versions are forbidden.
- Maven Wrapper 作为唯一仓库内构建入口；Spring Boot dependency management 管理 Spring/Testcontainers 兼容版本，ArchUnit 固定稳定版本。
- 最小运行依赖：Spring MVC、Actuator、JPA/JDBC 基础、Flyway、PostgreSQL driver。
- 测试依赖：Spring Boot Test、Spring Boot Testcontainers、Spring Boot BOM-managed Testcontainers `2.0.5`、ArchUnit JUnit 5 `1.5.0`。The BOM-managed 2.x line is used because the Boot 4.1 test integration resolves it as the compatible unified version.

选择 Maven 而非增加多模块 Gradle，是因为当前只有一个后端模块，Legacy 评测证据与团队命令也以 Maven 为主；Phase 1 没有理由为构建本身增加第二层结构。

### 3.2 数据库

- PostgreSQL 主版本固定为 15；Compose 与 Testcontainers 使用同一个经过运行验证的 `pgvector/pgvector:0.8.6-pg15-bookworm` 精确 tag，并把部署 Compose 固定到解析后的 image digest。若实现时该 tag 的 digest/运行验证不可得，则停止并在实现前补充兼容性研究，不改用 PostgreSQL 16 或浮动 `latest`。
- `V1__foundation.sql` 只执行 `CREATE EXTENSION IF NOT EXISTS vector` 及必要注释，不建任何业务表、索引或模型维度。
- 应用启动由 Flyway 完成 migration；Hibernate/JPA 不负责自动建表，DDL auto 必须为 validate/none，而非 create/update。
- 集成测试读取 `server_version_num`、`pg_extension` 和 Flyway history，证明版本、extension 和空库迁移真实有效。

### 3.3 前端

- Node.js 24 LTS + npm lockfile；初始化候选基线为 Vue `3.5.41`、TypeScript `7.0.2`、Vite `8.2.1`、Vue Router `5.2.0`、Vitest `4.1.11`、`@vitejs/plugin-vue` `6.0.8`、`vue-tsc` `3.3.10`。实现前的 peer/engine probe 发现 TypeScript `7.0.2` 与 `vue-tsc 3.3.10` 的 package exports 不兼容，因此锁定最近稳定兼容版本 TypeScript `6.0.2`，并加入 Vue `3.5.41` 的 compiler package。证据写入任务研究文件；禁止 prerelease。
- 请求层使用一个薄的原生 `fetch` 封装，默认同源 JSON、`credentials: same-origin`，保留后续 CSRF header 接入点；Phase 1 不引入 Axios。
- 样式采用 CSS custom properties 和普通 scoped/global CSS；不引入 Tailwind、组件大库、图表库或 CSS-in-JS。
- 不引入 Pinia；Phase 1 没有跨页面业务状态。后续出现真实共享状态再按前端规范决定。
- 测试采用 Vitest + Vue Test Utils；覆盖路由外壳、基础组件交互、语义结构和 reduced-motion 合约。视觉方向 artifact 采用人工浏览器检查并保存截图/记录，Phase 1 不引入 Playwright；Phase 7 再承担完整浏览器 E2E 与视觉回归门槛。

### 3.4 运行与 CI

- 根 `compose.yaml` 只含 `postgres`、`backend`、`frontend` 三个 ForgePilot 服务，使用显式 healthcheck、资源上限与独立命名卷。
- 后端镜像使用多阶段构建；运行层使用 Java 21 JRE，并显式配置 heap/direct/metaspace/NMT 或可等价观测的内存上限。
- 前端构建为静态文件，由轻量 Nginx 提供，并将 `/api` 反向代理到后端或保持同源部署契约。
- CI 分为 backend、frontend、evaluation、compose-smoke 四个逻辑闸门；不访问外部 AI，不使用仓库秘密。

## 4. 后端包与 ArchUnit 设计

八个 `package-info.java` 是包契约，不是空的四层目录。它们让允许包集合在 Phase 1 可见，同时不制造业务类。

`ArchitectureRulesTest` 对 `com.forgepilot` 生产类执行：

1. `slices().matching("com.forgepilot.(*)..")` 无环；根应用类排除于 feature slice。
2. 所有直接顶层 package 必须属于八个白名单，且 `agent/patch/mq/rag/repo/pullrequest/context/assistant/finding` 等禁用顶层包无类。
3. `scm..` 不依赖 `review..`。
4. 名称匹配 `*Repository` 的类型不得被其他 feature 直接依赖；同 feature 与 `review` 通过对方 Service/Query facade 的规则留待有实际类时验证，但测试结构现在就编码。
5. 名称匹配 `*Controller` 的类型不得直接依赖其他 feature 的 `*Repository`。

为避免恒真测试，至少加入测试 fixture 或规则自检，证明新增一个禁用 package 或 `scm -> review` 依赖时测试确实失败；fixture 仅存在测试源码，不进入生产包。

## 5. 前端视觉流程

### 5.1 比较 artifact

前端代理先在任务目录创建独立、可交互的 HTML artifact，不改生产 Vue 令牌。三个方向使用相同的 ForgePilot 中性示例内容，确保比较的是结构与视觉语言而非信息差异。

建议三类方向保持足够差异：

- Direction A：高密度工程控制台，强调扫描效率和证据层级。
- Direction B：文档化研发工作台，强调需求、AC 与上下文的阅读连续性。
- Direction C：克制的审查驾驶舱，强调风险聚焦、人工决策与状态正交。

每个方向必须呈现项目/需求/审查三项信息架构、状态/证据样例、响应式窄屏行为和最小交互；同时附商业契合度、密度、字体策略、语义色、记忆点和禁用模式。三者不能共享仅换色的同一 DOM 布局。

### 5.2 用户选择闸门

主会话核对 artifact 后向用户展示三个方向并暂停。收到明确选择后，前端代理才可以：

- 将选定 tokens 写入 Vue scaffold；
- 新增 `.trellis/spec/frontend/design-contract.md` 与 `motion.md`；
- 用实际约定替换现有六份前端 spec 中的占位内容；
- 将 Stage 7 设计漂移清单写入 `quality-guidelines.md`。

纯视觉决策只记录在 `.trellis/spec/frontend/`，不新增 `docs/v2/DECISIONS.md` 编号。若选择要求新增一级导航、页面或改变产品状态语义，则超出本任务，必须停止请求产品/架构批准。

### 5.3 动效与无障碍

- 动效只用于层级、反馈与状态变化，不作为读取信息的唯一方式。
- `prefers-reduced-motion: reduce` 下取消位移/缩放和非必要连续动画，保留瞬时或极短的不透明度变化。
- 基础组件保持键盘焦点可见、语义元素正确、颜色不是唯一状态载体、文本/背景对比可验。

## 6. 评测契约设计

### 6.1 Legacy 使用

按迁移矩阵执行 `KEEP TOOL / ADAPT`：以 RepoSage 固定提交的 `evaluation/tools/score.py`、`category-aliases.json`、development manifest/fixtures 为来源，先保留自测和确定性匹配行为，再删除 Agent/Patch/runtime arm 等 ForgePilot Phase 1 不需要的字段。不得迁入旧后端评测服务、运行器或 holdout 结果。

### 6.2 快速集

快速集固定为以下 12 个 development 用例，覆盖正例、明确 nonFinding、业务规则、工程契约、安全与多语言。该清单在本任务规划中冻结，执行期间不得自行替换：

1. `java-sql-resource-leak`
2. `typescript-ambiguous-null`
3. `java-broken-build`
4. `biz-fee-rate-hardcoded`
5. `biz-status-machine-bypass`
6. `eng-contract-drift-dual-encode`
7. `eng-transactional-self-invocation`
8. `biz-currency-unchecked`
9. `fp-java-whitelist-order-by`
10. `fp-python-chore-gitignore-cleanup`
11. `miss-template-share-authz`
12. `sec-java-customer-search-sqli`

选择理由与 Legacy commit 写入 quick manifest。只复制这些 development fixtures；不得复制或运行任何 holdout fixture。`biz-currency-unchecked` 取代研究草稿中曾出现的 `eng-http-client-leak`，以保证规划、实现和验证清单使用同一版本。

### 6.3 输入输出

- Manifest：case id、schema version、source commit、split、language、fixture、Requirement、AC、consistency truth、expected findings 与 nonFindings。
- Run envelope：case id、structural status、findings、AC coverage、可选 token/latency 元数据；Phase 1 reference run 明确标为 synthetic/reference，不伪装为模型结果。
- Score output：overall、by-category、by-case、AC verdict、structural failures、notRun、token/latency（有数据时）；分母为 0 返回 n/a。
- `score.py --selftest` 使用内置小矩阵；reference runs 用于证明 quick manifest 可被完整重算并得到固定 snapshot。

## 7. Compose 与健康流

```text
postgres healthy
  -> backend starts
  -> Flyway enables vector
  -> backend actuator health UP
  -> frontend static service starts
  -> frontend health + backend health checked
```

Compose 使用专用 project name（例如 `forgepilot-phase1`）运行。清理时只允许针对该 project 执行 `docker compose -p forgepilot-phase1 down`；只有在明确需要重验空库时才删除该 project 的命名卷，不影响现有 cpa/cli-proxy/cloudflared。

## 8. 4 GB 容量测量协议

### 8.1 基线

启动 ForgePilot 前先连续采样 5 分钟（建议每 15 秒一次），记录 UTC 时间与单调 elapsed time、内核/CPU、`MemTotal/MemAvailable`、memory pressure、swap 已用与 swap-in/out、现有容器 cgroup memory/RSS/PSS、`cloudflared` MemoryCurrent/PSS、重启数和 OOM 日志基线。ForgePilot 尚未启动；构建、测试、备份或其他一次性高负载任务不得与基线重叠。

### 8.2 运行配置

- 后端使用明确的小内存 JVM 上限，初始建议 `-Xms128m -Xmx384m`、Direct Memory 128m、Metaspace 128m，并开启可观测的 Native Memory Tracking 或等价指标。
- PostgreSQL 明确设置 `shared_buffers`、`work_mem`、`maintenance_work_mem`、`max_connections`，初始值以空底座为目标，不声称可代表 Phase 6 Review 负载。
- 前端静态服务设置容器内存上限；所有 ForgePilot 容器均有 restart/health 信息和可归属的 project label。

具体数值可因镜像实测微调，但每次改动必须记录并重新跑完整的 5 分钟基线、2 分钟预热和 4 分钟稳定窗口，不能只保留最终一次。

### 8.3 预热与稳定采样

三服务 healthy 后执行 2 分钟低频健康预热；预热不计入稳定窗口。预热成功后，每 15 秒采样一次，稳定窗口至少 240 秒且至少 17 组：

- `MemAvailable`、swap used、`vmstat` swap in/out；
- 三个 ForgePilot 容器和既有容器的 cgroup memory、RSS/PSS、PID 数；
- JVM heap/non-heap/direct buffer 指标；
- PostgreSQL `SHOW` 的内存参数与进程聚合 RSS/PSS；
- systemd `cloudflared` MemoryCurrent/PSS；
- 内核/容器 OOM 与重启计数。

Swap 判定关注相对基线的持续增长；SwapUsed 保持不变时，少量页级 swap-in/out 抖动记录为观察项，不单独判定容量失败。

原始输出保存到任务 `evidence/capacity/<run-id>/`，包括运行 metadata、rendered Compose、镜像 digest、命令日志与未经手工改写的 CSV/JSONL，再生成一份结论 Markdown。运行结论固定为 `PASS / FAIL / INVALID`：采集缺失、时长不足、配置漂移或不可归因的同时负载属于 `INVALID`；持续健康、无 OOM/重启、无相对基线的持续 swap 增长，且稳定窗口所有有效样本 `MemAvailable >= 1 GiB` 才为 `PASS`。

## 9. CI 设计

### Backend gate

- Java 21；Maven dependency cache。
- `./mvnw -B verify`，包含 Spring/Testcontainers/ArchUnit。

### Frontend gate

- Node 24；`npm ci`。
- lint（若已配置）、typecheck、unit test、production build。

### Evaluation gate

- Python 3 标准库。
- manifest validation、`score.py --selftest`、reference quick-set recomputation 与 snapshot comparison。
- grep/validator 明确拒绝 quick manifest 中出现 `holdout`。

### Compose smoke gate

- `docker compose config`、镜像构建、独立 project 启动、健康检查、HTTP smoke。
- 无论成功失败都执行仅针对该 project 的清理；不在 CI 做目标主机容量测试。

## 10. 安全、兼容与回滚

- 凭据仅由环境变量注入；仓库只提交 `.env.example`，不提交真实 secret。
- Phase 1 不开放业务端点；Actuator 只暴露必要健康信息，容量指标仅在本地/测量 profile 使用。
- 不继承 Legacy Flyway 历史；只从干净 V1 foundation 起步，后续业务 migration 在相应 Phase 加入，首个发布版前按实施计划评估 squash。
- 若 Spring Boot 4.1.x 与必要依赖或 4 GB 运行边界发生已验证的不兼容，先记录证据并回到规划；不得悄然引入第二 runtime 或放宽 PostgreSQL 15/架构规则。
- 回滚应用变更以文件分组为单位；运行态只清理 `forgepilot-phase1` project 与其明确命名卷。用户现有容器、systemd 服务和数据不属于本任务回滚范围。

## 11. 设计取舍

- 不创建 Trellis child task：Phase 1 的四个切片文件范围可独立派发，但最终退出条件（Compose、CI、容量与 result）强耦合，保留一个活动任务更符合“一次一个可验证 Phase”的治理规则。
- 不用 UI 框架/Pinia/Axios：当前没有业务状态和组件规模证明这些依赖必要。
- 不在 CI 跑目标主机容量：CI 证明可构建/可启动，目标主机测量证明短时空栈容量，两类证据不混用。
- 不在 planning 阶段提前选择视觉方向：用户需要基于实际交互 artifact 判断；因此把选择设计为执行中的显式暂停点。
