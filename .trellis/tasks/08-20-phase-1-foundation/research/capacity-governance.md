# Phase 1 容量、空栈与治理研究

> **2026-08-20 用户批准的执行修订**：本研究最初建议并按当时权威计划写成 30 分钟稳定窗口。用户随后明确要求将本任务的稳定采样缩短为 4 分钟。当前执行合同以更新后的 `prd.md`、`design.md`、`implement.md`、`validation.md` 和 `docs/v2/IMPLEMENTATION-PLAN.md` 为准：5 分钟基线 + 2 分钟预热 + 4 分钟稳定窗口，每 15 秒至少 17 组。下文保留原研究建议作为历史推理，不再作为本任务验收门槛；最终结论不得外推为长期稳定性证明。

## 1. 结论

Phase 1 的容量验收应拆成两个互不替代的门禁：

1. **PR/CI 空栈门禁**：在隔离的临时 Docker Compose project 与空卷上，验证构建、PostgreSQL 15+、pgvector、Flyway、后端健康、前端静态服务以及“无业务表/无业务服务”。该门禁应快速、确定、每次可重跑。
2. **目标 4 GB 主机容量门禁**：使用与部署相同的镜像、Compose 和显式 JVM/PostgreSQL 上限，在保留 `cpa`、`cpa-manager-plus`、`cloudflared` 等既有常驻服务的前提下，先采 5 分钟宿主机基线，再完成 2 分钟预热和至少 30 分钟空载稳定采样。原始数据、命令、配置和结论全部落在本 Phase 1 Trellis 任务下。

CI 的几分钟空栈 smoke 不能代替 30 分钟目标机实测；当前宿主机的一次瞬时快照也不能代替完整容量结论。Phase 1 只测空底座，不运行 Review Engine、不运行真实模型、不运行 holdout，也不决定 Phase 6 的 Review 并发上限。

建议把容量运行结论固定为 `PASS / FAIL / INVALID` 三态：环境噪声、采集缺失或配置漂移属于 `INVALID`，必须重跑，不能解释成通过或失败。

---

## 2. 已确认事实

### 2.1 权威契约

| 已确认事实 | 证据 | 对 Phase 1 的约束 |
|---|---|---|
| Phase 1 目标栈是单 Spring Boot 模块化单体、Vue 3、PostgreSQL 15+ 与 pgvector，并包含 Flyway、Testcontainers、ArchUnit、基础 CI、前端脚手架、评测骨架和 4 GB 容量基线。 | `docs/v2/IMPLEMENTATION-PLAN.md` Phase 1；`AGENTS.md` Current execution gate | 容量与 smoke 是 Phase 1 必交付项，不是可延期运维工作。 |
| 目标 4 GB 机必须让 PostgreSQL、空后端、前端静态服务与现有常驻服务连续稳定至少 30 分钟；记录 RSS/PSS、JVM heap/direct memory、PostgreSQL 参数、可用内存、swap/OOM；空载后至少保留 1 GB 可用内存。 | `docs/v2/IMPLEMENTATION-PLAN.md` Phase 1 目标产物与退出条件 | 只报 `docker stats` 或只截一张 `free -h` 不足以验收。 |
| 4 GB 部署必须显式限制 JVM 与 PostgreSQL；内存受限部署的 Review 并发为 1。 | `docs/v2/ARCHITECTURE.md` §7.2 | Phase 1 先验证空栈上限；并发 Review 的最终 1/2 冻结仍属于 Phase 6。 |
| PostgreSQL 最低版本 15 是硬依赖，Compose、Testcontainers、部署环境必须一致。 | `docs/v2/ARCHITECTURE.md` §7.1；`docs/v2/PRD.md` §8；`docs/v2/DECISIONS.md` D010 | smoke 必须实际查询服务器版本，不能只检查镜像标签。 |
| Phase 1 禁止登录、项目、成员、需求、知识、SCM、Review、Finding、业务实体、业务迁移和业务 UI。 | `docs/v2/IMPLEMENTATION-PLAN.md` Phase 1 明确禁止；`backend/README.md` | 空库中除 Flyway 元数据外不得出现业务表；Compose 不得带 RabbitMQ、Redis、Sandbox、观测全家桶等后置组件。 |
| Phase 1 评测只建契约/确定性评分器骨架，从 development 26 例中选 10–15 例快速集；不得调用尚不存在的 Review Engine，不得运行 holdout。 | `docs/v2/IMPLEMENTATION-PLAN.md` Phase 1；`evaluation/README.md` | 容量采样期间不运行评分或模型；CI 只验证评分器可确定性重算快速集。 |
| 每个 Phase 是独立人工闸门；任一退出条件未通过不得归档为 completed，也不得开始 Phase 2。 | `docs/v2/IMPLEMENTATION-PLAN.md` 统一授权闸门 | 容量失败、证据不全或 smoke 红灯都必须阻断 Phase 1 完成。 |

