# Phase 1 验收结果

任务：`.trellis/tasks/08-20-phase-1-foundation`
日期：2026-08-21（实现始于 2026-08-20）
状态：**本阶段目标产物已全部交付，退出条件已满足，等待用户验收与提交确认。**

本文件按 `docs/v2/IMPLEMENTATION-PLAN.md` 的 `result.md` 验收模板组织。所有命令与
结果均为实际执行记录，退出码如实记载；未执行或无法执行的项明确标注，不以推断代替。

---

## 1. 验收标准逐项结论

| AC | 结论 | 依据 |
|---|---|---|
| AC1 干净 checkout 可用仓库内命令构建 | **通过** | 在全新空 `MAVEN_USER_HOME` 且容器内无全局 `mvn` 的条件下，`./mvnw` 自举 Maven 3.9.16 并完成 `verify`；前端 `npm ci` 从 lockfile 重装 206 包；评测仅用 Python 3 标准库 |
| AC2 真实 PG15+/pgvector Testcontainers | **通过** | `FoundationDatabaseTest` 2/2：`server_version_num >= 150000`、`vector` extension 非空、`<->` 距离 = 1、Flyway V1 `success=true`、无业务表 |
| AC3 migration 无业务表、无业务代码 | **通过** | `V1__foundation.sql` 仅 `CREATE EXTENSION IF NOT EXISTS vector`；三条边界检索零命中；`target/classes` 恰好 9 个 class（1 入口 + 8 `package-info`）；Compose smoke 运行时复验 `public` schema 业务表数 = 0 |
| AC4 ArchUnit 五条规则且非恒真 | **通过** | 6/6 通过；在 `/tmp` 隔离副本注入**生产类**做反证探针：注入 `project↔knowledge` 互引 → `featureSlicesAreFreeOfCycles` 失败；注入 `agent.LeakedAgent` + `scm→review.ReviewRepository` + `Controller→跨模块 Repository` → 另外三条规则各自按预期失败 |
| AC5 Compose 独立 project 构建启动、健康、清理 | **通过** | 两次全新卷冷启动 `final-e` / `final-f` 均 exit 0，证据见 `evidence/smoke/` |
| AC6 前端检查与构建 | **通过** | lint / typecheck / test(8) / build 全部 exit 0 |
| AC7 视觉方向已选并写入规范 | **通过（含一处已记录的偏离）** | 用户选定方向 B；生产采用 B 的信息结构与 teal/mint 色相家族但明度方案为 light，该差异 2026-08-20 落地时未留痕，2026-08-21 经用户确认保留并已在 `evidence/visual-selection.md` 与 `design-contract.md` 记录 |
| AC8 评测契约可验证、无 holdout 产物 | **通过** | `CORPUS VALID`、`SELFTEST OK (30 checks)`、`REFERENCE MATCH`、`HOLDOUT GUARD OK`（以全仓 `--root .` 执行） |
| AC9 基础 CI 全绿且不依赖秘密 | **部分通过（如实记录）** | `ci.yml` YAML 合法、四 job 依赖正确、引用的 7 个路径全部存在、CI 步骤与本地命令逐条同源且本地全绿；但**尚未推送，GitHub Actions 从未真实运行过**。这是本阶段唯一未闭环的验收项 |
| AC10 4 GB 容量证据 | **通过** | 权威运行 `20260821T030713Z-53877bba63ce`，19/19 闸门通过，详见第 4 节 |
| AC11 diff 与边界核验 | **通过** | `git diff --check` 干净；三个只读审查代理的结论由主会话逐条回到源文件核对，含一条误报被推翻 |
| AC12 `result.md` 完整 | 本文件 |

---

## 2. 影响范围与业务边界

新增/修改的范围：`backend/**`、`frontend/**`、`evaluation/**`、`compose.yaml`、
`.env.example`、`.github/workflows/ci.yml`、`scripts/phase1-compose-smoke.sh`、
`.trellis/spec/{backend,frontend}/**`、本任务目录的 artifacts 与 evidence。

**业务边界保持完好**，逐项核实：

- **数据表**：0 张。唯一的 migration 只启用 pgvector extension，16 张业务表一张未建。
- **API**：0 个业务端点。后端只暴露 Actuator `health`（默认 profile 下
  `/actuator/metrics` 实测返回 **404**，容量指标仅在 `capacity` profile 下开放，
  该 profile 全仓唯一设置处是容量测量脚本）。
- **页面**：前端只有 项目 / 研发需求 / 代码审查 三项既定信息架构的路由外壳与通用
  空状态，路由表与 `ARCHITECTURE.md` §6 的七条路径逐字一致，无新增一级菜单、无业务
  交互、无虚构业务数据。
