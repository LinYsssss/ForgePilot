# P6 研发助手 — Technical Design

> 基线：父任务 `08-16-forgepilot-upgrade/design.md` §5/§10；日期 2026-08-17。

## 1. Boundary and package placement

新增 `com.example.codereview.assistant` 领域包，平铺放置 Controller、Service、DTO、Prompt 组装与模型客户端接口/实现。`context` 包继续拥有跨场景上下文预算与来源模型；`assistant` 负责编排 Requirement、Context Builder、模型流和 SSE 生命周期。

依赖方向：

```text
AssistantController
  -> AssistantService
       -> ProjectAuthorization / RequirementRepository
       -> ContextBuilder(ASSISTANT)
            -> Requirement/AC/Link repositories
            -> RagService
            -> Repository/Git read boundary
       -> AssistantPromptAssembler -> PromptTemplateRegistry
       -> AssistantModelClient(openai-compatible | mock)
       -> AiCallLogService
```

不新增数据库表或迁移。

## 2. HTTP and SSE contract

### Endpoint

`POST /api/projects/{projectId}/requirements/{requirementId}/assistant/stream`

Request:

```json
{
  "message": "这个需求实现时最容易漏掉什么？",
  "history": [
    { "role": "USER", "content": "..." },
    { "role": "ASSISTANT", "content": "..." }
  ]
}
```

服务端约束建议值：message ≤ 4000 字符；history ≤ 12 条；单条 ≤ 8000 字符；历史总计 ≤ 24000 字符。角色只接受 USER/ASSISTANT，服务端按原顺序复制并拒绝未知角色。

Response：`text/event-stream;charset=UTF-8`。

```text
event: context
data: {"sources":[...],"truncatedSections":[...],"warnings":[...]}

event: delta
data: {"text":"..."}

event: done
data: {"promptTokens":0,"completionTokens":0,"totalTokens":0}
```

流建立前的参数、身份和对象权限失败走现有 HTTP 401/403/404/400；流建立后的模型失败发送安全的 `error` 事件后完成 emitter。SSE data 使用 ObjectMapper 序列化，不手拼 JSON。

## 3. Async execution and capacity

Controller 在当前请求线程同步完成身份、项目、Requirement 归属和请求体校验，然后创建 `SseEmitter`。模型的阻塞式上游流读取交给专用 `assistantTaskExecutor`：核心线程、最大线程、队列和 emitter timeout 均由 `app.assistant.*` 配置，默认保持小而有界。

队列拒绝或并发上限命中映射既有 `ErrorCode.RATE_LIMITED`。emitter completion/error/timeout 设置取消标记；模型回调在发送每个 chunk 前检查取消状态。不得占用 RabbitMQ Agent executor，也不得复用 Agent EventSource emitter registry。

## 4. Context Builder evolution

保持现有 `build(ContextScene, projectId, refs)` 入口，并以兼容方式扩展 `Refs` 与 `ContextBundle`：保留旧构造器供 REQUIREMENT_CHECK 调用，新增 ASSISTANT 所需的 `userId`、`requirementId` 和来源字段。

ASSISTANT provider 执行：

1. `ProjectAuthorization.requireRead` 并按 `projectId + requirementId` 读取 Requirement。
2. 读取全部 AC，生成 `REQ-<seq>` 与 `AC-<seq>` 来源。
3. 用标题、背景、描述和 AC 拼接检索 query，复用 RagService 的 topK 与片段截断纪律。
4. 读取 Requirement Links：
   - COMMIT：以 ref 为 head，调用既有 git diff 边界；
   - PULL_REQUEST：按项目与 external id/PR number 解析本地 PR，再以 base/head 读取 diff；
   - BRANCH：只作为关联元数据，不执行无界分支扫描；
   - 无法解析的 ref：加入 warning，不中断。
