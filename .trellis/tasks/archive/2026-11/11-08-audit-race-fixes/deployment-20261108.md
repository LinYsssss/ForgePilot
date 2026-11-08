# 部署记录（2026-11-08）

- 代码：`056e983`（业务改动在 `41e42b4` / `2873bb1` / `adb5ddf`），已推送；CI run [42470320689](https://github.com/LinYsssss/ForgePilot/actions/runs/42470320689) 成功，用时 3m11s。
- 备份：`/root/forgepilot-backups/audit-race-fixes-20261108T101654Z/forgepilot.dump`，3,127,615 字节，sha256 `32066c5c…c17c95fb`，`pg_restore -l` 列出 22 项 TABLE DATA。
- 回滚镜像：`fp-demo-backend:pre-race-fixes-20261108`（`95404fe9`）、`fp-demo-frontend:pre-race-fixes-20261108`（`16df2ee1`）。
- 10:22 UTC 执行 `docker compose -p fp-demo up --build --detach --wait backend frontend`，postgres 容器与卷未动。新镜像为 backend `68134b7c`、frontend `4b9a1de6`。
- 验证：三个容器 healthy；公网 `/api/actuator/health` 返回 UP；公网与本地构建的前端资产同为 `index-B4f02FLC.js`；Flyway 校验 15 条迁移，无需迁移；线上 jar 含 `RequirementQualityService$Content`。
- 本次没有向线上发起任何业务写请求，也没有调用 AI。
