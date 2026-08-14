# PRD：生产加固与清理——TLS/告警/反馈闭环 + 冗余与注释治理

## Goal / 用户价值

把 RepoSage 从「生产形态完整」推进到「可对外部署 + 可运维 + 可讲述」：补齐 TLS 部署边界、告警与看板、产品级漏报/误报反馈闭环、前端错误上报、数据生命周期文档五项能力；同时按「受控治理」口径完成冗余代码清理与注释中文化，使代码库达到面试/答辩可展示的表达质量。

## 背景（2026-08-13 生产就绪探测实录）

健康检查/Prometheus/OTel/DLQ/Outbox（`AgentOutboxScheduler`）/恢复（`AgentRecoveryService`）/发布幂等/限流（`RateLimitFilter`）/审计（`SecurityAuditLogger`）/钉钉通知/CSP 均已在库；CI 已在 Node 22 跑前端。真实缺口仅五项，即本任务 R1-R5。用户追加：顺便删冗余代码、注释用中文写好（R6/R7，力度经 Q1 裁决为「受控治理」，用户委托按推荐执行）。

## 已确认事实（仓库证据锚点）

- `deploy/docker-compose.yml`：postgres(pgvector:pg16)/rabbitmq(3.13-management)/backend/sandbox-runner/model-service/frontend/nginx(1.30.4)/otel-collector/prometheus；**无 alertmanager、无 grafana**。`deploy/observability/prometheus.yml` 仅 2 个 scrape job、**无 rule_files**。
- `deploy/nginx.conf`：**无 ssl/443**；HSTS 已预埋（行 26，TLS 一加即活）；CSP 放行 Google Fonts 但 `frontend/` 对其**零引用**（可收紧）；认证为 cookie 会话制→裸 HTTP 有会话劫持风险。
- 反馈闭环落点：`finding/AgentFindingController` 域 + `patch/PatchApproval(Decision)` + `SecurityAuditLogger`；r8 回灌流程为人工策展准入（r8 任务步骤 6），反馈数据只作输入不自动进语料。
- 限流配置族样式：`SecurityConfig` 的 `app.ratelimit.*`（错误上报端点沿用此样式扩预算键）。
- 注释普查（LC_ALL=C.UTF-8, \p{Han}）：backend 349 个 java 文件中 **252 个无中文**；frontend 74 中仅 3 个；sandbox/model-service 需排除 venv 后重查。
- 本机**无 Docker**：R1/R2 运行时验收转移服务器（与 r7 基线复跑、r8 门禁同批转移）。

## Requirements

- R1（P0）TLS 部署边界：`deploy/tls/` overlay（nginx 443 conf + compose override + 自签发脚本 + 文档），基础栈零改动；唯一基础栈改动为 CSP 移除 Google Fonts 域（独立提交可回滚）。
- R2（P1）告警与看板：rabbitmq prometheus 插件接入抓取；`alerts.yml` 首批四条（队列积压/DLQ 非零/AI 熔断打开/实例失联）；alertmanager（webhook env 可配，默认空接收，钉钉桥仅文档化可选）；grafana provisioning + 一张看板。
- R3（P1）反馈闭环 backend：`ReviewFeedback` 实体（误报/确认挂 findingId，漏报用 path+line+category+note）+ `POST /api/agent-runs/{runId}/feedback`（404/403/400/upsert 幂等矩阵）+ `GET /api/feedback/export`（管理员，JSON Lines，作 r8 回灌输入）+ 审计接入。**本任务不动 frontend**，ink UI 触点归墨境任务步骤 8。
- R4（P2）错误上报：`POST /api/client-errors`（匿名、4KB 截断、专用限流键、日志 sink 不入库）；前端接线放尾段（时机门：墨境步骤 6/7 落库后）。
- R5（P2）数据生命周期文档：ai_call_log 留存、postgres 备份/恢复、compose 卷清单与清理边界成文。
- R6 冗余清理（受控）：唯一删除标准=全仓零引用 且 非 Spring 装配点 且 非资产；每批删除后 `mvn verify` 绿；「已核未删」清单落档（含 `LegacyReviewProjectionService`（旧 UI 兼容层）、`MockAiReviewClient`（profile 门控开发资产）的保留理由）；不凑数。
- R7 注释中文化（受控）：backend 252 文件全量治理（英文注释重写中文、关键裸奔逻辑补注释、不复述代码、不写废话、不动既有中文注释），按包分批独立提交；frontend 3 文件与 sandbox/model-service 放尾段。

## Acceptance Criteria

- [ ] TLS：overlay 三件套 + 文档在库；本机完成 conf 语义审读与 YAML 解析校验；服务器验收清单（自签证书 https 走通登录会话、HTTP 栈行为零变化）并入转移清单。CSP 无 Google Fonts 域且前端无 CSP 违规（尾段浏览器 QA 时复验）。
- [ ] 告警：四条规则 + alertmanager + grafana 配置在库且 YAML 全部可解析、指标名与实测对号；服务器验收清单（DLQ 注入触发告警、看板出数）并入转移清单。
- [ ] 反馈闭环：`mvn verify` 全绿含校验矩阵逐条单测（404/403/400/upsert/导出格式/审计断言）；curl 操作序列文档化。
- [ ] 错误上报：端点单测（截断/限流/校验）绿；前端接线在尾段完成后 npm test+build 绿。
- [ ] 生命周期文档在 `docs/` 成文且与 compose 卷实况一致。
- [ ] 冗余清理：每批 verify 绿；`research/redundancy-audit.md` 含「已删/已核未删+理由」全量清单。
- [ ] 注释治理：backend 复查 `grep -rlP "\p{Han}"` 全含中文或豁免清单成文；抽查 10 文件注释达质量标准（说明约束/意图，不复述代码）。
- [ ] 全程零 diff：`demo-repos/`、`evaluation/cases/`、`knowledge-noise/`、backend `prompts/` 模板；墨境步骤 6/7 落库前 `frontend/` 零触碰。

## Out of Scope

- 多模型路由降级、增量重审等新特性；墨境重构本体（另有任务）；r8 R3/R4（等 R2 门禁）；alertmanager→钉钉桥的默认接入（仅文档）。

## Open Questions

（无——Q1「清理力度」已按用户委托取推荐档「受控治理」，其余决策均有仓库证据或设计裁决支撑。）