- **顶层包**：严格等于 `ai auth common knowledge project requirement review scm`。
  禁止包 `agent patch mq rag repo pullrequest context assistant finding` 在生产源码中
  零命中（测试目录下的 `agent/fixture` 是 ArchUnit 的**负向 fixture**，由
  `DO_NOT_INCLUDE_TESTS` 排除在生产扫描之外，属有意设计）。
- **未引入**：Agent、Patch、MQ/Outbox、Redis、第二 AI runtime、第二 Review Pipeline、
  本地 Git/clone、代码向量库、运行时 DDL。

---

## 3. 验证命令与结果

### 3.1 后端（2026-08-21T02:07Z）

宿主机无 JDK/Maven，使用 Java 21 容器执行仓库内 wrapper：

```bash
docker run --rm --network host -v /root/ForgePilot/backend:/workspace \
  -v /root/.m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace eclipse-temurin:21-jdk ./mvnw -B -ntp verify
```

`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / **exit 0**。
依赖审计：134 个 artifact 无 SNAPSHOT/RC/M/alpha/beta。Spring Boot 4.1.0、
Spring Framework 7.0.8、Hibernate 7.4.1.Final、Flyway 12.4.0、PostgreSQL JDBC
42.7.11、Testcontainers 2.0.5、ArchUnit 1.5.0。

Wrapper 完整性双向验证：全新空 `MAVEN_USER_HOME` 自举成功（exit 0）；把
`maven-wrapper.properties` 的 sha256 改错后 → `mvnw: checksum mismatch`，**exit 1**
并删除坏包。校验值来源可追溯：本地分发包的 sha512 与 Maven Central 官方发布的
`apache-maven-3.9.16-bin.zip.sha512` 逐字符相同。

### 3.2 前端（2026-08-21T02:45Z）

`npm ci` → `npm run lint` → `typecheck` → `test -- --run` → `build`，五条全部 **exit 0**。
测试 8 个（此前 5 个）。构建产物 `index.html 0.48 kB` / `CSS 4.56 kB` / `JS 86.34 kB`。

### 3.3 评测（2026-08-21T03:2xZ，主会话亲自复跑）

```
--validate-corpus  -> CORPUS VALID                                   exit 0
--selftest         -> SELFTEST OK (30 checks)                        exit 0
--manifest ... --runs evaluation/reference-runs                      exit 0
--compare-report   -> REFERENCE MATCH                                exit 0
--guard-no-holdout --root .  -> HOLDOUT GUARD OK                     exit 0
```

快速集恰好 12 例唯一 case、全部 `split=development`、source commit
`96137dd3b43e14c5e8881c99688663afd979cf4e`，与 `design.md` §6.2 冻结清单逐字一致；
`evaluation/cases/` 无多余目录。**未运行任何 holdout 用例，仓库与执行记录中无 holdout
产物。**

### 3.4 Compose smoke（2026-08-21T02:5xZ）

两次全新卷冷启动，project 名 `forgepilot-phase1-final-e` / `-f`，均 **exit 0**。
断言覆盖：静态契约（三服务、mem_limit 512/768/64 MiB、postgres 按 digest 钉死、
单一命名卷、`/api` 前缀保留）、三容器 healthy、后端与经前端代理的健康端点均 UP、
默认 profile 下 `/actuator/metrics` **404**、PG ≥ 15、pgvector 距离 = 1、Flyway V1
success、**业务表数 = 0**。两次运行结束后主机无任何 forgepilot 容器/卷/网络残留。

### 3.5 CI

`ci.yml` YAML 解析通过，四个 job（backend / frontend / evaluation / compose-smoke）
依赖关系正确，引用的 7 个脚本与文件全部存在，CI 的 npm 步骤与 `package.json`
scripts 一一对应，不使用任何仓库 secret 或外部 AI/SCM 凭据。
**未推送，Actions 从未真实运行**——见第 7 节遗留问题。

---

## 4. 4 GB 容量结论

**权威运行：`20260821T030713Z-53877bba63ce`，结论 PASS，19/19 闸门通过。**

主会话对原始数据的独立复算（不采信 `summary.md`）：

| 指标 | 实测 | 契约 |
|---|---|---|
| 基线样本 / 窗口 | 21 / 300 s | ≥ 5 min |
| 稳定样本 / 窗口 | 17 / 240 s | ≥ 17 且 ≥ 240 s |
| 采样间隔 min/中位/max | 基线 15/15/15 s，稳定 15/15/15 s | 每 15 秒 |
| 稳定窗口 `MemAvailable` 最小值 | 2,451,524 kB = **2.338 GiB** | ≥ 1 GiB |
| `SwapUsed` 基线中位 → 稳定 max | 18,672 → 18,672 kB，**增长 0** | ≤ 64 MiB |
| kernel OOM | 2,940 字节真实内核日志，OOM 关键词 0 命中，stderr 空 | 无 OOM |
| Metaspace used / max | 70,037,552 B / 134,217,728 B（= 配置的 128 MiB） | 有实值 |
| heap max | 389,283,840 B（= `-Xmx384m`） | 有实值 |
| `compose.sha256` | `2dd6d1d4…` = 仓库当前 `compose.yaml` | 一致 |
| 现有服务 | `cpa-manager-plus` / `cli-proxy-api` 的 Running、RestartCount、**StartedAt** 前后完全相同 | 不受影响 |

**结论边界**：该证据只支持 Phase 1 **空栈短时（4 分钟稳定窗口）**容量结论，
**不外推长期稳定性，也不代表 Phase 6 Review 负载**。5+2+4 分钟的缩短协议由用户于
2026-08-20 明确批准，并已同步写入 `IMPLEMENTATION-PLAN.md`。

**重跑的必要性与前四次运行的处置**：五次历史运行全部保留。此前唯一记录为 PASS 的
`074544Z` **不能支持 AC10**：其采样间隔为基线 17/18/19 s、稳定 19/20/21 s，违反批准的
15 秒契约；且它测量的 `compose.sha256 = 121a6e94…` 已不是当前配置。记录为 FAIL 的
`092008Z` 反而符合 15 秒契约——证据与结论是反过来的。该情况已在
`evidence/capacity/README.md` 逐条更正，`092008Z` 保持其 FAIL 记录，未被回溯提升。

**重跑前修正的三处采集/判定缺陷**（否则新的 PASS 会继承空心闸门）：

1. `journalctl --since` 被传入 ISO-8601 `...Z` 时间戳，journalctl 拒绝解析；`2>&1`
   把这条错误写进 `kernel-after.log` 本身，`|| true` 吞掉失败。结果 `kernel_oom` 判定
   在检索一段 48 字节的错误文本，**在前四次运行中恒为绿**。现改用 `%F %T` 格式、
   stderr 分流到 `kernel-*.err`、采集不到时写入 `KERNEL_JOURNAL_UNAVAILABLE` 并判**失败**。
2. Metaspace 从未作为标量采样，而 `validation.md` §9 要求其有实值。已补采。
3. `existing_services` 只比较 `(Running, RestartCount)`，而 `compose up` 重建容器不会
   递增 `RestartCount`。已纳入 `State.StartedAt` 比较。

---

## 5. 跨项目隔离、权限与关键失败路径

Phase 1 **不实现**权限、登录或跨项目数据，因此无可测的隔离用例——这是范围结论，不是
缺口。本阶段能证明的失败路径：

- **凭据 fail-closed**：`application.yml` 的 `spring.datasource.password` 为
  `${FORGEPILOT_DB_PASSWORD}`，**无兜底默认值**，缺失时应用启动失败而不是以弱口令静默
  启动。（修复前为 `${FORGEPILOT_DB_PASSWORD:forgepilot}`。）
- **构建供应链**：wrapper 分发包校验失败时 exit 1 并删除坏包，已用错误校验值实证。
- **架构规则非恒真**：四条规则由注入生产类的反证探针实证会失败。
- **容量测量不误伤宿主**：现有服务的 `StartedAt` 参与判定。
- **证据无凭据泄漏**：容器 inspect 证据按 allowlist 采集，`Config.Env` 从不保留；
  rendered Compose 用 `--no-interpolate` 采集，保留变量表达式而非解析后的值。

---

## 6. Legacy 资产使用

严格按 `docs/v2/LEGACY-MIGRATION-MATRIX.md`，固定参考提交
`96137dd3b43e14c5e8881c99688663afd979cf4e`：

| 资产 | 矩阵判定 | 实际使用 |
|---|---|---|
| `evaluation/tools/score.py` | KEEP TOOL / ADAPT | 迁入并适配为 V2 envelope；先记录迁入前的 Legacy selftest 基线（`evidence/legacy-scorer-selftest.md`，sha256 与自测结果均已复核），再删除 Agent/Patch/双运行时/旧后端 report 字段 |
| `evaluation/cases` + manifest | REWRITE / KEEP DATA | 只复制 12 个 development fixture 与对应 manifest 条目 |
| 32 个 Legacy Flyway migrations | DROP | 未继承，从干净 `V1__foundation.sql` 起步 |
| Agent / Patch / MQ / sandbox / model-service | DROP | 未进入 |

未整包复制任何 Legacy 包，未继承其迁移历史或周边架构。

---

## 7. 决策、风险、回滚与遗留问题

### 新决策

**无新增 `D0xx` 决策。** 视觉方向属纯视觉决策，按 `design.md` §5.2 只记录在
`.trellis/spec/frontend/`，不占用 `DECISIONS.md` 编号。容量协议由 30 分钟缩短为
5+2+4 分钟是用户于 2026-08-20 的明确批准，已同步进 `IMPLEMENTATION-PLAN.md`。

### 遗留问题（如实记录，不含糊）

1. **CI 从未真实运行**（AC9 唯一缺口）。能证明的是 CI 调用的命令与本地执行的是同一组
   且全绿，不能证明 GitHub Actions runner 上会绿。首次推送后需确认四个 job。
2. **后端无 HTTP 层健康测试**。`FoundationDatabaseTest` 用 `webEnvironment = NONE`，
   `/actuator/health/readiness`（容器 healthcheck 使用的路径）只由 Compose smoke 与容器
   healthcheck 覆盖，后端单测闸门本身不设防。判断为重复验证，未新增测试。
3. **ArchUnit 的两个已知盲区**：规则不约束 feature 内部子包深度（未来
   `project.domain.infrastructure.web` 这类四层脚手架能通过）；Repository 仅按类名后缀
   识别（`ProjectRepositoryImpl`、`ProjectDao` 会漏）。Phase 1 无业务类，无实际影响，
   列为 Phase 2 引入业务代码前的加固项。
4. **容量证据体积**：每次运行保存 7 天完整内核日志（约 2.2 MB × 5）。建议后续改为
   OOM 过滤摘录 + 全量日志哈希。
5. **前端 artifact 的两个研究材料缺陷**：视口切换按钮改的是预览框宽度而非容器查询，
   不触发方向稿的响应式断点；方向 A 的字体描述与实现相反（README 写"无衬线正文 +
   衬线标题"，实际是衬线正文）。用户已选 B，不影响结论，但比较材料的准确性打了折扣。
6. **契约 JSON Schema 未被强制**：`evaluation/contracts/*.schema.json` 无程序加载，
   `score.py` 用手写校验。今日四份产物对 Schema 校验 0 错误，但无回归护栏。
7. **本次容量运行在未提交的工作树上执行**，`git-head.txt` 记录的仍是 `53877bb`，
   dirty 清单由 `git-status.txt` 存证。这与前五次运行口径一致，且符合
   `validation.md` §11「提交与验收前保持 `in_progress`」的既定顺序。

### 回滚

backend / frontend / evaluation 三个切片可按独立文件组回滚，互不影响。运行态回滚只
针对 `forgepilot-phase1-*` project 及其命名卷，不触碰用户现有容器与 systemd 服务。
视觉方向如需更换，`tokens.css` 与两份 spec 是唯一落点，比较 artifact 可整体重做。

### Phase 2 前置条件

1. 推送后确认 CI 四个 job 全绿。
2. 进入下一阶段前先写 `DECISIONS.md` 的 **D012（Phase 批次化授权）** 并相应修改
   `IMPLEMENTATION-PLAN.md` §统一授权闸门，单独成一个 `docs:` 提交。
3. 引入业务类的同时补齐 ArchUnit 的子包深度与 Repository 识别规则（遗留问题 3）。
4. `common.web` 错误契约落地时填写 `.trellis/spec/backend/error-handling.md` 与
   `logging-guidelines.md`（当前如实标注为空）。
5. **holdout 仍然锁定到 Phase 8**，配置冻结后只跑一次。

---

## 8. 子代理分工与主会话核对

全部子代理均为不重叠文件范围，无一被允许提交、推送或删除未授权文件。主会话对每个
代理的结论都回到源文件核对，未采信总结。

| 代理 | 范围 | 产出 | 主会话核对结果 |
|---|---|---|---|
| 后端/架构审查（只读） | `backend/**` | AC1–AC4 全部 PASS + 10 条 finding | 复核工作树未被改动、surefire 时间戳为本次复跑、抽查 5 条 finding 全部属实 |
| 前端契约审查（只读） | `frontend/**`、前端 spec、视觉 artifact | AC6 PASS、AC7 部分、21 条 finding | 字节级复核三条阻断项全部属实；**推翻其 F4**（nginx `/api` 前缀语义"互斥"系误报，`ARCHITECTURE.md` §2.4 规定后端 REST 路径本身带 `/api`） |
| 评测/容量审查（只读） | `evaluation/**`、`evidence/capacity/**` | AC8 PASS、AC10 两条阻断 | 独立复算两次运行的采样间隔与 kernel 闸门，**两条阻断均属实**，据此决定重跑 |
| 前端修复 | 同前端审查范围 | 13 项修复 | 逐项核对文件 + 独立复跑四条闸门（8 测试全绿） |
| 后端 spec 沉淀 | `.trellis/spec/backend/**` | 6 份文档 | 占位符清零、引用而非复述 ARCHITECTURE、JVM 包络记录与实际四处文件逐字一致 |

主会话直接执行的部分：根级集成文件审查、后端 5 项修复及其双向验证、容量脚本三处缺陷
修复、两次 Compose smoke、完整容量协议、评测闸门复跑、本文件。
