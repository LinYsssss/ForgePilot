# P6 研发助手 — Implementation Plan

> 规划已批准并进入实施；下方复选框与验证记录反映当前工作区进度。

## Phase A — 契约与上下文纵切

- [x] 新增 assistant DTO、Controller 骨架和对象级授权测试。
- [x] 兼容扩展 ContextBuilder 的 ASSISTANT 请求/返回模型。
- [x] 实现 Requirement/AC/knowledge/link/code 上下文收集、来源标签、预算与降级警告。
- [x] 为 PR ref 增加项目内解析查询；commit/PR diff 读取复用既有 Git 边界。
- [x] 单测固定必选失败与可选降级的差异。

**Gate A：** 不调用模型也能得到完整、可预算、可解释的 Assistant ContextBundle。

## Phase B — Prompt、模型流与日志

- [x] 抽取共享 Prompt 脱敏工具，并证明现有 Agent golden 字节不变。
- [x] 注册 `assistant-v1` 并实现 AssistantPromptAssembler/golden tests。
- [x] 实现 AssistantModelClient、OpenAI-compatible 上游 SSE parser 与 mock 实现。
- [x] 新增专用有界 executor、SseEmitter 生命周期和 context/delta/done/error 事件。
- [x] 扩展 AiCallLogService 的 ASSISTANT 成功/失败口径。

**Gate B：** mock 模式可稳定流出多个 delta；上游失败不泄密且日志口径正确。

## Phase C — 前端内存会话与需求详情 UI

- [x] 扩展 API 层 POST stream 能力，复用 CSRF/401 规则。
- [x] 实现纯函数 SSE parser，并覆盖跨网络 chunk、CRLF、尾包和 error 事件。
- [x] 新增 useRequirementAssistant 单例及 ask/stop/retry/reset。
- [x] 在 RequirementsPaper 嵌入墨境助手面板、来源/降级提示和安全纯文本输出。
- [x] 接入项目/Requirement 切换和组件卸载清理；不得影响 Agent EventSource。

**Gate C：** 页面可完成提问、流式显示、停止、重试和切换清理。

## Phase D — 安全、兼容与全量检查

- [x] 补 ObjectLevelAuthorizationMatrixTest 和流式 MVC 测试。
- [x] 检查无写仓库、工具调用、会话落库和 Web Storage 通路。
- [x] 运行 backend focused tests 与 `mvn -s .mvn/settings.xml verify`。
- [x] 运行 `npm test`、`npm run build`。
- [x] 运行 `pwsh scripts/verify-local.ps1 -SkipSmoke`。
- [x] 运行 Trellis check，修复确认后的 finding；更新可复用 spec。

**Gate D：** A1–A8 全部满足，P6 形成可独立回滚的合批提交并归档。

## Risky files / rollback points

- `ContextBuilder`：必须保留 REQUIREMENT_CHECK 兼容构造和行为。
- `AgentPromptAssembler`：脱敏抽取不得改变既有 prompt 字节或 hash。
- `frontend/src/api/client.js`：401/CSRF 是冻结行为，新增流接口不得复制出漂移版本。
- `useWorkspace` reset 链：只追加 assistant reset，不改变既有 teardown 顺序。
- 模型流：专用执行器，禁止占用 Agent/RabbitMQ executor。

## Explicitly forbidden

- 不建聊天表或新增 Flyway。
- 不接 Agent tools/sandbox，不执行命令。
- 不写文件、不 apply patch、不 commit/push。
- 不使用 localStorage/sessionStorage。
- 不修改既有 Agent EventSource 协议。

## Implementation validation (2026-08-17)

- Trellis implement + full-scope check completed; confirmed findings were fixed.
- Backend `mvn -s .mvn/settings.xml verify`: PASS — 716 tests, 0 failures, 0 errors, 6 skipped.
- Frontend `npm test`: PASS — 78 tests.
- Frontend `npm run build`: PASS.
- `pwsh scripts/verify-local.ps1 -SkipSmoke`: PASS; backend/frontend/build passed, smoke intentionally skipped, Docker probe skipped because Docker command is unavailable.
- `git diff --check`: PASS (existing `.trellis/.template-hashes.json` line-ending warning only).
- Baseline test-only Mockito fixture defects in `RunGateVerdictServiceTest` and `FindingLifecycleServiceTest` were corrected without production behavior changes.
- Reusable POST SSE and assistant trust/lifecycle contracts were recorded in backend/frontend specs.
