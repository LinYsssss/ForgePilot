# Implement：生产加固与清理

> **▶ 2026-08-14 已恢复并跑完 1-5 阶段**（暂停现场档 `research/handoff-2026-08-14.md` 仅留作过程记录）。恢复时 backend `mvn verify` 先红后绿：修掉测试编译缺口与导出端码编码缺陷各一处，终态 658 通过 / 0 失败 / 6 跳过（既有 Testcontainers 门控）。
>
> **落库状态**：`a2f1e0c`（1-5 阶段成果，一批）+ `327920d`（Outbox 调度器上下文泄漏修复 + 规范沉淀），已 ff-only 并入 `main` 并推送；分支 CI 三 job 全绿（verify / supply-chain / nginx-headers），supply-chain 由 `.trivyignore` 转绿已实测生效。§6 分支清理**未执行**（远程删除不可逆，留待确认）。
>
> **剩余**：步骤 6（冗余清理）、7（注释中文化 252 文件）、8（尾段，等墨境步骤 6/7）、9（收尾）。服务器侧待验收：V28 真实迁移、TLS 走通、告警触发、grafana 看板（清单见 `research/server-acceptance-checklist.md`）。
>
> **CI supply-chain 修法与交接档所述不同**：交接档拟用 `docker:29.6.2-cli → 29.7.2-cli` 修两条 HIGH，实测该前提不成立——docker/cli `29.x` 分支 `ARG GO_VERSION=1.26.5`，最新 tag v29.7.2 仍是 1.26.5，而修复需 ≥1.26.6（master 已 bump，将随下个特性版本进入镜像）。改为：仍升 29.7.2 取其它修复，另加仓库根 `.trivyignore` 以带到期日（exp:2026-11-01）的显式抑制覆盖这两条，并把 `--ignorefile` 挂载接进 `deploy/scan-images.sh`（容器化 trivy 看不见宿主 ignore 文件）。理由：门禁长期红会训练所有人忽略它；且该 CLI 二进制只与本机 docker.sock 通信，idna/dnsmessage 路径不可达。

> 每阶段独立提交=回滚点；提交遵循仓库既有 message 风格、作者本人署名、无 AI 标记。全程硬边界：不触碰受保护资产；墨境步骤 6/7 落库前不触碰 `frontend/`。
> （2026-08-14 用户新规则覆盖：只做必要检查、成果合批一笔提交、后续全部并入 main 单分支工作。）