当前仓库的实况也已确认：`backend/README.md`、`frontend/README.md` 仍是占位入口，仓库没有应用构建清单、Dockerfile、`deploy/` Compose 或 CI workflow 可供执行。因此本文件的 smoke/容量命令是 **Execute 阶段的协议草案**，本轮没有声称它们已运行；第一次真实运行必须在这些文件落地后以新的 run id 记录。

### 2.2 Trellis 治理实况

以下是对当前 Trellis 实现的直接检查，不是仅依据工作流文字：

- `.trellis/workflow.md` §1.3 要求 `implement.jsonl` 与 `check.jsonl` 各至少有一个真实条目；示例行不算 ready。
- `.trellis/scripts/task.py::cmd_start` 只解析任务、设置 session pointer，并把 `planning` 改为 `in_progress`；它**不检查** `prd.md`、`design.md`、`implement.md`、用户确认或 JSONL 实际条目。
- `.trellis/scripts/common/task_context.py::_validate_jsonl` 会跳过无 `file` 字段的 seed 行，并在真实条目为 0 时仍以 0 error 返回。
- 2026-08-20 对当前任务执行 `python3 .trellis/scripts/task.py validate ...` 的实测结果为 `implement.jsonl: ✓ (0 entries)`、`check.jsonl: ✓ (0 entries)`；`list-context` 同时明确显示二者只有 seed row。
- 当前宿主机没有 `python` 命令，Trellis 命令应使用 `python3`；这是环境事实，不应通过修改系统别名来掩盖。

因此，Phase 1 必须有主会话人工 pre-start gate；不能把 `task.py validate` 或 `task.py start` 的退出码误当作规划已经完备。

### 2.3 当前目标机瞬时基线（2026-08-20，只作前置证据）

本次研究以只读命令检查了当前主机。该快照证明目标环境可测，但**不是** Phase 1 容量 PASS：

| 项目 | 当前快照 |
|---|---:|
| CPU | 4 vCPU，x86_64 |
| `MemTotal` | 4,005,188 kB（约 3.82 GiB） |
| `MemAvailable` | 3,154,088 kB（约 3.01 GiB） |
| Swap | 4,194,300 kB，总已用约 19 MiB |
| `cpa-manager-plus` | `docker stats` 约 90.96 MiB；memory cap 512 MiB；主进程 PSS 约 41.6 MiB |
| `cli-proxy-api` | `docker stats` 约 45.8 MiB；memory cap 256 MiB；主进程 PSS 约 43.1 MiB |
| `cloudflared` | systemd active；`MemoryCurrent` 约 33.4 MiB；主进程 PSS 约 29.8 MiB；未配置 `MemoryMax` |
| 最近 OOM | `journalctl -k --since '7 days ago'` 未发现 OOM 标记 |

使用的只读命令包括：

```bash
awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/{print}' /proc/meminfo
swapon --show --bytes
docker compose ls --format json
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.PIDs}}'
systemctl show cloudflared -p MemoryCurrent -p MemoryPeak -p MemoryMax -p NRestarts
journalctl -k --since '7 days ago' --no-pager
```

注意：`cloudflared` 是 systemd 服务，不会出现在 `docker ps` / `docker stats` 中；容量脚本必须同时枚举 Docker 与非 Docker 常驻服务。

### 2.4 Legacy / 历史证据能复用什么

先按 `docs/v2/LEGACY-MIGRATION-MATRIX.md` 检查边界后，得到以下结论：

