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

## 基线结果（本任务收尾时更新）

- frontend npm test：PASS（5 tests）
- frontend npm run build：PASS
- backend（容器化 mvn test）：见任务收尾记录
- model-service pytest：见任务收尾记录
