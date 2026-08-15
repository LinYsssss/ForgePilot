# 服务器侧验收清单:TLS + 告警 + 看板(阶段 1/2 运行时验收)

> 背景:本机无 Docker,阶段 1(TLS overlay)与阶段 2(告警与看板)在本机只交付了
> 配置与静态校验:python 逐一解析全部 YAML/JSON、基础与 TLS 两份 nginx conf 归一化
> diff 证明语义完整继承、bash -n / sh -n 过检、alertmanager 入口脚本三条路径落盘
> 模拟通过(见同目录 `verify-alertmanager-entrypoint-local.sh`,含空/未设/带 & 参数 URL)。
> 下列**运行时验收未执行、不假装执行**,与 r7 基线复跑、r8 门禁同批转移到服务器执行。
> 执行完毕把结果(命令输出/截图路径/日期)回填本文件,作为能力表述的实测产物锚点。

## 0. 前置

- [ ] 服务器已拉取包含本批改动的分支;`deploy/.env` 就绪(新增可选项 `ALERT_WEBHOOK_URL`、`GRAFANA_ADMIN_PASSWORD` 见 `.env.example`)
- [ ] 基线确认:不叠加 TLS 时 `docker compose up -d` 行为与改动前一致(HTTP 栈零变化;rabbitmq 多挂了 enabled_plugins、prometheus 多挂了 alerts.yml、新增 alertmanager/grafana 两个 127.0.0.1 服务)

## 1. TLS(deploy/tls/)

```bash
cd deploy
./tls/gen-self-signed.sh <服务器IP或域名>
docker compose -f docker-compose.yml -f tls/docker-compose.tls.yml up -d
```

- [ ] `docker compose ... exec nginx nginx -t` 通过(TLS conf 语法在真 nginx 下过检)
- [ ] `curl -sI http://<host>/ | head -n1` 返回 301,Location 为 https
- [ ] `curl -kI https://<host>/` 返回 200,响应头含 `Strict-Transport-Security: max-age=15552000`
- [ ] CSP 头无 `fonts.googleapis.com` / `fonts.gstatic.com`(基础与 TLS 两形态各验一次)
- [ ] 浏览器走 https 完成一次**登录 + 会话内操作**(cookie 会话在 https 下正常;`AUTH_COOKIE_SECURE=true` 时尤其要验)
- [ ] https 下打开一个 Agent Run 时间线,SSE 事件流持续推送不断流(验证 443 段的 proxy_buffering off / 1h 读超时继承)
- [ ] `curl -k https://<host>/actuator/prometheus` 返回 404(actuator 拦截语义继承)
- [ ] 回滚演练:`down` 后不带 tls 文件重新 `up`,纯 HTTP 行为回到原状
- [ ] (真实证书部署时)按 `deploy/tls/README.md` §二替换证书并 reload,浏览器无告警

## 2. 告警链路(deploy/observability/)

静态校验(容器内工具):

- [ ] `docker compose exec prometheus promtool check config /etc/prometheus/prometheus.yml`
- [ ] `docker compose exec prometheus promtool check rules /etc/prometheus/alerts.yml`
- [ ] `docker compose exec alertmanager amtool check-config /etc/alertmanager/alertmanager.yml`

指标源确认:

- [ ] `docker compose exec backend curl -s http://rabbitmq:15692/metrics/per-object | grep rabbitmq_queue_messages_ready` 能看到 9 条业务队列(queue 标签与队列声明常量一致:backend RabbitMqConfig 8 条 + sandbox-runner SandboxRabbitConfig 的 sandbox.dead.queue)
- [ ] Prometheus UI(127.0.0.1:9090)Targets 页三个 job(reposage-backend / otel-collector / rabbitmq)全部 UP
- [ ] `resilience4j_circuitbreaker_state{name="aiReview"}` 在 Prometheus 可查(closed=1 为常态)

触发验证:

- [ ] **DeadLetterQueueNotEmpty**:经 RabbitMQ 管理台(127.0.0.1:15672)向 `code.review.dead.queue` 直接 publish 一条消息 → 1 分钟后 Prometheus /alerts 转 firing → Alertmanager UI(127.0.0.1:9093)可见;验证后 purge 该队列,告警自动 resolved
- [ ] **InstanceDown**:`docker compose stop otel-collector` → 2 分钟后 firing → `start` 后 resolved
- [ ] (可选)**AiCircuitBreakerOpen**:临时把 `LLM_API_KEY` 改错并触发一次审查,熔断打开即 firing(state="open" 无 for,秒级)
- [ ] (可选)**ReviewQueueBacklog**:阈值 50/5m,演示环境难自然触发;可临时停 backend 后批量投递审查任务验证,或仅确认表达式在 Prometheus 可求值
- [ ] (配置了 `ALERT_WEBHOOK_URL` 时)webhook 端点收到 Alertmanager 原生 JSON POST

## 3. Grafana 看板

- [ ] 127.0.0.1:3000 登录(admin / `GRAFANA_ADMIN_PASSWORD`),数据源 Prometheus 测试通过
- [ ] 看板「RepoSage 运行概览」自动出现在 RepoSage 目录下
- [ ] 跑一次完整审查/Agent Run 后逐面板出数:HTTP 速率与时延、工作队列深度、死信(恒 0)、AI 调用速率、熔断状态(closed=1)、Token 速率、Agent 步进 p95、JVM 堆、up
- [ ] 已知边界确认:HTTP 面板是均值/瞬时最大而非 p95(backend 未对 http.server.requests 开直方图桶,面板描述已写明)——如需真 HTTP p95,后续单独提 backend 配置改动

## 4. 回填区(执行时填写)

| 项 | 日期 | 结果 | 证据 |
| --- | --- | --- | --- |
| TLS https 登录会话 | | | |
| DLQ 注入触发告警 | | | |
| InstanceDown 触发/恢复 | | | |
| Grafana 全面板出数 | | | |
| promtool/amtool 校验 | | | |
