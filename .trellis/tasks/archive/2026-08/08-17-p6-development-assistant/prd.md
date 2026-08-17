# P6 研发助手

> 父任务 `.trellis/tasks/08-16-forgepilot-upgrade`（R8、design §5/§10、implement P6）。
> 规划基线日期：2026-08-17。用户已确认 P6–P9 全部保留，不采用裁剪方案。

## Goal

在需求详情页内提供一个**只读、可降级、带来源引用的流式研发助手**。助手使用当前 Requirement、AC、项目知识库以及需求已关联的代码上下文回答实现与澄清问题，帮助开发者理解需求和定位相关代码，但不具备修改文件、生成并应用补丁、commit、push 或调用实时沙箱工具的能力。

## Confirmed facts

- `ContextScene.ASSISTANT` 已预留，但 `ContextBuilder` 当前仅实现 `REQUIREMENT_CHECK`，其他场景会显式抛错。
- Requirement 详情、AC、体检报告和代码关联已经集中在 `/requirements` 墨境页面与 `useRequirements` 单例中。
- `requirement_link` 已支持 `BRANCH / COMMIT / PULL_REQUEST`；绑定仓库可通过既有 `GitCliService` 读取 commit/PR 对应 diff。
- 后端已有 OpenAI-compatible 与 mock AI 两条运行路径、版本化 Prompt 注册表、AI 调用日志和 Agent SSE 先例。
- 前端凭证只使用 HttpOnly Cookie；禁止新增 localStorage/sessionStorage。原生 `EventSource` 无法携带 POST JSON，因此本功能应使用带凭证的 `fetch` 流式读取，而不是改变既有 Agent EventSource 生命周期。

## Requirements

### R1 — 入口与授权

- 在需求详情中嵌入「研发助手」区域，不新增独立一级路由。
- 所有项目成员（LEADER / DEVELOPER / REVIEWER）均可只读提问。
- 服务端必须同时校验项目读权限以及 `requirementId` 属于路径中的 `projectId`；匿名请求为 401，陌生项目成员不得获得 2xx。

### R2 — 请求与流式契约

- 新增 `POST /api/projects/{projectId}/requirements/{requirementId}/assistant/stream`，请求体包含当前问题和有界会话历史。
- 响应使用 UTF-8 `text/event-stream`，事件类型固定为：
  - `context`：本轮使用的来源、截断信息和降级警告；
  - `delta`：增量回答文本；
  - `done`：正常结束及可用的 usage 摘要；
  - `error`：流建立后的可展示错误，不泄露密钥、Prompt 原文或堆栈。
- 前端新增可复用的带凭证 POST 流读取能力，沿用全局 401 与 CSRF 规则；不得修改已被测试钉死的 Agent `EventSource` 行为。

### R3 — ASSISTANT 上下文

- 扩展场景化 Context Builder，使 `ASSISTANT` 成为显式支持的场景，不允许以空上下文静默冒充成功。
- 必选上下文：Requirement 编号、标题、背景、描述、状态和全部 AC。
- 可选上下文：
  - 项目知识库检索结果；
  - `COMMIT` 关联的 diff 摘要；
  - 可解析 `PULL_REQUEST` 关联的 base/head diff 摘要；
  - `BRANCH` 和无法解析的关联作为引用元数据保留。
- 所有段落必须有字符/字节与条数预算。仓库未绑定、ref 不可解析或单个代码上下文读取失败时，保留 Requirement + AC 主链并在 `context` 事件中标注降级，不因可选来源失败而让整轮提问失败。
- 来源使用稳定引用标签：`REQ-*`、`AC-*`、`KB:*`、`CODE:*`，供模型回答和 UI 展示。

### R4 — Prompt 与模型边界