- 历史 `ff63adc:deploy/docker-compose.yml` 的服务 memory limit 合计约 4,016 MiB，且包含 RabbitMQ、Sandbox Runner、OTel、Prometheus、Alertmanager、Grafana 等 V2 已 DROP 的组件；该 Compose 注释还写“11 个服务”，实际文件只有 10 个服务。它只能证明“必须实测且不能信陈旧摘要”，不能作为 V2 模板整份迁入。
- 历史 `ff63adc:.trellis/spec/guides/deployment-and-observability.md` 可复用的治理思想是：可选配置要可独立回滚，指标/队列名必须对实际运行值，不能凭文档猜测。完整观测栈本身已被 V2 明确排除。
- 历史 P8 在缺 Docker/真实凭据时，把五臂全部记录为 `notRun=38` 和 `BLOCKED BEFORE RUN`，没有用 H2/Mock 冒充真实结果。证据：`96137dd:.trellis/tasks/archive/2026-08/08-17-p8-experiment-defense/eval-runs/preflight-2026-08-18/{matrix.md,run-metadata.json}`。容量门禁应沿用同一诚实口径：目标机未跑成就是 `INVALID/FAIL`，不能拿 CI smoke 替代。
- 历史 `.trellis/spec/guides/demo-assets-and-claims.md` 要求对外能力/数字必须指向已存在的实测产物。Phase 1 的“可在 4 GB 运行”声明必须链接本任务的原始容量证据。
- `docs/v2/LEGACY-MIGRATION-MATRIX.md` 明确把 `deploy/observability/**` 判为 `DROP（V1 部署）/REFERENCE`，把旧评测工具/数据判为选择性 KEEP/ADAPT；不得因做容量测量重新带回观测全家桶或第二评测链。

---

## 3. 建议的 Phase 1 初始资源包络

以下数值是**规划建议，不是现有架构事实**。实现设计可以在给出理由后调整，但必须在第一次容量运行前冻结，并把最终值写进运行 metadata。禁止通过取消上限取得表面通过。

| 服务 | 建议初始容器内存上限 | 建议进程参数 | 理由 |
|---|---:|---|---|
| PostgreSQL + pgvector | 512 MiB | `shared_buffers=128MB`、`work_mem=4MB`、`maintenance_work_mem=64MB`、`autovacuum_work_mem=64MB`、`max_connections=30` | 与历史实测起点一致但不继承旧栈；限制乘法型 `work_mem × connection` 风险。 |
| 空 Spring Boot 后端 | 768 MiB | `-Xms128m -Xmx384m -XX:MaxDirectMemorySize=128m`；容器上限承担 metaspace、code cache、线程栈和其他 native 余量 | 显式 heap/direct 上限比仅用百分比更可复现；仍留约 256 MiB native 余量。 |
| Vue 构建后的静态服务 | 64 MiB | 单一轻量静态服务器；不在目标机运行 Node dev server | 静态运行时不应携带 Node 构建进程。 |

ForgePilot 三服务建议上限合计 1,344 MiB。加上现有两个容器的已配置 memory cap 768 MiB，总显式容器上限约 2,112 MiB，仍为内核、页缓存、Docker、cloudflared 和 1 GiB `MemAvailable` 目标保留名义空间。

约束与注意事项：

- 构建镜像、`npm ci`、Maven 编译和 Testcontainers 测试不计入运行容量窗口；目标机容量采样使用预构建镜像和 `docker compose up --no-build`。若答辩要求目标机本地构建，另做“构建容量”研究，不混入空载运行结论。
- PostgreSQL 的 `effective_cache_size` 是 planner hint，不是硬内存分配；要记录，但不能把它当作实际上限。
- `cloudflared` 当前无 `MemoryMax` 是宿主机风险，但修改外部常驻服务不属于 Phase 1 ForgePilot 范围。容量不能靠停掉或重配它来通过；是否另行加 systemd 上限是独立运维决策。
- 镜像标签必须固定到明确 PostgreSQL/pgvector 版本，不能用 `latest`。Compose、Testcontainers 与 CI 必须使用同一 PostgreSQL major/pgvector 组合。
- Phase 1 不加入 RabbitMQ、Redis、独立向量服务、Sandbox、Prometheus/Grafana 或第二静态反向代理层来“模拟未来生产”。

---

## 4. 4 GB 容量测量协议

### 4.1 运行身份与证据目录

每次运行使用唯一 UTC run id，并写入：

```text
.trellis/tasks/08-20-phase-1-foundation/evidence/capacity/<run-id>/
├── metadata.json
├── commands.log
├── compose.rendered.yaml
├── images.json
├── host-baseline.csv
├── host-steady.csv
├── containers-baseline.jsonl
├── containers-steady.jsonl
├── processes-baseline.csv
├── processes-steady.csv
├── jvm-steady.jsonl
├── postgres-settings.csv
├── health.csv
├── docker-events.log
├── kernel-oom.log
├── service-logs.txt
└── summary.md
```