0. [x] **前置核实**：确认 backend schema 演进机制（flyway/liquibase/ddl-auto，跟随既有模式）；精确普查 sandbox-runner/model-service 注释基数（排除 venv/生成物）；核对 RabbitMqConfig 实际队列名供告警规则用。（实况 2026-08-14：**Flyway 确认**——`db/migration/` + pom 含 flyway-core/flyway-database-postgresql；队列名实测：`code.review.{task,delay,dead}.queue`、`agent.{step,delay,cancel,dead}.queue`、`sandbox.job.queue`；sandbox-runner 精确基数 52 java/含中文 4；**model-service 普查仍被污染**（排 venv 后仍 2350 py，疑含测试语料/嵌套环境），尾段阶段实施前重新精确圈定，不阻塞 1-5 阶段）
1. [x] **P0 TLS overlay**（独立提交）：`deploy/tls/{nginx-tls.conf,docker-compose.tls.yml,gen-self-signed.sh}` + 部署 README；基础 nginx.conf 仅做 CSP 收紧（移除 Google Fonts 域，单独可回滚）。验证：conf 语法审读、compose YAML python 解析通过；**栈级 https 走通转移服务器验收清单**。（实况：CSP 收紧已复验——前端对 Google Fonts 零引用 grep 复测仍零命中，`deploy/test-nginx-headers.sh` 只断言 `default-src 'self'`，不受影响）
2. [x] **告警与看板**（独立提交，可拆 2 笔）：rabbitmq `enabled_plugins` 挂载 + prometheus 抓取 job + `alerts.yml` 四条规则 + alertmanager 服务（webhook env 可配，默认空接收）+ grafana provisioning（数据源+一张看板 JSON）。验证：YAML 全部可解析、规则表达式与实测指标名对号；**触发验证转移服务器**。（实况：检查 A 已过，发现并修复 5 处，含三处漏 `sandbox.dead.queue`——该队列由 sandbox-runner 侧声明，步骤 0 只核 backend 故漏）
3. [x] **反馈闭环 backend**（独立提交）：`ReviewFeedback` 实体+迁移（按步骤 0 核实的机制）+ `POST /api/agent-runs/{runId}/feedback` + `GET /api/feedback/export` + `SecurityAuditLogger` 接入 + 校验矩阵单测（404/403/400/upsert 幂等/导出格式/审计断言）。验证：`mvn -s .mvn/settings.xml verify` 全绿。（实况：恢复时修两处——①`ReviewFeedbackControllerTest.exportSince` 辅助方法缺失导致测试编译失败；②**导出端点编码缺陷**：`APPLICATION_NDJSON` 不带 charset，String 转换器按 ISO-8859-1 落字节把中文 note 写成 `?`，语料在回灌前即损毁，已改为显式 UTF-8 字节+响应头声明，与 ReviewController 同口径）
4. [x] **错误上报端点**（独立提交）：`POST /api/client-errors`（匿名+4KB 截断+专用限流预算 `app.ratelimit.client-errors-limit`）+ 单测。验证：mvn verify 绿。（实况：CSRF 豁免复验为精确路径 `/api/client-errors` 而非通配）
5. [x] **数据生命周期文档**（独立提交）：`docs/` 下成文——ai_call_log 留存口径、postgres 备份/恢复步骤、compose 卷清单与清理边界（WorkspaceArchiveService 既有归档职责一并写清）。
6. [x] **冗余清理 backend**（每批独立提交）：候选清单（grep 引用分析+Spring 装配排除）→ 逐批删除 → 每批 mvn verify 绿；「已核未删」保留清单落 `research/redundancy-audit.md`（含 LegacyReviewProjectionService/MockAiReviewClient 保留理由）。（实况：**零删除**——358 个 main 源类中，30 个零直接引用全部框架托管，31 个「仅被测试引用」全部为 Spring 按接口/注册表/监听器装配的生产类，真候选 0 个；结论与复现脚本落 `research/redundancy-audit.md`。方法级死代码与重复代码明确划出本轮范围并说明理由）
7. [x] **注释中文化 backend**（按包分 8-10 批，每批独立提交）：252 个无中文文件治理——英文注释重写中文、关键裸奔逻辑补注释、不写废话注释、不动既有中文注释；每批 mvn verify 绿。完成判据：`LC_ALL=C.UTF-8 grep -rlP "\p{Han}"` 复查全含中文或豁免清单成文（纯常量/生成类）。（实况：252 个里**只有 59 个真有英文散文注释**,已全部重写为中文,复测「仍带英文散文注释」= 0;其余 193 个本就一句注释都没有,豁免清单落 `research/comment-exemptions.md`。另补三处关键裸奔逻辑:两个 Normalizer 的 XXE 全关配置、AgentToolRegistry 的四道闸;并订正 AgentOutboxScheduler/AgentSchedulingConfig 中已被证伪的断言。mvn verify 658/0/6)
8. [ ] **尾段（时机门：墨境步骤 6/7 已提交后）**：前端错误上报接线（main.js 挂载 sendBeacon 模块）+ frontend 3 个无中文文件注释治理 + sandbox/model-service 按步骤 0 普查结果同标准治理。验证：npm test + build 绿。
9. [ ] **收尾**：`trellis-check`（Agent）→ `trellis-update-spec` 沉淀（部署 overlay 模式、告警规则规范、注释质量标准）→ 提交推送 → 服务器侧验收清单（TLS 走通/告警触发/grafana 看板）并入 r7/r8 转移清单 → `/trellis:finish-work`。

风险文件：`deploy/nginx.conf`（CSP 收紧——唯一动基础栈的点）、backend finding/patch 域（新实体迁移）、SecurityConfig（新端点放行）。
回滚点：每阶段独立提交；overlay 删文件即回原状；迁移单独成笔。
产物：TLS overlay 三件套、告警四规则+看板、反馈 API+导出、错误上报端点、生命周期文档、冗余审计档、注释治理批次、服务器验收清单。
