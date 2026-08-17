# Assistant Streaming Contracts

> ForgePilot P6（2026-08-17）建立的只读研发助手契约。适用于 `assistant/`、
> `ContextBuilder.ASSISTANT`、`PromptSanitizer`、OpenAI-compatible 流解析以及前端 POST SSE 消费者。

## 1. Scope / Trigger

出现以下任一变更时必须按本规范执行，并同步后端、前端与契约测试：

- 修改 `/api/assistant/config` 或 Requirement assistant stream 端点；
- 增删 `context / delta / done / error` 事件或字段；
- 修改 assistant history、Prompt、来源标签、上下文预算或降级规则；
- 修改 OpenAI-compatible 上游 SSE 解析、并发/超时配置或 AI 日志口径；
- 新增其他需要携带 JSON 请求体的流式 AI 功能。

助手是**只读能力**：不得依赖 Agent Tool Registry、sandbox、文件写入、patch apply、commit 或 push。

## 2. Signatures

### HTTP

```http
GET /api/assistant/config
POST /api/projects/{projectId}/requirements/{requirementId}/assistant/stream
Content-Type: application/json
Accept: text/event-stream
```

```java
record StreamRequest(
    @NotBlank @Size(max = 4000) String message,
    @Valid @Size(max = 12) List<HistoryMessage> history
) {}

record HistoryMessage(
    @NotBlank @Size(max = 16) String role,
    @NotBlank @Size(max = 8000) String content
) {}
```

核心内部签名：

```java
SseEmitter AssistantService.stream(
    Long projectId, Long requirementId, Long userId, StreamRequest request);

TokenUsage AssistantModelClient.stream(
    AssistantPrompt prompt, Consumer<String> onDelta, BooleanSupplier cancelled);
```

### 配置

```yaml
app.assistant.enabled
app.assistant.max-concurrent
app.assistant.emitter-timeout-ms
app.assistant.executor.core-size
app.assistant.executor.max-size
app.assistant.executor.queue-capacity
app.assistant.history.max-total-chars
app.assistant.prompt.max-context-chars
app.assistant.context.max-query-chars
app.assistant.context.max-code-links
app.assistant.context.max-code-files
app.assistant.context.max-code-file-chars
app.assistant.context.max-code-total-chars
```

生产环境变量使用对应的 `ASSISTANT_*` 名称，默认值见 `config/app-agent.yml`。

## 3. Contracts

### 同步前置检查

Controller 返回 emitter 前必须同步完成：

1. 当前用户存在；
2. `app.assistant.enabled=true`；
3. `ProjectAuthorization.requireRead(projectId, userId)`；
4. Requirement 按 `projectId + requirementId` 命中；
5. history 角色、条数、单条长度和总长度合法；
6. Prompt/context preflight 成功；
7. 并发 permit 与有界 executor 接受任务。

这些失败必须保持普通 HTTP 错误，不能先提交 200 SSE 再发送业务错误。

### SSE

响应必须是：

```text
text/event-stream;charset=UTF-8
```

成功顺序固定：

```text
context -> delta (1..N) -> done -> complete
```

流建立后的失败：

```text
context? -> delta* -> error -> complete
```

事件 payload：

```json
{"sources":[{"id":"KB:1","type":"KNOWLEDGE","title":"...","ref":"..."}],
 "truncatedSections":[],"warnings":[]}
{"text":"增量文本"}
{"promptTokens":0,"completionTokens":0,"totalTokens":0}
{"errorCode":"AI_CALL_FAILED","message":"AI 调用失败"}
```

事件 data 必须由 ObjectMapper/Spring 序列化，禁止字符串拼 JSON。

### 来源与信任

- Requirement 与所有 AC 是必选来源；Prompt 预算不能静默删除 AC。
- knowledge/code/link 是可选来源，失败写入 warning 后继续 Requirement + AC 主链。
- `context.sources` 只能包含**最终 Prompt 实际使用**的来源白名单，不能把被预算裁掉的来源展示为已验证。
- 外部文件名、ref、知识内容、history、question 进入检索或 Prompt 前都要经过 `PromptSanitizer` 与预算。
- 来源 ID 使用服务端生成的稳定/不透明标识，禁止把可能含 secret 的原始 ref 或文件名拼进 ID。