`metadata.json` 至少包含：run id、Git SHA、dirty paths、开始/结束 UTC、内核、CPU、`MemTotal`、swap 配置、Docker/Compose 版本、Compose project name、Compose 文件 SHA-256、镜像 tag+digest、JVM flags、PostgreSQL image/server/extension 版本、PostgreSQL 内存参数、既有常驻服务及其限制、采样间隔、预热/稳定窗口、最终状态和失败原因。

运行原始目录一旦形成，不手工改 CSV/JSONL。若解析器有 bug，保留原 run 为 `INVALID`，修复后生成新的 run id。

### 4.2 有效运行前置条件

以下任何一项不满足，都不得开始计时：

- Git SHA 与准备验收的提交一致；dirty 文件清单已保存并确认不影响镜像/配置。
- 使用生产 Compose 与预构建镜像；`docker compose config --quiet` 通过。
- ForgePilot Compose 只有 `postgres`、`backend`、`frontend`（静态服务）及经任务设计明确批准的最小 edge 形态；无后置组件。
- `cpa-manager-plus`、`cli-proxy-api` 与 `cloudflared` 保持运行，不为制造余量而停止。
- 没有正在执行的 Maven/npm/Docker build、全量测试、评测或其他一次性高负载任务。若主机不可避免地有非 ForgePilot 波动，标记 `INVALID` 并另择窗口。
- 目标机 `MemTotal`、swap、内核和 Docker 信息已采集；不能只写“4 GB”。
- 容量运行使用唯一 Compose project name 与 task-owned 空卷，防止覆盖其他项目。
- 时钟为 UTC 且采样脚本能写入单调 elapsed seconds；最终以 elapsed time 判断 30 分钟，不只按样本数猜测。

禁止为得到好看数据执行 `drop_caches`、关闭 swap、清理内核缓存、停止既有服务或修改宿主机 overcommit。测量应反映真实常驻环境。

### 4.3 准备命令

实现阶段应把以下命令封装为一个透明的采集脚本，但脚本输出必须保留底层命令和原始值：

```bash
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short=12 HEAD)"
EVIDENCE_DIR=".trellis/tasks/08-20-phase-1-foundation/evidence/capacity/$RUN_ID"
PROJECT="forgepilot-cap-${RUN_ID,,}"

mkdir -p "$EVIDENCE_DIR"
date -u +%FT%TZ
git rev-parse HEAD
git status --short
uname -a
nproc
docker version
docker compose version
free -b
swapon --show --bytes
docker compose ls --format json
docker ps --format '{{json .}}'
systemctl show cloudflared -p ActiveState -p MemoryCurrent -p MemoryPeak -p MemoryMax -p MemorySwapMax -p NRestarts

docker compose -p "$PROJECT" -f deploy/docker-compose.yml config --quiet
docker compose -p "$PROJECT" -f deploy/docker-compose.yml config
docker compose -p "$PROJECT" -f deploy/docker-compose.yml build
docker compose -p "$PROJECT" -f deploy/docker-compose.yml images --format json
```

`build` 在正式采样前完成；若镜像从 registry 拉取，则用 `pull` 替换。采样启动必须使用：

```bash
docker compose -p "$PROJECT" -f deploy/docker-compose.yml up -d --no-build --wait
```

### 4.4 采样阶段

#### A. 既有常驻服务基线：5 分钟

- ForgePilot 尚未启动。
- 每 15 秒采一组，共至少 20 组；同时记录绝对 UTC 与 elapsed seconds。
- 记录宿主机、现有容器和 cloudflared 的内存，建立增量基线。

每个宿主机样本至少执行/提取：

```bash
awk '/^(MemTotal|MemFree|MemAvailable|Buffers|Cached|SReclaimable|SwapTotal|SwapFree):/{print}' /proc/meminfo
cat /proc/pressure/memory
vmstat 1 2
```

每个容器样本保留机器可读原文：

```bash
docker stats --no-stream --format '{{json .}}'
docker inspect --format '{{json .State}} {{json .HostConfig.Memory}} {{json .HostConfig.MemorySwap}} {{json .RestartCount}}' <container>
```

RSS/PSS 不可只取容器 init PID。每个相关容器先用：

```bash
docker top <container> -eo pid
```

再对所有 PID 读取：

```bash
awk '/^(Rss|Pss|Pss_Anon|Pss_File|Swap):/{print}' /proc/<pid>/smaps_rollup
```

`cloudflared` 使用 `systemctl show` 与 `/proc/<pid>/smaps_rollup` 同样采样。

#### B. 启动和预热：不计入 30 分钟

