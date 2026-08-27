# Frontend

ForgePilot 的 Vue 3 + TypeScript + Vite 前端，覆盖全部对外可用的后端工作流：
登录、显示名与改密、成员目录与多角色、用户 SCM 多身份、需求与验收条件、需求质量检查与一次性实现建议、项目知识与需求附件、
GitHub/GitLab 仓库接入、审查发现与结构化证据、Finding 审计与人工决策。

视觉方向为用户确认的 **Precision Review Console / 精密审查台**：单一深色分层界面、克制的玻璃面板、
青蓝强调色、紧凑元数据与密集证据工作区，并保留完整动态语言（交互粒子、光球、网格/扫描线、
雷达/激光、脉冲辉光、全息边框、shimmer 与页面转场）；系统请求减少动态效果时停止持续动画。
所有数据、权限、路由与错误状态都以真实 API 为准，不引入 mock 数据。

## 命令

```bash
npm ci
npm run lint        # 前端基线策略检查（scripts/lint.mjs）
npm run typecheck   # vue-tsc 严格模式
npm run test -- --run
npm run build
```

四条命令全部通过，测试为 **14 个文件 / 42 个测试**，
产物 JS 236.80 kB、CSS 66.43 kB。

## 信息架构

一级导航固定为六个入口，排布在桌面顶部应用栏：横版 Logo 在左、六个入口在页面水平中心、
账户操作在右；`64rem` 以下变为两行且导航可横向滚动，不隐藏任何入口。

- 工作台 `/workspace`
- 项目 `/projects`
- 研发需求 `/requirements`
- 项目知识 `/knowledge`
- 仓库接入 `/repositories`
- 代码审查 `/reviews`

另有非一级账户页 `/account`，以及四条详情/兼容路径：`/projects/:id/members`、`/requirements/:id`、`/reviews/:id`、
重定向到仓库接入的 `/projects/:id/settings`。产品路由共 11 条；`/login` 是认证入口，不计入产品路由。

品牌规则：同一页面只出现一种可见 Logo——已登录 Shell 用 `public/brand/logo-lockup.png`，
登录页用 `public/brand/logo-app.png`，后者同时是 favicon。

## 边界

- 不为底层技术能力新增一级菜单：Metrics、Agent、Patch、AI Logs 都不是一级页面。
- AI 能力只出现在三段上下文内：需求详情的质量检查与一次性实现建议、审查详情的 Finding；
  不创建通用 AI/Assistant 入口、聊天框或第二条运行管线。
- 工作台只在浏览器端组合现有列表 API，是只读总览；不虚构指标、评分或运行状态。
- 项目知识页展示真实文档/Chunk 数、已嵌入数、向量维度与 Embedding Profile，**不返回也不展示原始向量**。
- AI 置信度、Finding 人工状态、Review Decision、跨轮血缘、需求状态与派生的评审活动，
  在 UI 上必须是彼此独立的标签，不得合并。
- 仅两个运行时依赖：`vue`、`vue-router`。新增依赖需要举出它能挡住的真实故障。

视觉与动效契约、组件规范和质量门槛定义在 `.trellis/spec/frontend/`，本文件不复述。
自动化测试不能替代真实浏览器验收，人工清单见 [MANUAL-ACCEPTANCE.md](./MANUAL-ACCEPTANCE.md)。
