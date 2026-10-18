# 前端三轮改造 · 推送与部署记录(2026-10-18)

- **推送**:`8b2a5fd..4fe7556`,13 个提交,分三组:
  - 工作台化(4):`a1fe629` 工作台队列/KPI/右栏、`4448261` 需求详情五标签、`0d09994` 去装饰标签、`1102296` 计划与部署记录(细节见 `10-17-workbench-ia/`)。
  - 密度与对齐(8):`1b23f9e` 会话区下拉、`0fce7a5` 待办定高内滚、`70e3c16` 知识/仓库/需求紧凑化+全局密度、`fc5cc49` AI 两栏对齐与文本 trim、`9b8c586` 两栏真对齐(网格行修复)、`1d6dae0` 列表一行式与内滚、`9c8f833` 第三轮记录、`4fe7556` 活动概览内滚。
  - 计划文档 1:`364a8c2` 密度优化记录。
- **部署**:10-18 08:41 UTC `docker compose -p fp-demo up --build --detach --wait`;backend 镜像 `4b019e7bc18d`、frontend `bcbe64b1dd99`;postgres 容器与卷未动(启动时间仍 2026-10-07T16:11Z)。
- **验证**:三容器 healthy;loopback 18081、backend health 与公网 `https://yasinlin.com` 均 200;入口资产 `index-BGRpBKmH.js` 本地与公网逐字一致;Flyway 仍 14 条迁移、无数据库变更。界面以 Playwright 真实浏览器核验(截图在 `10-17-workbench-ia/screenshots-20261017/`、`10-17-ui-density-align/screenshots-20261017|20261018/`,仅本地)。
- **CI**:run [39112403114](https://github.com/LinYsssss/ForgePilot/actions/runs/39112403114) 四个 job 全绿(Backend verify / Frontend gates / Evaluation contract / Empty-stack Compose smoke)。
- **回滚**:上一线上版本为 `5973d48`(10-17);回滚 = 检出该提交并重跑同一条 compose 命令。
