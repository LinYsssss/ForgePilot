# 部署记录

## 2026-10-16（`fix/review-audit-fixes` 合入 main 后的部署，线上 = `3a251d7`）

- 代码：`3a251d7`（15 提交：GitHub/GitLab 权威读取错误码对齐、embedding 分批、快速语料 v2、前端项目选择收敛、交付文档补充），`main` 由 `78574d8` 快进并推送（origin/main 同步）；CI run 38931556928 四个 job 全绿（Backend verify、Frontend gates、Evaluation contract、Empty-stack Compose smoke，3 分 37 秒）。
- 部署前：无迁移变更（Flyway 校验 14 条迁移、无需迁移）；备份 `/root/forgepilot-backups/review-audit-fixes-deploy-20261016T162527Z/fp-demo-db-pre-3a251d7-20261016T162527Z.dump.gz`（3,034,539 字节，pg_dump custom 格式 216 项，gzip 校验通过，sha256 `da1b6363…`）。
- 执行：`docker compose -p fp-demo up --build --detach --wait`，16:25 UTC 重建 backend 与 frontend；未带任何 volume 参数，postgres 容器与数据卷未动。
- 镜像：backend `sha256:1e18dd71…`、frontend `sha256:526da6f3…`（替换 backend `f2970cfb…`、frontend `ceaf913f…`）。
- 运行配置：新增 `FORGEPILOT_KNOWLEDGE_EMBEDDING_BATCH_SIZE=64`（compose 透传默认值，.env 未显式设置）。
- 验证：本机门禁后端全量 432/0（5 分 14 秒）、前端 lint/typecheck/66 测试/build、评测契约（含 `verify-freeze`，本机 holdout 守卫失败属证据机预期）全部通过；部署后三容器健康，`/actuator/health` 直连、经 nginx 与公网（yasinlin.com）均 UP，前端引用 `assets/index-7nkyTH1B.js`/`index-D1nIfD3d.css` 与本地构建一致。
- 备注：本次后 10-14 与更早 09-30、10-01 补充段所述改动均已在线（09-30 后端部分随 10-03 的 `54e2953` 先行上线，09-30 前端部分与 10-14 全部随本次上线）。README/MANUAL/OPERATIONS/SECURITY 中 10-14 补充段的「尚未部署」已更新为「2026-10-16 已部署」；09-30、10-01 的旧标记保留待统一清理。回滚：无 schema 变更，可直接回退前后端镜像。
