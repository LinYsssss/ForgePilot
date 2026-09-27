# 部署记录

## 补记：2026-09-23（审查输出改中文，`review-3`）

当时没有写部署记录，这里按线上事实补记。

- 代码：`5f1869c`（`ReviewPrompts` 加统一中文规则，`VERSION` → `review-3`），归档提交 `9707664`。
- 只重建并替换 backend（容器创建于 2026-09-23 01:41:52 UTC）；frontend 无改动，仍是 2026-09-21 的镜像；postgres 未动。
- 部署前备份：`/root/fp-demo-pre-deploy-20260923T014044Z.sql`。
- 验证：Halo fork PR #5 重审（review 71）`prompt_version=review-3`，说明与建议为中文、证据原样；随后 37 个开放 PR 全部重审为 `review-3`（mall #7 已合并、Halo #23 超时除外）。

## 2026-09-25（`review-4` 与本任务其余改动）

- 代码：`0885c0a`、`99058f3`、`3a6d2a3`、`84740b2`、`ea51e6a`；镜像构建自 `ea51e6a` 的工作树（backend 与 frontend 构建上下文均无未提交改动）。
- 部署前：没有 PENDING / RUNNING 的审查；备份 `/root/fp-demo-pre-deploy-20260925T195525Z.sql`（9,792,671 字节）。
- 执行：`docker compose -p fp-demo build backend frontend`，`docker compose -p fp-demo up --detach --wait backend frontend`；未带任何 volume 参数，postgres 容器与数据卷未动，无新迁移。
- 镜像：backend `7525aeb48009`、frontend `b8d8f09fdefa`；公网首页引用 `assets/index-CnocTUqh.js`，与本次构建产物一致，HSTS 与 Referrer-Policy 响应头仍在。
- 运行配置：容器内 `FORGEPILOT_AI_TEMPERATURE=0`、`FORGEPILOT_AI_TIMEOUT=120s`。
- 验证：本地 `/api/actuator/health` 为 UP，公网 200；以 dev01（新密码）调用一次实现建议，EMBEDDING 与 IMPLEMENTATION_GUIDANCE 均 SUCCESS，说明 provider 接受温度 0。
- 数据修正：项目 1 的名称去掉前导空格（`' mall-order-service'` → `'mall-order-service'`），与本次「新建项目去首尾空白」一致。
- 随后两轮稳定性复测各 37 个 `review-4` 审查全部完成，结果见 `docs/thesis/LIVE-EXERCISES.md` 第五节。
