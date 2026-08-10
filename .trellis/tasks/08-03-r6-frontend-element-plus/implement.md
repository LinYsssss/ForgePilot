# Implement：前端 Element Plus 迁移

0. [x] 环境：Node 22（服务器容器或 nvm；本机 Windows 需从 24 切换）；确认 r3 lockfile 已是官方源。（实际执行环境为部署服务器,一切 node/npm 走 node:22-alpine 容器,截图走 playwright:v1.48.0 容器）
1. [x] `ui-ux-promax` 流程定稿 design tokens（主色/灰阶/风险四级色/圆角/间距/字号/阴影）→ `tokens.css` + Element 变量覆盖 → 提交。（该 skill 本环境不存在,按等价流程执行;定稿见 design-tokens.md）
2. [ ] 引入依赖（element-plus / icons / unplugin×2）→ `npm audit --audit-level=high` → 空壳页验证按需导入生效 → 提交。
3. [ ] 逐页迁移（顺序：登录 → 项目列表 → 仓库/提交 → 对比审查 → PR 工作流 → 日志页 → 设置管理 → 报告页 → Agent 工作台/时间线；报告页与时间线最复杂放熟手后攻），每页走 design.md 四步法，一页一提交。
4. [ ] 全局收尾：AppShell/导航统一、Observatory 残余样式清理（grep aurora/glass 零命中）、三档宽度走查、可达性检查。
5. [ ] 终验：`npm ci && npm test && npm run build`、体积对比落档、CI 绿、服务器演示动线人工走查。
6. [ ] `trellis-check`（Agent）→ 提交推送 → `/trellis:finish-work`。

产物目录：`screenshots/`（每页前后对比）、`bundle-size.md`（体积对比）。
回滚点：单页提交粒度。