- 新增版本化 `assistant-v1` Prompt，并经 `PromptTemplateRegistry` / 唯一组装入口生成；Prompt 变更遵守 prompt-management 五规则和 golden 测试纪律。
- 将 Requirement、知识片段和代码片段视为不可信上下文，明确隔离其内部指令；沿用秘密脱敏和预算截断纪律。
- 使用独立 `AssistantModelClient` 流式接口，提供 OpenAI-compatible 与 mock 实现；不复用要求结构化 Finding JSON 的 `AiReviewClient`。
- 助手只输出文本与来源引用，不暴露工具协议，不提供任何服务端写操作入口。

### R5 — 会话与前端体验

- 会话只保存在当前浏览器页面内存中，不建会话表，不写 Web Storage；切换项目或 Requirement 时清空。
- 前后端均限制单条消息、历史条数和历史总字符数；后端不信任前端裁剪结果并再次校验。
- UI 支持：空态建议问题、发送、流式追加、停止生成、失败后重试、来源列表、上下文降级提示。
- 输出按纯文本安全渲染（保留换行），不直接使用 `v-html`；本 Phase 不引入 Markdown/代码高亮依赖。
- 同一需求同一时刻只保留一个前端请求；新请求、切换需求和组件卸载均中止旧流。

### R6 — 日志、容量与失败语义

- `ai_call_log.request_type` 新增 `ASSISTANT` 口径，记录 project、字符数、可获得的 token、耗时、SUCCESS/FAILED；不落完整问题、回答、会话历史或 Prompt 正文。
- 流式模型调用使用专用有界执行器/并发上限，容量耗尽返回既有 `RATE_LIMITED` 语义，防止长连接耗尽通用请求线程。
- mock 模式必须可离线演示并稳定产生多个 `delta` 事件和来源引用。

## Acceptance Criteria

- [ ] A1：任意项目成员可在 Requirement 详情提问；匿名为 401，陌生人请求他人 Requirement 永不 2xx，跨项目 Requirement 不泄露。
- [ ] A2：一次成功请求按 `context → delta... → done` 输出；前端可逐段显示、停止并重试，切换项目/需求后旧会话和旧请求被清理。
- [ ] A3：模型输入包含当前 Requirement、全部 AC 和可用知识/代码上下文；回答展示至少一个稳定来源引用，伪造或未知来源不会被 UI 当作已验证来源展示。
- [ ] A4：关联 commit/PR 的代码摘要受预算约束；仓库未绑定、ref 失效或知识检索失败时按设计降级且 Requirement + AC 问答仍可使用。
- [ ] A5：OpenAI-compatible 与 mock 均实现相同流式接口；mock 离线路径可重复演示；模型/流异常以 `error` 结束并正确记录失败日志。
- [ ] A6：代码中不存在助手触发文件写入、patch apply、commit、push、实时沙箱工具或服务端会话持久化的通路。
- [ ] A7：Prompt 注册、预算、脱敏和不可信上下文隔离有 golden/单元测试；AI 日志新增 ASSISTANT 成功与失败用例。
- [ ] A8：后端授权/服务/流式契约测试、前端 composable/组件测试通过；最终执行 `mvn -s .mvn/settings.xml verify`、`npm test`、`npm run build` 和 `pwsh scripts/verify-local.ps1 -SkipSmoke`（若用户再次要求跳过测试，则如实记录未执行项）。

## Out of scope

- 自动生成并应用代码修改、自动 commit/push、PR 创建或任何写仓库能力。
- 实时沙箱、Agent Tool Registry、命令执行或外部搜索工具调用。
- 服务端聊天会话/消息表、跨设备历史、分享会话、长期记忆。
- 独立聊天页面、全局悬浮助手、语音/图片输入、Markdown 渲染依赖。
- P7 工作台/度量重构、P8 实验与品牌截图、P9 远程仓库改名。

## Dependencies and ordering

- 依赖已完成的 P1b Requirement 域、P2 Context Builder/体检、P3 Requirement Link 和 P5 权限/质量闭环基础。
- P6 完成后进入 P7；P8 在 P6/P7 与既有 L 线任务完成后执行；P9 严格位于 P8 之后。