- 启动 ForgePilot 空栈，最多等待 180 秒全部 healthy。
- 执行空栈 smoke（见 §5）。
- 用 `/actuator/health` 与前端根页面做 2 分钟低频预热；每 5 秒各请求一次，只消除 JVM 类加载/JIT 和静态文件首次访问，不制造业务负载。
- 预热期间任何 OOM、重启、health fail 或迁移失败均直接 `FAIL`。

#### C. 空载稳定窗口：至少 30 分钟

- 预热完成后才记 `steady_start`。
- 每 15 秒采宿主机/容器/RSS/PSS/JVM/PostgreSQL状态；至少 121 组且 `steady_end - steady_start >= 1800s`。
- 每 30 秒检查 PostgreSQL、后端和前端健康；所有探针都带时间戳和 HTTP/exit status。
- 全窗口持续监听 Docker `oom/die/restart` 事件；结束时按 `steady_start` 导出内核 OOM 日志。
- 采样程序自身不得使用 Java/Node 或引入 Prometheus/Grafana；使用 shell、`/proc`、Docker CLI、curl 和 psql 即可。

建议健康命令：

```bash
curl -fsS "$BACKEND_URL/actuator/health"
curl -fsS "$FRONTEND_URL/"
docker compose -p "$PROJECT" -f deploy/docker-compose.yml exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

JVM 每个样本保存 Micrometer/Actuator 原始 JSON，至少包含：

```bash
curl -fsS "$BACKEND_URL/actuator/metrics/jvm.memory.used"
curl -fsS "$BACKEND_URL/actuator/metrics/jvm.memory.committed"
curl -fsS "$BACKEND_URL/actuator/metrics/jvm.memory.max"
curl -fsS "$BACKEND_URL/actuator/metrics/jvm.buffer.memory.used?tag=id:direct"
curl -fsS "$BACKEND_URL/actuator/metrics/jvm.buffer.count?tag=id:direct"
```

这些 metrics 只需绑定宿主机 loopback/容器内部诊断面；不得经公开静态入口暴露全部 Actuator。若设计不用 Actuator metrics，则必须提供等价的 `jcmd`/JMX 原始证据，并记录诊断工具对镜像的影响；不能只依据 `-Xmx` 推算实际 heap/direct 使用。

PostgreSQL 在运行前后各导出一次设置，至少包含：

```sql
SELECT current_setting('server_version_num') AS server_version_num;
SELECT extversion FROM pg_extension WHERE extname = 'vector';
SELECT name, setting, unit, source
FROM pg_settings
WHERE name IN (
  'shared_buffers', 'work_mem', 'maintenance_work_mem', 'autovacuum_work_mem',
  'temp_buffers', 'max_connections', 'wal_buffers', 'effective_cache_size',
  'max_worker_processes', 'max_parallel_workers', 'max_parallel_workers_per_gather',
  'huge_pages', 'jit'
)
ORDER BY name;
```

结束时导出：

```bash
docker compose -p "$PROJECT" -f deploy/docker-compose.yml ps --format json
docker compose -p "$PROJECT" -f deploy/docker-compose.yml logs --no-color --timestamps
journalctl -k --since "$STEADY_START_UTC" --no-pager
```

任务拥有的空栈可在证据采集完成后清理：

```bash
docker compose -p "$PROJECT" -f deploy/docker-compose.yml down --volumes --remove-orphans
```

执行前必须验证 `$PROJECT` 以 `forgepilot-cap-` 开头；只删除本次唯一 project 的空卷，不触碰 `cpa`、`cpa-manager-plus` 或其他持久卷。

### 4.5 判定规则

#### 权威契约直接要求的硬门

- 稳定窗口连续不少于 1,800 秒。
- PostgreSQL、后端、前端全程健康，无容器/进程意外退出或重启。
- 无 Docker OOM、systemd OOM 或内核 OOM kill。
- 已保存 RSS/PSS、JVM heap/direct、PostgreSQL 参数、`MemAvailable`、swap/OOM 原始证据。
- 空载完成后 `MemAvailable >= 1,048,576 KiB`。

#### 建议在 Phase 1 计划中采纳的更严格门

- 不只看最后一点：30 分钟稳定窗口内每个有效样本均满足 `MemAvailable >= 1,048,576 KiB`，避免挑选最好的一帧。
- 相对预热结束时，`SwapUsed` 增量不超过 64 MiB，且 `vmstat` 不出现连续 3 个样本的 swap-in/swap-out；宿主机已有少量历史 swap 不能直接判失败。
- 任一 ForgePilot 容器峰值 memory usage 不超过其 hard limit 的 85%；超过说明配置余量不足，即使尚未 OOM 也不进入下一阶段。
- 后端或 PostgreSQL 的“第 25–30 分钟 PSS 中位数”不得比“第 5–10 分钟 PSS 中位数”高 64 MiB 以上且仍持续上升；否则按潜在缓存失控/泄漏处理，不能称为稳定。
- health 探针成功率 100%；不允许通过自动重启掩盖启动/运行失败。
- `metadata.json`、raw 文件或命令日志缺任一关键项，整次运行标记 `INVALID`，不得人工补数字。

`summary.md` 必须列出每一条判据的实际值、结果和证据文件，而不是只写“30 分钟正常”。

---

## 5. 空栈 smoke 与 CI 门禁

### 5.1 单一 smoke 契约

本地、CI 和目标机必须调用同一个空栈 smoke 脚本/命令集合，避免三套口径。建议接口：

```text
scripts/smoke-empty-stack.sh \
  --compose deploy/docker-compose.yml \
  --project <unique-project> \
  --backend-url <loopback-url> \
  --frontend-url <loopback-url> \
  --destroy-volumes
