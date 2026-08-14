# Design：生产加固与清理

> 依据 prd.md；证据锚点均为 2026-08-13 实测。本机无 Docker——R1/R2 的运行时验收显式转移到服务器环境（与 r7 基线复跑、r8 门禁同批），本机只交付配置与静态校验。

## D1 TLS 部署边界（R1）

**形态：叠加不侵入**。基础演示栈零改动（demo 零接触是既有纪律），新增 `deploy/tls/`：

- `nginx-tls.conf`：`listen 443 ssl`（TLSv1.2/1.3，`ssl_certificate /etc/nginx/certs/server.{crt,key}`），复制现有 server 块全部语义（安全头组、SSE 专段、actuator 拦截）；原 80 端口降级为 301 跳转。现有 HSTS 头已预埋（nginx.conf:26 注释声明「一旦加 TLS 立即生效」），不改参数。
- `docker-compose.tls.yml` override：挂载证书目录 + `443:443` + 替换 conf 挂载。启动方式 `docker compose -f docker-compose.yml -f tls/docker-compose.tls.yml up`。
- `gen-self-signed.sh`：openssl 自签发（SAN=localhost+可传入 IP/域名），开箱可跑；真实证书替换路径写进 README。
- **CSP 收紧**（两份 conf 同步）：移除 `https://fonts.googleapis.com`/`https://fonts.gstatic.com`（证据：`grep -rn "fonts.googleapis" frontend/` 零命中；墨境合同 §3.3 本就要求字体不从远端加载）。
- 回滚：删 overlay 文件即回原状；基础栈文件零 diff。

## D2 告警与看板（R2）

- **RabbitMQ 指标源**：`rabbitmq:3.13-management` 挂载 `enabled_plugins` 文件（`rabbitmq_management,rabbitmq_prometheus`），容器网内暴露 15692；prometheus 新增 scrape job。
- **告警规则** `deploy/observability/alerts.yml`（prometheus `rule_files` 挂载），首批四条：
  1. 审查队列积压：`rabbitmq_queue_messages_ready{queue=~"review.*"} > 50 for 5m`（阈值以 RabbitMqConfig 实际队列名为准，实现时核对）
  2. 死信非零：DLQ 对应队列 `messages > 0 for 1m`（RabbitMqConfig.java:56-57 已配 x-dead-letter）
  3. AI 熔断打开：`resilience4j_circuitbreaker_state{name="aiReview",state="open"} == 1`（实例名 application.yml:51+）
  4. 实例失联：`up == 0 for 2m`
- **alertmanager**（`prom/alertmanager`，127.0.0.1 端口绑定，沿用 compose 的 logging 锚点/mem_limit 习惯）：MVP 路由到可配置 webhook（env 注入，默认空接收器）；钉钉桥（prometheus-webhook-dingtalk）作为文档化可选项，不进默认栈——backend 既有 `DingTalkNotifier` 是业务通知，不复用于基础设施告警，边界写清。
- **Grafana**（127.0.0.1:3000）：provisioning 数据源 + 一张看板 JSON（面板：HTTP p95/QPS、队列深度与 DLQ、AI 调用失败与熔断状态、JVM 内存）。
- 本机验收边界：YAML 可解析性 + 规则表达式静态审读；`promtool`/栈级触发验证转移服务器（无 Docker）。

## D3 产品级反馈闭环——backend 先行（R3）

**领域落点**：`finding/` 包（既有 `AgentFindingController`）。

- 实体 `ReviewFeedback`：`id, runId, findingId(可空), type(enum FINDING_FALSE_POSITIVE | FINDING_CONFIRMED | MISS_REPORT), path(可空), line(可空), category(可空), note(≤2000), reporter, createdAt`。语义：误报/确认必须挂 findingId；漏报（MISS_REPORT）没有 finding 可挂，用 path+line+category+note 描述。
- 迁移：**实现前先确认既有 schema 演进机制**（flyway/liquibase/ddl-auto——实现阶段第一件事核实，跟随既有模式，不引入新机制）。
- API（权限与既有 run 访问守卫同源，审计走 `SecurityAuditLogger`）：
  - `POST /api/agent-runs/{runId}/feedback`——校验矩阵：run 不存在→404；无访问权→403；type 与字段组合非法（误报缺 findingId / 漏报缺 path）→400；同 reporter+findingId+type 重复→**upsert 取最新**（幂等，不 409）。
  - `GET /api/feedback/export?since=<iso>`——管理员权限，JSON Lines 输出，作为 r8 回灌流程（人工策展准入）的输入工件；不自动写入评测语料。
- 测试：控制器 + 服务层单测覆盖校验矩阵逐条 + 审计日志断言 + 导出格式钉死。
- **本任务不动 frontend**；ink UI 触点在墨境任务步骤 8 落地（届时只需调该 API）。

## D4 前端错误上报——backend 端点先行（R4）

- `POST /api/client-errors`：匿名可达（错误常发生在登录前），载荷 `{message, stack?, url, ts}` 总尺寸 ≤4KB 超限截断；专用限流预算（沿用 `RateLimitFilter` 的配置族样式，新增 `app.ratelimit.client-errors-limit` 默认 10/分/IP）；落 SLF4J WARN 带 marker（不入库，容器日志/observability 可见）。单测：截断、限流、载荷校验。
- 前端接线（`window.onerror`+`unhandledrejection`→sendBeacon，main.js 一行挂载）放**尾段阶段**，待墨境步骤 6/7 落库后实施，避免与施工中的 frontend/ 冲突。

## D5 冗余清理——受控（R6，Q1 已裁决）

- **唯一删除标准**：可证死代码——全仓 grep 零引用 **且** 非 Spring 装配点（无 stereotype 注解，或有注解但无路由/队列/调度绑定）**且** 非资产。每批删除后 `mvn -s .mvn/settings.xml verify` 全绿才提交。
- 已预判「像冗余但不是」的保留项（核查后落档防再议）：`LegacyReviewProjectionService`（旧 UI 兼容层，墨境步骤 8 迁完才死）、`MockAiReviewClient`（profile 门控的开发资产）。
- 排除面：受保护资产全清单（prd）、`frontend/`（墨境地盘）、r8 待门禁代码。预期收获不大——如实报告「查了多少、删了多少、留了什么」，不凑数。

## D6 注释中文化——受控（R7，Q1 已裁决）

- 对象：backend 252 个无中文文件（英文注释→中文重写；关键逻辑裸奔处补注释）。**质量标准：注释说明约束/意图/坑，不复述代码**；类头一句话职责；公共 API javadoc 中文；不写废话注释；既有正确中文注释不重写。
- 分批：按包分 8-10 批，每批独立提交（防 git blame 一锅粥），每批 `mvn verify` 绿。
- 排除：prompts 模板（golden 钉字节）、demo-repos、evaluation fixtures（语料资产）、生成代码；frontend 3 文件与 sandbox/model-service（先精确普查排除 venv）放尾段。

## 兼容与运维

- 基础演示栈行为全程零变化（TLS/告警全部 overlay/新增文件承载；唯一例外：基础 nginx.conf 的 CSP 收紧——低风险且有零引用证据，独立提交可单独回滚）。
- 与并行工作的隔离：本任务在墨境步骤 6/7 落库前不触碰 `frontend/`；不触碰 r8 prompt 资产。
- 回滚：每阶段独立提交；overlay 文件删除即回原状；反馈闭环带迁移的提交单独成笔。
