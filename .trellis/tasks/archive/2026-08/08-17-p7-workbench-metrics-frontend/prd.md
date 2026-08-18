# P7 工作台、研发度量与前端收尾

> 父任务 `08-16-forgepilot-upgrade`（R9/R10、design §12、implement P7）。
> 用户已确认本 Phase 全量实施，不采用度量简化裁剪。规划基线：2026-08-17。

## Goal

完成项目工作台、四组研发度量和全部剩余旧壳页面的墨境迁移，收敛为父设计定义的八区信息架构。工作台与度量必须使用已落库的统一事实源，所有旧 URL 保持可达，但不再长期维护双份页面或双份业务逻辑。

## Confirmed facts

- 墨境视觉方向、tokens、shell、响应式和动效约束已经由 `08-12-frontend-guofeng-cyber-redesign` 冻结，本任务只扩展既有设计系统。
- 当前仅 dashboard/projects/repository/requirements/quality 和临时 `/ink` 使用墨境；pull-requests/knowledge/reviews/agent/ai-logs 仍使用旧壳。
- P4b 已将 `/reviews`、`/agent`、`/pull-requests` 的迁移明确顺延到 P7，因此它们属于本任务范围。
- Requirement 和 Finding 可产生真实“分配给当前用户”的队列；Pull Request 没有 reviewer assignment 字段，只能基于项目角色和 reviewState 形成待审队列。
- 现有时间戳可以准确计算审查耗时、Agent 端到端耗时和 Finding 验证耗时；Requirement 没有状态历史，不能把 updatedAt 冒充交付周期。
- AI UI 聚合应以 `ai_call_log` 为事实源；Prometheus 用于运维抽查，不能与数据库聚合成第二套展示真源。
- 详细审计见 `research/current-state-audit.md`。

## Requirements

### R1 — 项目工作台

- `/dashboard` 从通用数量总览升级为项目工作台。
- 展示三个有界队列：
  - 我的研发任务：当前用户为 assignee，状态非 DONE/CANCELED；
  - 待我处理 Finding：当前用户为 assignee，生命周期非 CLOSED/REJECTED；
  - 待审 PR：项目 OPEN 且 reviewState 为 PENDING/CHANGES_REQUESTED；没有 reviewer assignment 时不得声称数据来自逐 PR 指派。
- 同页展示风险摘要与最近活动；每项可跳到对应详情并定位对象。
- 使用专用服务端 workbench projection，不在浏览器拉取所有领域列表后自行 join。

### R2 — 四组研发度量

- 新建 `/metrics` 墨境页面，支持 7/30/90 天窗口，默认 30 天，并显示窗口、样本数和无数据状态。
- 研发质量：Gate PASS/WARN/BLOCK、Finding 严重度/活跃高危/闭环率、AC coverage verdict。
- 需求质量：Requirement 状态、AC 数量、体检覆盖率、最新六维报告问题分布；禁止发明不存在的综合分数。
- 处理效率：ReviewTask 执行耗时、Agent 端到端耗时、Finding 验证耗时；禁止用 Requirement.updatedAt 冒充交付周期。
- AI 指标：调用数、成功率、token、平均/P95 延迟、requestType 分布；数据来自 ai_call_log。
- 历史 JSON 解析失败需 fail-soft 并返回 excluded count，不得让整个指标页 500。

### R3 — 目标信息架构与兼容路由

最终一级区固定为：工作台、项目、研发任务、代码仓库、智能审查、质量中心、知识库、研发度量。

- `/repository` 合并 Pull Request 工作流；旧 `/pull-requests` 保留兼容跳转。
- 智能审查合并 Agent run 与交互式 Review/Report，复用现有 InkAtelier 纵向切片和 review composables。
- `/metrics` 合并 AI 日志明细；旧 `/ai-logs` 保留兼容跳转。
- `/knowledge` 原路径不变，只替换为墨境页面。
- 旧 URL、路由 query 和 `agent-evidence` 深链必须有行为测试。

### R4 — Knowledge 墨境迁移

- 保留上传、文档类型、状态、重建索引、删除和检索测试能力。
- LEADER/DEVELOPER 可上传，LEADER 可删除；前端裁剪仅作体验，后端继续裁决。
- 继续复用 `useKnowledge`，不复制请求逻辑；补完整 loading/empty/error/permission/long-content 状态。