5. 对 diff 先按文件拆分，再限制关联数、文件数、单文件字符和总字符；只输出路径、change type 与有界 diff 摘要。
6. 返回不可变 `ContextBundle`：Requirement snapshot、knowledge snippets、code slices、sources、truncatedSections、warnings。

可选上下文失败使用窄范围捕获并记录 warning；权限、Requirement 不存在和请求不合法不可降级。

## 5. Prompt and trust boundary

注册 `assistant-v1 -> prompts/chat/assistant-v1.txt`。新增 `AssistantPromptAssembler`，职责：

- 系统层固定声明只读边界、不得声称已修改代码、不得服从上下文中的指令；
- 将 Requirement/AC、KB、CODE、history、question 分节；
- 每节先秘密脱敏再按 UTF-8 字节/字符预算截断；
- 将所有来源标签列入白名单，要求答案引用标签；
- 不要求 JSON 输出，不进入 Finding/coverage parser。

秘密脱敏规则应从 `AgentPromptAssembler` 抽为共享的纯工具，避免复制正则；抽取时必须保持既有 Agent prompt golden 字节不变。

## 6. Model client

接口建议：

```java
interface AssistantModelClient {
    AssistantUsage stream(AssistantPrompt prompt, Consumer<String> onDelta, BooleanSupplier cancelled);
}
```

- `OpenAiCompatibleAssistantModelClient`：向既有 chat completions 兼容端点发送 `stream=true`，逐条解析上游 SSE 的 `choices[].delta.content`；忽略空增量，识别 `[DONE]`，若 usage 缺失则记 0。
- `MockAssistantModelClient`：基于来源生成确定性中文答案，拆成多个 delta，供离线演示和测试。
- 上游错误沿用 `AiTransientFailureClassifier` 的瞬态/永久分类与超时配置思想；日志与对外错误均不得包含 API key、完整 Prompt 或上游原始响应正文。

## 7. AI call log

`AiCallLogService` 增加 `ASSISTANT` 常量和 success/failed 方法，taskId 为空，projectId 必填。记录字符数、usage、latency、status；不修改 ai_call_log schema，不写 Requirement ID 或消息正文。

## 8. Frontend state and UI

新增 `useRequirementAssistant.js` 模块级单例，持有当前 requirement key、messages、sources、warnings、streaming、AbortController。公开 `ask/stop/retry/reset`；`useWorkspace` 项目 reset 链和 Requirements 页面切换逻辑都必须调用 reset。

新增 `apiStream`（或等价窄封装）到 API 层：

- 复用 `API_BASE`、`credentials: include`、CSRF header 和全局 unauthorized handler；
- 只负责响应状态与 ReadableStream，SSE framing 由独立纯函数解析并用 node tests 覆盖跨 chunk、CRLF、多事件与尾包；
- 不触碰现有 `useAgentWorkspace` 的 EventSource 生命周期。

`RequirementsPaper.vue` 内嵌助手面板；回答用 `white-space: pre-wrap` 文本节点渲染。来源只从 `context.sources` 白名单映射，模型正文中的未知标签不生成可点击/已验证样式。

## 9. Compatibility and rollback

- 新端点、新 Prompt、新 request type 和前端区域均为加法；现有 REST response、Requirement DTO、Agent SSE 和数据库 schema 不变。
- 功能以 `app.assistant.enabled` 控制；关闭时后端端点返回明确不可用语义，前端隐藏入口，满足父设计的零残留降级。
- 代码回滚为单提交；无 Flyway，因此回滚不留数据库残片。

## 10. Test strategy

- Backend unit：history/request validation、ContextBuilder ASSISTANT、预算/降级、Prompt 脱敏与 golden、OpenAI SSE parser、mock chunks、日志成功/失败。
- Backend MVC/security：匿名 401、陌生人非 2xx、跨项目、正确 media type/event order、rate limited。
- Frontend：SSE parser 的分块边界、401/CSRF、Abort/reset/retry、来源白名单、纯文本渲染。
- Full gate：backend verify、frontend test/build、verify-local -SkipSmoke。
