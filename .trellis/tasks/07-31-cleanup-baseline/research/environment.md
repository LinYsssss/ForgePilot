# 环境探测（2026-07-31，Linux 服务器 /root/reposage）

## 工具链

| 工具 | 版本 | 结论 |
|---|---|---|
| Docker | 29.6.1（Compose v5.3.1） | ✅ 可用——解锁容器化构建/测试、Compose 验收、Testcontainers、镜像扫描 |
| Node | v24.18.0（npm 12.0.1） | ⚠️ 可用，但 `frontend/package.json` engines 要求 `>=22 <23`；npm 仅告警不阻断，CI 用 Node 22。本机结果可信但与 CI 存在版本差 |
| Java | ❌ 未安装 | 后端/沙箱本机裸跑不可行 |
| Maven | ❌ 未安装 | 同上；改用 `maven:3.9-eclipse-temurin-17` 容器 + `reposage-m2` 卷缓存 |
| Python | 3.10.12 | ✅；pytest + model-service 依赖已按需安装 |

## 各模块验证深度结论

- **frontend**：本机直接 `npm test && npm run build` ✅（基线：5 tests 通过、Vite 6.4.3 构建通过）。
- **backend / sandbox-runner**：本机通过 Docker 容器跑 Maven（首跑需下载依赖，较慢；结果与 CI 同口径）。命令：
  `docker run --rm -v /root/reposage:/ws -v reposage-m2:/root/.m2 -w /ws/backend maven:3.9-eclipse-temurin-17 mvn -s .mvn/settings.xml test`
- **model-service**：`python3 -m pytest tests/ -q`（依赖已装）。
- **Docker 动态验收**（Compose 起全栈、Testcontainers 3 个跳过用例、镜像扫描）：本机具备条件，属加分验证项，不在本任务范围。
- `scripts/verify-local.sh` 已按以上口径实现：缺工具链的项记 `SKIPPED(未验证)`，不冒充通过。

## 重大发现：Compose 全栈已在本机运行

`docker ps`（2026-07-31）：deploy-nginx / deploy-frontend / deploy-backend(healthy) / deploy-sandbox-runner / deploy-model-service 已运行 7 小时；deploy-postgres(pgvector, healthy) / deploy-rabbitmq(healthy) / prometheus / otel-collector 已运行 3 天。另有与本项目无关的 cli-proxy-api 容器。

含义：
- **端到端验收在本机完全可行**：Agent 链路（RabbitMQ）、Patch 沙箱（Docker）、真实 PostgreSQL 迁移、Prometheus 指标全部具备条件。
- **注意**：这是活的部署环境——postgres 有 3 天的真实数据；改前端后需重建镜像才会反映到该环境；涉及数据库的操作（迁移/清库）必须先备份（deploy/backup.sh）。
- deploy/.env 在位（Compose 依赖它），未受本次密钥备份清理影响。

## 基线结果（2026-07-31 实测）

- frontend npm test：PASS（5 tests，后续任务已扩展）
- frontend npm run build：PASS（Vite 6.4.3）
- backend（maven:3.9-eclipse-temurin-17 容器 `mvn test`）：PASS —— **524 tests / 0 failures / 0 errors / 3 skipped**（3 个 Testcontainers 用例照旧跳过；依赖缓存在 `reposage-m2` 卷）
- model-service pytest（python:3.12-slim 容器）：PASS（9 passed, 43 warnings）
- sandbox-runner：本轮未跑（后续任务收尾时用同一容器方式验证）
