# PR 守门 Agent：SCM 与 Sandbox 运维及安全验收

本文记录 Phase 3 Task 12 的运行边界、验收步骤和当前证据。容器相关命令必须在专用验收主机执行；本工作树所在主机没有 Docker，因此未把容器安全验收标记为通过。

## 1. 凭据和配置边界

- SCM API 主机、安装标识和凭据只能从已持久化且已授权的 `scm_installation` 读取。Webhook payload 不得覆盖 API 主机或凭据。
- GitHub 使用 Check Run + Issue/PR comment；GitLab 使用 Commit Status + MR note。发布正文包含摘要、阻断 Finding、证据链接、Agent Run URL 和 Patch 校验状态。
- 任何会暴露 Patch 内容的发布都必须先获得审批；未审批请求应在发起 HTTP 请求前拒绝。
- `SCM_ALLOW_INSECURE_LOCALHOST=true` 仅用于 WireMock/本机契约测试。生产配置必须使用 HTTPS。
- `.env` 必须由部署系统注入，禁止提交真实密钥。Compose 现在要求 `DB_PASSWORD`、`RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD` 和 `SANDBOX_SIGNING_SECRET`，缺失即失败，不再提供 `change-me` 运行时默认值。

## 2. Sandbox 安全模型

Sandbox Runner 是受信任的单机编排组件；分析容器不是 Docker Socket 的可信使用者。Runner 读取签名、过期时间和 nonce 后才接受任务，并只路由固定注册的命令 ID。

分析容器创建参数必须同时满足：

- 固定且带 `sha256` digest 的镜像；
- `--network none`、`--read-only`、`--cap-drop ALL`、`no-new-privileges`；
- 非 root `65534:65534`、CPU/内存/PID 上限和超时 kill；
- 只绑定当前任务 workspace 到 `/workspace`，临时目录为 `noexec,nosuid` tmpfs；
- 不继承 Docker Socket、SCM credential、LLM key、数据库密码或宿主机任意目录；
- 依赖缓存只有存在 `.complete` 标记才允许只读挂载；缺失缓存返回 `ENVIRONMENT_INCOMPLETE`；
- 归档解包、workspace 路径和子模块 URL 必须通过遍历、符号链接、私网/链路本地地址检查。

Compose 中唯一挂载 Docker Socket 的服务是 `sandbox-runner`。该服务必须部署在受控单机上；它创建的分析容器不得继承该挂载。

## 3. 自动化验证

在仓库根目录执行：

```text
cd backend
mvn test

cd ../sandbox-runner
mvn test

cd ../frontend
npm test
npm run build
```

当前本机证据：

- backend：141 tests，0 failures，3 skipped；跳过 `InfrastructureIntegrationTest`、`LegacySchemaMigrationIntegrationTest` 和 `GitHubWebhookAgentRunIntegrationTest`；
- sandbox-runner：31 tests，0 failures，0 skipped；
- frontend：3 tests passed，生产构建通过；
- GitHub/GitLab WireMock 发布契约：4 tests passed；
- `git diff --check`：无错误。

Docker 可用时必须额外执行：

```text
cd backend
mvn -Dtest=InfrastructureIntegrationTest,LegacySchemaMigrationIntegrationTest,GitHubWebhookAgentRunIntegrationTest test
```

该命令必须真实启动 PostgreSQL/RabbitMQ，验证 Flyway、MQ 连接和“签名 GitHub webhook → 持久化 Agent Run”链路；测试被跳过不算验收通过。

## 4. Compose 安全冒烟

在专用验收主机准备强随机 `.env` 后执行：

```text
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config
docker compose --env-file deploy/.env -f deploy/docker-compose.yml build
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d
```

验收必须保存以下脱敏证据：

1. `docker inspect` 显示分析容器只有 `/workspace` 任务绑定和受限 tmpfs，没有 `/var/run/docker.sock`、宿主根目录或 backend 工作目录。
2. `HostConfig.NetworkMode` 为 `none`；容器内访问 RabbitMQ、backend、`169.254.169.254` 等地址失败。
3. 以恶意命令 ID、未固定 digest、`workspace:../outside`、符号链接和归档遍历样本提交任务，Runner 拒绝任务且没有残留容器/工作目录。
4. 读取容器环境变量和日志时，只验证不存在 `SANDBOX_SIGNING_SECRET`、SCM token、LLM key、数据库密码；所有输出必须脱敏，禁止把密钥写入 CI 日志。
5. RabbitMQ、backend 的宿主端口仅绑定 `127.0.0.1`；对外流量只经 Nginx 暴露的入口。

推荐在验收脚本中对每个分析容器执行以下检查，并只输出布尔结果：

```text
docker inspect <container> --format '{{json .Mounts}}'
docker inspect <container> --format '{{.HostConfig.NetworkMode}}'
docker inspect <container> --format '{{json .Config.Env}}'
docker logs <container>
```

## 5. 未完成项和放行条件

当前主机没有 `docker` 命令，无法执行 Compose 配置解析、镜像构建、真实 RabbitMQ→Runner 联调、Testcontainers 或容器逃逸/网络隔离探测。因此 Phase 3 的最终 Docker 安全放行仍为 **未完成**。

放行前必须在有 Docker 的隔离主机重新运行本节全部命令，保存脱敏日志和容器 inspect 结果，并由评审确认没有任何跳过项。随后才可进入 Phase 4 插件、证据、Patch 和评测任务。
