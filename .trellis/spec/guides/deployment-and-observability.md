# 部署与可观测性

> 来源：08-13 生产加固任务（TLS overlay / 告警规则 / 看板）。每条都对着 `deploy/` 下的真实文件，
> 发现规则与实物不符时先查实物。

---

## 1. Overlay 模式：可选能力不进基础栈

**规则**：TLS、调试端口、替换镜像这类**可选**能力，一律做成 overlay 文件叠加，不改基础 compose。

```bash
docker compose -f docker-compose.yml -f tls/docker-compose.tls.yml up -d
```

- 实物：`deploy/tls/{nginx-tls.conf,docker-compose.tls.yml,gen-self-signed.sh}` + README。
- **回滚 = 不带那个 -f**，或删掉 overlay 目录；基础栈从未被修改，所以没有「改回去」这一步，
  也就不存在改回去时改漏的可能。
- 反例：把 TLS 直接写进 `docker-compose.yml` 再用注释开关。注释掉的配置会随时间腐坏，
  而且没人能确认注释块与当前基础栈是否还兼容。

**唯一允许动基础栈的情况**是安全收紧本身（如 CSP 移除外部字体域），此时：
单独成一笔提交、写清判据（实测前端零引用）、并确认相关门禁脚本不会因此变红
（`deploy/test-nginx-headers.sh` 只断言 `default-src 'self'`，收紧不影响它）。

## 2. 告警规则必须对着**实测**指标名与队列名写

告警最常见的失效方式不是规则写错，而是**规则里的名字根本不存在**——它永远不触发，
而"从不触发"和"一切正常"在看板上长得一模一样。

- **指标名**：写规则前先抓一次 `/actuator/prometheus`，用实际输出的名字，不要凭记忆。
- **队列名**：必须**跨模块**收集。本项目的队列由两处声明：
  - backend `RabbitMqConfig`：`code.review.{task,delay,dead}.queue`、`agent.{step,delay,cancel,dead}.queue`
  - sandbox-runner `SandboxRabbitConfig`：`sandbox.job.queue`、`sandbox.dead.queue`

  08-13 的检查实测发现：DLQ 告警、看板、生命周期文档**三处都漏了 `sandbox.dead.queue`**，
  原因就是普查只做了 backend 一侧（backend 只配路由、不建该队列）。
  **只查一个模块的队列声明是不够的。**

- 死信队列的告警要覆盖**全部** DLQ；漏掉一条，那条链路的失败就是静默的。

## 3. 指标标签只用有界低基数值

run id、仓库名、错误信息**不得**作为标签——每个不同的标签值都会新开一条时间序列，
高基数会把 Prometheus 拖垮。逐条审计留痕交给数据库（`ai_call_log`、各 audit 表），
指标只负责聚合视图。实物参见 `AgentMetrics` / `AiMetrics` 的类注释。

## 4. 告警通道默认必须能空跑

Alertmanager 的接收器在 webhook 未配置时要退化成空接收，**不能因为缺密钥就起不来**。
否则本地与 CI 想跑一次完整栈都得先准备一套真实告警凭据，结果就是没人跑。

## 5. 供应链门禁

见 [backend/security-guidelines.md](../backend/security-guidelines.md) 的
「Supply-chain gate」一节：门禁只拦「已有修复版本」的 HIGH/CRITICAL；
「修复已发布但无可拉取镜像携带」这个盲区走带 `exp:` 到期日的 `.trivyignore`，
且容器化 trivy 必须显式挂载该文件。
