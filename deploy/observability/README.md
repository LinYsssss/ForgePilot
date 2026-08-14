# 可观测性栈(observability/)

演示栈的指标、告警与看板配置。组件全部随 `deploy/docker-compose.yml` 启动,管理端口一律只绑 127.0.0.1,远程访问走 SSH 隧道。

## 组件与数据流

```text
backend /actuator/prometheus ──┐
otel-collector :9464 ──────────┼──> prometheus :9090 ──告警──> alertmanager :9093 ──(可选)──> webhook
rabbitmq :15692(仅容器网内)──┘        │
                                       └──数据源──> grafana :3000(看板「RepoSage 运行概览」)
```

| 文件 | 作用 |
| --- | --- |
| `prometheus.yml` | 抓取配置 + 规则装载 + Alertmanager 对接 |
| `alerts.yml` | 首批四条告警规则(队列积压 / 死信非零 / AI 熔断打开 / 实例失联) |
| `alertmanager.yml` + `alertmanager-entrypoint.sh` | 告警路由;webhook 地址由 `ALERT_WEBHOOK_URL` 环境变量注入,默认空接收器 |
| `rabbitmq-enabled-plugins` | 钉死 RabbitMQ 插件清单(management + prometheus),Erlang term 格式,末尾句点必须保留 |
| `grafana/provisioning/` + `grafana/dashboards/` | Grafana 数据源与看板自动装配 |
| `otel-collector.yml` | OTLP trace/metric 汇聚(既有,未随本批改动) |

## 告警外发配置

默认不外发:告警只在 Alertmanager UI(`http://127.0.0.1:9093`)与 Prometheus `/alerts` 页可见,这是未配置密钥时的安全落地形态。

要外发,在 `deploy/.env` 里设置后重建 alertmanager 容器:

```bash
ALERT_WEBHOOK_URL=https://your-webhook-endpoint/path
docker compose up -d alertmanager
```

地址接收 Alertmanager 原生 webhook JSON(POST)。**钉钉群机器人不认这个载荷格式**,直接填钉钉机器人地址收不到消息;接钉钉的可选做法是加一个 [prometheus-webhook-dingtalk](https://github.com/timonwong/prometheus-webhook-dingtalk) 桥接容器,把 `ALERT_WEBHOOK_URL` 指向桥接服务,由它转成钉钉 markdown 后再发群机器人。桥接组件不进默认栈——演示环境的告警在 UI 里看足够。

边界说明:backend 自带的 `DingTalkNotifier` 是**业务事件**通知(审查完成等),与基础设施告警是两条链路,刻意不复用——业务通知挂了不该影响基础设施告警,反之亦然。

## 服务器侧验证(本机无 Docker,以下命令在服务器执行)

```bash
# 规则与配置静态校验(promtool 在 prometheus 容器里自带)
docker compose exec prometheus promtool check config /etc/prometheus/prometheus.yml
docker compose exec prometheus promtool check rules /etc/prometheus/alerts.yml
docker compose exec alertmanager amtool check-config /etc/alertmanager/alertmanager.yml

# rabbitmq per-object 指标确认(队列积压/死信告警依赖 queue 标签)
docker compose exec backend curl -s http://rabbitmq:15692/metrics/per-object | grep rabbitmq_queue_messages_ready
```

完整触发验收(死信注入→告警 firing→看板出数)见
`.trellis/tasks/08-13-production-hardening/research/server-acceptance-checklist.md`。

## Grafana

- 地址 `http://127.0.0.1:3000`,初始账号 `admin`,密码取 `.env` 的 `GRAFANA_ADMIN_PASSWORD`(缺省 admin,首次登录改掉)。
- 看板与数据源全部由 provisioning 文件装配;UI 里的修改不落盘,容器重建即回到仓库文件版本。想固化改动:UI 导出 JSON 覆盖 `grafana/dashboards/reposage-overview.json`。
- Prometheus/Alertmanager/Grafana 均不挂持久卷:指标历史与告警静默随容器重建清零。这是演示栈的刻意取舍(与 compose 其他观测组件一致),长期留存不是当前目标——需要时再给 prometheus 挂 TSDB 卷。