```

脚本实现属于后续 Execute；本研究不创建脚本。它必须非零退出于任一失败，并保存 Compose logs。

### 5.2 每次 cold-start 必查项

1. `docker compose config --quiet` 通过，渲染后的服务 allowlist 与 Phase 1 设计一致，无 dropped dependency。
2. 使用唯一 Compose project 与全新空卷启动；180 秒内全部 healthy。
3. 后端 `/actuator/health` 返回 `UP`；前端根路径返回 200 且实际加载构建产物，不是 Node dev server。
4. 通过 SQL 查询 `server_version_num >= 150000`，不能只看镜像名。
5. `pg_extension` 中存在 `vector`，并执行一次最小 vector 运算：

   ```sql
   SELECT '[1,2,3]'::vector <-> '[1,2,4]'::vector;
   ```

6. `flyway_schema_history` 所有已计划 migration 均 `success=true`，版本/description 与任务计划完全一致。
7. `public` schema 的普通表只允许 `flyway_schema_history`；任何用户、项目、需求、知识、SCM、Review、Finding 表都失败。建议查询：

   ```sql
   SELECT table_name
   FROM information_schema.tables
   WHERE table_schema = 'public'
     AND table_type = 'BASE TABLE'
     AND table_name <> 'flyway_schema_history'
   ORDER BY table_name;
   ```

   Phase 1 期望结果为空；pgvector extension 对象不应被误判为业务表。

8. 检查三个 ForgePilot runtime 的 memory limit 非 0，JVM 与 PostgreSQL 参数和部署文件一致。
9. 日志中无 Flyway failure、panic、OOM、restart loop 或无法连接数据库；不能只凭最终 health 绿灯忽略中途失败。
10. 执行 `down --volumes --remove-orphans` 清理本次唯一 project 后，再用第二个全新 project/卷完整 cold-start 一次。两次都通过才证明“空库可重复启动”。

### 5.3 建议 CI 阶段

```text
backend-unit-architecture
  -> 编译/单元/ArchUnit
backend-postgres-contract
  -> Testcontainers PostgreSQL 15+ + pgvector + Flyway
frontend
  -> lockfile install + typecheck + focused tests + production build
empty-stack-smoke
  -> production Compose config + 两次 fresh-volume cold start
phase-boundary
  -> 服务/schema/source 路径 allowlist，无业务实现/后置组件
