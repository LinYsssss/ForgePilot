# 部署记录

## 2026-10-17（`5973d48` 推送后的部署，线上 = `5973d48`）

- 代码：`5973d48`（三个提交：测试侧 CSRF 改走真实双提交路径、前端 `vue` 3.5.44、文档收尾）。
  本次推送**没有触及后端生产代码与迁移**——`git diff --name-only c881e2b..5973d48 -- backend/src/main`
  为空，后端产物功能等价；实际变化是前端依赖及其镜像重建。
- CI：run 39045618266 四个 job 全绿（Backend verify、Frontend gates、Evaluation contract、
  Empty-stack Compose smoke）。
- 部署前：无迁移变更（Flyway 校验 14 条迁移、无需迁移）；备份
  `/root/forgepilot-backups/order-deps-docs-deploy-20261017T153542Z/fp-demo-db-pre-5973d48-20261017T153542Z.dump.gz`
  （3,034,542 字节，pg_dump custom 格式 212 项，gzip 校验通过，sha256 `8537f975…`）。
- 执行：`docker compose -p fp-demo up --build --detach --wait`，15:36 UTC 起重建 backend 与 frontend；
  未带任何 volume 参数，postgres 容器与数据卷未动（容器启动时间仍是 `2026-10-07T16:11:24Z`）。
- 镜像：backend `sha256:8337b491…`、frontend `sha256:6ae8b0cf…`（替换 backend `9940fc2e…`、
  frontend `1229232c…`；postgres `a947c45c…` 不变）。
- 验证：三容器 healthy；`/actuator/health` 直连（18080）、经 nginx（18081）与公网 `yasinlin.com`
  均 `UP`；后端日志 `Successfully validated 14 migrations` 与
  `Schema "public" is up to date. No migration necessary.`；页面引用的
  `assets/index-BxbeTmqU.js` 与 `index-D1nIfD3d.css` 与本地 `frontend/dist/assets` 一致
  （CSS 哈希未变，JS 哈希随 vue 升级变化）。
- 回滚：无 schema 变更，可直接回退前后端镜像。