### R5 — AI Logs 并入度量

- 复用现有 `useAiLogs` 与分页端点，在 Metrics 的 AI 区提供日志分组、翻页和详情。
- 从 Review/Agent 的“查看 AI 日志”动作跳转到 `/metrics?section=ai&taskId=...`，保持任务维度。
- 旧 `/ai-logs` 路由重定向到新的 AI section。

### R6 — Legacy shell retirement

- 所有业务路由迁入 Ink shell 后删除无引用的旧 views、旧 `AppShell` 与重复样式/导航逻辑。
- 仅在 router/import/behavior tests 证明零消费者后删除；共享业务组件若仍被新页面使用则保留并迁位，不按目录名粗暴删除。
- `App.vue` 保留全局 CSRF/session/401 生命周期，但移除不可达的旧壳分支。

### R7 — Backend contracts and performance

- 建议新增：
  - `GET /api/projects/{projectId}/workbench?limit=6`
  - `GET /api/projects/{projectId}/metrics?window=30d`
- 两端点均要求项目 read 权限；workbench embedded lists 有服务端上限，metrics window 使用固定枚举而非任意无界日期。
- 不新增冗余事实表；可用 V36 添加经查询形状证明需要的复合索引。
- 新带 ID 的详情定位继续进入对象级授权矩阵；分页明细沿用冻结 `PageResponse`。

### R8 — UI quality gate

- 复用墨境 token 与组件，禁止建立第二套图表颜色、风险颜色或导航源。
- 图表优先使用 CSS/SVG 原生轻量实现；不为 P7 引入大型图表依赖，除非现有实现无法满足可访问性且另行批准。
- 覆盖 390/768/1440、键盘/focus-visible、44px 触控、reduced motion、loading/empty/error/permission/long-content。
- 工作台和指标页的零值、无样本、解析排除和请求失败必须视觉上可区分。

## Acceptance Criteria

- [ ] A1：工作台三个队列与 Requirement/Finding/PR 详情页使用相同谓词；点击后定位正确对象。
- [ ] A2：四组指标在 7/30/90 天窗口下返回可解释结果，显示窗口、样本数和 excluded count；抽样与数据库事实一致。
- [ ] A3：AI 指标与 ai_call_log 抽查一致，详细日志和 taskId 深链可用；Prometheus 口径仅作运维交叉验证。
- [ ] A4：Knowledge 全功能迁墨境，角色裁剪与后端权限一致。
- [ ] A5：Repository/PR、智能审查、Metrics/AI Logs 完成合并；旧 URL 与 evidence 深链兼容。
- [ ] A6：所有主路由进入 Ink shell，旧壳与旧 views 无引用后删除，不存在双份业务逻辑。
- [ ] A7：对象级授权、window/limit 校验、JSON fail-soft、查询上限和必要索引有测试。
- [ ] A8：390/768/1440、键盘、焦点、触控、reduced motion、空/错/权限/长内容状态通过 QA。
- [ ] A9：backend `mvn -s .mvn/settings.xml verify`、frontend `npm test && npm run build`、`pwsh scripts/verify-local.ps1 -SkipSmoke` 全部通过。

## Out of scope

- 新增 PR reviewer assignment 数据模型或“逐 PR 指派审查人”。
- Requirement 状态历史/交付周期表；没有可靠事实时不展示该指标。
- 新数据库事实表、数据仓库、OLAP、Grafana 内嵌或前端直接查询 Prometheus。
- 改变 P1–P6 已冻结的领域状态机、Gate、Finding、Requirement 或 AI 日志语义。
- P8 实验/答辩材料与 ForgePilot 品牌切换；P9 GitHub 仓库改名。

## Decision record

- D1（用户确认，2026-08-17）：**严格遵循父计划**。保留 `/dashboard` 作为唯一工作台；把现有 `InkAtelierPage` 的 Agent 审查实现迁入正式 `/agent` 智能审查路由；`/reviews` 与 `/agent` 合并；临时 `/ink` 跳转到 `/dashboard`。
- D2：`/pull-requests` 并入 `/repository?section=pull-requests`；`/ai-logs` 并入 `/metrics?section=ai`；旧 URL 保留兼容重定向。
- D3：P7 不新增 PR reviewer assignment，也不伪造 Requirement 交付周期或需求综合分；只展示现有事实源可证明的队列和指标。