```

建议命令形态（具体 wrapper/JDK/Node 版本由 design 冻结）：

```bash
./mvnw -B verify
npm ci
npm run typecheck
npm test -- --run
npm run build
docker compose -p "forgepilot-ci-$CI_RUN_ID-a" -f deploy/docker-compose.yml config --quiet
scripts/smoke-empty-stack.sh --project "forgepilot-ci-$CI_RUN_ID-a" --destroy-volumes
scripts/smoke-empty-stack.sh --project "forgepilot-ci-$CI_RUN_ID-b" --destroy-volumes
git diff --check
```

未决：仓库尚未冻结 Maven/Gradle、JDK、Node、包管理器、CI provider、端口和静态服务器。最终 `design.md` 必须明确选择并把 Docker、CI 与本地 wrapper 版本对齐；不得把上述示例命令当作已批准技术事实。

30 分钟容量门不建议在共享 PR runner 每次运行。CI 应验证容量采集脚本、结果 schema 和空栈 smoke；Phase 1 退出由目标 4 GB 主机的 commit-specific 运行负责。

---

## 6. Phase 1 Trellis 验证清单

### 6.1 `task.py start` 前

- [ ] `task.json.status == planning`，当前 task 指向 `.trellis/tasks/08-20-phase-1-foundation`。
- [ ] `prd.md` 已列范围、非目标、全部 Phase 1 退出条件和禁止业务范围，无 `TBD`。
- [ ] `design.md` 已冻结构建工具/版本、目录、Compose 服务、数据库空 schema、CI、资源上限、证据路径与回滚。
- [ ] `implement.md` 有有序步骤、文件边界、每步验证、视觉选择人工闸门、容量门和 stop points。
- [ ] 用户对最终 `prd.md`、`design.md`、`implement.md` 与验证清单给出明确确认；任务文件记录确认日期和授权只到 Phase 1。
- [ ] `implement.jsonl`、`check.jsonl` 各有至少一个真实 spec/research 条目；不能只有 `_example`。
- [ ] 同时运行 `python3 ... task.py validate` 与 `list-context`；不能只信 validate 的 0 error。
- [ ] 主会话审查所有研究文件实际内容与证据路径，不把子代理总结当作事实。
- [ ] planning 阶段 dirty paths 仅限任务/研究产物；无应用源码、依赖清单、迁移、Compose 或 CI 变更。
- [ ] 只有上述全绿后才执行一次 `python3 .trellis/scripts/task.py start <task-dir>`。

### 6.2 Execute 中

- [ ] `task.json.status == in_progress`；每个实现子任务有不重叠文件范围和验收标准。
- [ ] 主会话逐个检查实际 diff、构建日志和测试输出；不以子代理“已通过”代替复核。
- [ ] Legacy 使用均先引用迁移矩阵分类，只迁最小允许资产；不恢复旧 Compose/观测栈/业务架构。
- [ ] 没有登录、项目、成员、需求、知识、SCM、Review、Finding 实体、表、API 或业务 UI。
- [ ] 无 RabbitMQ/Outbox、Agent、Patch、Sandbox、第二 AI runtime、第二 Review pipeline 或额外一级页面。
- [ ] Testcontainers、Compose、CI 使用 PostgreSQL 15+；选定的 pgvector image/tag/digest 与兼容性证据已写入 `design.md`/运行 metadata（“三处完全相同的镜像 digest”属于建议的加强门，不是当前权威文档的额外硬约束）。
- [ ] 前端视觉方向必须由用户选定后才固化 spec；代理不得替用户选择。
- [ ] 评测只使用 development 快速集，不调用 Review Engine、不读取/运行 holdout。
- [ ] 容量测量只在 production-equivalent 空栈完成后执行，保留既有常驻服务，原始文件不可手改。

### 6.3 Finish / Phase 1 退出

- [ ] `git diff --check` 通过。
- [ ] 后端编译、单元/最小 Spring 集成、PostgreSQL/pgvector Testcontainers、ArchUnit 全绿。
- [ ] 前端类型检查、focused tests、production build 和关键可访问性/`prefers-reduced-motion` 验证全绿。
- [ ] CI 使用同一命令成功；两次 fresh-volume 空栈 smoke 全绿。
- [ ] PostgreSQL 15+、pgvector、Flyway、空 schema、服务/依赖 allowlist 验证全绿。
- [ ] development 快速集评分器可确定性重算；holdout 零运行。
- [ ] 目标机容量 run 为 `PASS`，30 分钟原始数据、命令、镜像/config digest 和 summary 已版本化。
- [ ] 源码和数据库边界扫描确认无业务源码、业务表或 Phase 2 能力。
- [ ] `result.md` 完整写明完成/未完成、偏差、修改范围、命令与结果、产品/架构边界、Legacy 使用、新决策、容量证据、风险和回滚。
- [ ] 任一项未通过时任务保持未完成，不 archive；明确记录 `FAIL/INVALID/notRun`。
- [ ] 提交前主会话展示提交分组、commit message 和未识别 dirty files，等待用户确认；不自动 push。
- [ ] Phase 1 完成后停止。没有单独授权不得创建或启动 Phase 2 实现任务。

---

## 7. 停止条件与回滚

### 7.1 立即停止并回到 Plan

- 权威文档之间对 Phase 1 范围、schema、依赖或验收出现冲突。
- 需要新增运行时依赖、顶层模块、表、一级页面或修改已接受决策才能继续。
- 用户尚未确认最终规划，或 JSONL context 仍 seed-only。
- 发现任务实际需要 Phase 2 业务行为才能让 smoke/容量通过。

处理：停止实现，更新 `prd.md/design.md/implement.md` 或提出 D012+ 决策，重新取得用户确认；不得让代码自行解释冲突。

### 7.2 CI / 空栈立即阻断

- PostgreSQL 实际版本 <15、pgvector 不可用、Flyway 失败或 fresh-volume 第二次启动失败。
- 出现任何业务表、业务 API/UI、禁止服务或未批准 runtime dependency。
- ArchUnit、Testcontainers、类型检查、构建或 smoke 红灯。
- 日志显示 migration/health/restart 问题但最终探针偶然恢复。

处理：不得进入容量测量，也不得把失败项移出 CI。回退到最近一个可冷启动的提交/镜像，修复后从全新卷重跑两次。

### 7.3 容量运行立即 FAIL

- 任一 OOM kill、容器 `OOMKilled=true`、意外 restart/die、PostgreSQL/backend/frontend health failure。
- 预热后或稳定窗口结束时无法保留至少 1 GiB `MemAvailable`；若计划采纳 §4.5 严格门，则窗口内任一样本低于 1 GiB 也失败。
- 无法读取 heap/direct、PostgreSQL 参数、RSS/PSS 或 swap/OOM 关键数据。
- 必须停掉 cpa/cmp/cloudflared、关闭安全能力、取消内存上限或引入额外主机资源才能通过。

处理：保留失败 run 的全部原始证据，停止该唯一 ForgePilot Compose project；不得删除失败证据后重写同一 run。只允许在既有架构内调整 JVM/PostgreSQL/static server 上限和启动配置，形成新 run id 并重新完成完整 5+2+30 分钟协议。若在保留 1 GiB 余量下仍无法装入，Phase 1 判定未满足目标机约束，提交用户裁决，而不是提前扩容或进入 Phase 2。

### 7.4 容量运行标记 INVALID 后重跑

- 采样器中断、时间戳/文件缺失、镜像或 Compose 在运行中变化。
- 同时发生 Maven/npm/build/真实评测、备份或其他不可归因的宿主机高负载。
- 实际运行时长不足 1,800 秒，或原始数据被手工编辑。

处理：不依据该 run 调参或发布容量声明。保留 `INVALID` 证据和原因，清理本次唯一空卷，在稳定窗口重新运行。

### 7.5 回滚形态

- Compose/JVM/PostgreSQL 参数以单独、可审查的部署提交管理；容量调参不与业务代码混合。
- 回滚到最后一个已知可 cold-start 的镜像 digest 与 Compose digest；不是回滚到浮动 tag。
- Phase 1 没有业务数据。CI/容量均使用 task-owned 空卷，失败后只销毁唯一 project 的卷；不得执行广域 `docker system prune` 或删除其他 Compose project。
- Flyway 不做手工反向改表。若 foundation migration 有误，在未发布、无业务数据的 Phase 1 中修正任务提交并重建专属空卷，再从零验证。
- 若回滚仍触碰产品/架构边界，停止并请求新决策，不增加兼容层、兜底服务或第二部署路径。

---

## 8. 尚待 Phase 1 design 冻结的选择

这些选择目前没有权威答案，不能在研究中伪装成已批准事实：

- JDK/Spring Boot、Maven 或 Gradle及 wrapper 版本。
- Node、包管理器、Vue/Vite/Vitest、静态服务器及端口。
- PostgreSQL 15/16 的具体 pgvector 镜像 tag/digest；只确认服务器 major 必须 15+，且 Testcontainers、Compose、部署环境的 PostgreSQL major 统一。是否进一步要求 CI/Compose 复用同一 pgvector 镜像 digest 由 `design.md` 冻结。
- CI provider、job 拆分和缓存策略。
- §3 建议内存上限的最终值，以及是否采纳 §4.5 的 64 MiB swap/增长和 85% cap 严格门。
- JVM 内存采集使用内部 Actuator metrics 还是等价 JMX/jcmd；无论选择哪种都必须提供 heap/direct 实测。
- 容量采集脚本的最终文件名；证据目录和原始字段契约应保持本研究定义的可追溯性。

上述选择必须进入 `design.md/implement.md` 并在 `task.py start` 前由用户确认。它们不授权 Phase 2，也不能改变“空载 30 分钟、至少 1 GiB 可用内存、无 OOM、完整原始证据”的 Phase 1 硬契约。