### 生命周期与容量

- 模型流只能运行在 `assistantTaskExecutor`，禁止复用 Agent/MQ executor。
- permit、executor reject 均映射 `RATE_LIMITED`。
- completion/error/timeout 设置取消标记；取消必须释放 permit，并记录失败/取消日志。
- AI 日志失败不得改变已经完成的 SSE 协议，尤其不能在 `done` 后补发 `error`。
- `ai_call_log.request_type=ASSISTANT`，不落完整问题、回答、history 或 Prompt 正文。

## 4. Validation & Error Matrix

| 场景 | 结果 |
|---|---|
| 匿名请求 | HTTP 401，不能提交 SSE 200 |
| 非项目成员 | HTTP 403/404，永不 2xx |
| Requirement 属于其他项目 | HTTP 404/403，不泄露对象存在性 |
| 功能关闭 | HTTP `SERVICE_UNAVAILABLE` |
| message 空/过长 | HTTP BAD_REQUEST/Bean Validation |
| history 角色不是 USER/ASSISTANT | HTTP BAD_REQUEST |
| history 总字符超限 | HTTP `PAYLOAD_TOO_LARGE` |
| permit/executor 已满 | HTTP `RATE_LIMITED` |
| 仓库未绑定、ref 失效、知识检索失败 | SSE context warning，主问答继续 |
| 上游模型在流中失败 | SSE `error(AI_CALL_FAILED)` 后 complete |
| 浏览器取消/断连 | 停止发送、释放容量、写取消日志；不得再发 done |
| AI 日志写失败 | 只记服务端 warn，不改变 SSE 成功/失败结果 |

## 5. Good / Base / Bad Cases

### Base

只有 Requirement + AC，无知识库和代码关联：仍输出 `context`、至少一个 `delta`、`done`。

### Good

Requirement、AC、知识和 commit/PR diff 均可用；Prompt 使用的来源与 `context.sources` 完全一致，回答引用白名单标签，usage 写入 AI 日志。

### Bad

- 把所有检索结果直接放进 `context.sources`，即使 Prompt 已截断；
- 先返回 emitter，再在后台做权限检查；
- 用原始 branch/ref/文件名生成来源 ID；
- knowledge 检索 query 无边界且未脱敏；
- 在公共 HTTP 或 RabbitMQ executor 上执行长时间模型流；
- 把取消当成功记录，或在 `done` 后因日志写失败再发 `error`。

## 6. Tests Required

后端变更至少断言：

- 匿名 401、陌生人永不 2xx、跨项目 Requirement 拒绝；
- `Content-Type` 明确 UTF-8，中文 delta 不乱码；
- `context -> delta+ -> done` 顺序；
- history 角色/总长度、功能关闭、容量耗尽错误；
- Requirement/全部 AC 保留，optional context 失败降级；
- query/source/title/ref 脱敏与预算，来源白名单等于 Prompt 实际来源；
- OpenAI SSE 跨 chunk、CRLF、`[DONE]`、错误帧；
- 取消、成功、失败日志，日志自身失败不污染 SSE；
- 既有 Agent Prompt golden 字节保持不变。

跨层同时运行：

```powershell
cd backend; mvn -s .mvn/settings.xml verify
cd ../frontend; npm test; npm run build
cd ..; pwsh scripts/verify-local.ps1 -SkipSmoke
```

## 7. Wrong vs Correct

### Wrong

```java
SseEmitter emitter = new SseEmitter();
executor.execute(() -> {
    authorization.requireRead(projectId, userId); // 200 已可能提交
    // ...
});
return emitter;
```

### Correct

```java
authorization.requireRead(projectId, userId);
ContextBundle context = contextBuilder.build(ContextScene.ASSISTANT, projectId, refs);
AssistantPrompt prompt = promptAssembler.assemble(context, request);
acquireCapacityOrThrow();
return submitBoundedStream(prompt); // 只有 preflight 成功才返回 emitter
```
