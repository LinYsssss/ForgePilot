# 前端工作台化改造 · 部署记录(2026-10-17)

- **变更范围**:仅 `frontend/`(12 个 SFC/TS + `lib/datetime.ts`);无后端代码、无迁移、无配置改动。
- **命令**:`docker compose -p fp-demo up --build --detach --wait`(工作目录 `/root/ForgePilot`,不带任何 volume 参数)。postgres 容器与卷未动(启动时间仍 2026-10-07T16:11Z)。
- **镜像**:frontend `5f9a34819f18`(原 `6ae8b0cf…`)、backend `4528a33a09b9`(源码未变,`--build` 形式性重建)。
- **产物**:前端资产 `index-Be6F9YJh.js`(277.59 kB,gzip 91.02 kB)/ `index-g51Z8WF-.css`(71.82 kB,gzip 12.67 kB);本地 build、容器与公网三处逐字一致。
- **验证**:
  - 三容器 Healthy;loopback frontend 200、backend `{"status":"UP"}`;公网 `https://yasinlin.com/` 200 且入口脚本为同一哈希。
  - 入口脚本含新界面标记(「我的待办」「需求工作区」),旧英文标签(Project workbench / Review pipeline / AI delivery loop)0 处。
  - Flyway 仍 14 条迁移、无需迁移;测试账号只读冒烟(`GET /api/projects`)200。
- **门禁**:前端 lint / typecheck / vitest 66 项 / production build 全绿;后端未改,未重跑;66 项测试未因本次改造修改任何一行。
- **浏览器视觉核验(同日)**:用主机 Playwright chromium 对部署后的环境以 1680×1050 真实渲染两轮共 23 张截图(登录、工作台、研发需求列表与需求详情三标签、代码审查列表与详情、项目知识、仓库接入、项目、账户)。工作台(KPI/我的待办/右栏)、需求详情(元信息条 + 五标签)、审查两页、仓库与知识页均按新布局渲染,无 pageerror;仅未登录 401 与评审员访问钉钉通知配置的既有 403(页面按设计降级为"你可查看安全元数据…"提示,无错误态)。截图存 `screenshots-20261017/`(仅本地,未入库)。
- **回滚**:提交推送前的上一份工作树即 8b2a5fd;回滚 = 检出该提交并重跑同一条 compose 命令(前端镜像随之重建)。
- **备注**:三张原型图存于本目录 `prototypes/`(仅参考,未纳入版本控制)。
