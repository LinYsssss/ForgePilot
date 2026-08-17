# Streaming API and In-Memory Assistant State

> ForgePilot P6（2026-08-17）建立的前端 POST SSE 契约。适用于 `apiStream`、
> `readSseStream` 与 `useRequirementAssistant`；既有 Agent `EventSource` 生命周期不在本规范内，不得顺手改动。

## 1. Scope / Trigger

当功能需要“带 JSON body 的流式响应”或修改研发助手会话状态时使用本规范。原生 `EventSource` 只适合 GET；需要 POST/history/CSRF 时必须走 credentialed fetch stream。

## 2. Signatures

```js
apiStream(path, { method = 'POST', headers, body, signal }) -> Promise<ReadableStream>
readSseStream(readable, onEvent) -> Promise<void>
buildAssistantHistory(items, limits?) -> Array<{ role, content }>
useRequirementAssistant() -> {
  enabled, messages, sources, warnings, truncatedSections, streaming,
  loadConfig, ask, stop, retry, reset, ensureKey
}
```

## 3. Contracts

### HTTP 边界

`apiStream` 必须与普通 `api()` 同源：

- `${API_BASE}${path}`；
- `credentials: 'include'`；
- 非安全方法从当前 Cookie 现读 CSRF token；
- 401 调用唯一的全局 unauthorized handler；
- 非 2xx 解析 ApiResponse 错误；
- 成功时要求 `response.body` 存在。

禁止在 composable 内直接裸调 fetch，避免 CSRF/401 行为分叉。

### SSE parser

解析器必须处理：

- 网络 chunk 任意切分，包括一个 UTF-8 字符跨 chunk；
- LF 与 CRLF；
- 一个 chunk 中多个事件；
- 多行 `data:`；
- 尾部无额外空行的完整事件；
- `context/delta/done/error` 的 JSON data。

### 会话状态

- 状态是模块级单例，但按 `projectId:requirementId` key 隔离；切换 key 必须 reset。
- 只保存在页面内存，不写 localStorage/sessionStorage。
- 同时只允许一个请求；新 ask、stop、reset、切换 Requirement、组件卸载均 abort 旧请求。
- 每次请求分配单调 request generation。旧请求的 event/catch/finally 只有在 generation 仍匹配时才能改共享状态。
- history 从最近消息逆向取样，排除 pending/error/cancelled，限制条数、单条字符和总字符；服务端仍会二次校验。
- 流正常结束必须收到 `done`；EOF 无 done 视为失败，不能把半截回答标成功。

### 来源展示

仅使用 `context.sources` 作为可信来源集合。模型正文中的 `[KB:*]` 或 `[CODE:*]` 只是文本，不能据此生成“已验证”链接或徽章。回答以文本节点/`white-space: pre-wrap` 渲染，禁止 `v-html`。

## 4. Validation & Error Matrix

| 场景 | 前端行为 |
|---|---|
| config disabled | 隐藏/禁用入口，不发送请求 |
| 401 | 调全局登出处理，不额外重复 toast |
| 403/404/429/5xx | 生成失败消息，可 retry |
| AbortError | 标记 cancelled；空回答移除；不显示通用网络错误 |
| SSE `error` | 使用服务端安全 message，保留 retry |
| EOF 无 `done` | “流式响应意外结束” |
| 新请求覆盖旧请求 | 旧请求的 finally 不得清除新 controller/streaming |
| project/Requirement 切换 | abort + 清空 messages/sources/warnings |

## 5. Good / Base / Bad Cases

### Base

用户发送一条问题，收到 context、多个 delta 和 done；回答逐段追加，完成后 `streaming=false`。

### Good

用户快速停止后立即发第二问；第一问的旧 finally 被 generation guard 忽略，第二问继续正常流式显示。

### Bad

- 在 `finally` 中无条件 `controller=null; streaming=false`；
- 把 pending/failed/cancelled 消息发送回服务端；
- 只按 `\n\n` split，不处理 CRLF 或跨 chunk；
- 在 composable 内复制 Cookie/CSRF/401 fetch 逻辑；
- 从模型正文正则提取来源并标成可信；
- 把聊天记录写入 Web Storage。

## 6. Tests Required

Node tests至少覆盖：

- chunk/UTF-8/CRLF/多事件/尾包解析；
- credentials、CSRF Cookie 现读和 centralized 401；
- context/delta/done/error 路由；
- stop/reset/retry；
- 旧流 race：旧 finally 不影响新流；
- history 条数、单条和总字符预算；
- pending/error/cancelled 不进 history；
- EOF 无 done；
- source 只接受 context 白名单；
- 项目/Requirement 切换清理。

验证：

```powershell
cd frontend
npm test
npm run build
```

## 7. Wrong vs Correct

### Wrong

```js
let controller
async function ask() {
  controller = new AbortController()
  try { await consume(controller.signal) }
  finally {
    controller = null       // 可能清掉更新请求的 controller
    streaming.value = false // 可能终止更新请求的 UI
  }
}
```

### Correct

```js
const requestId = ++activeRequestId
const requestController = new AbortController()
controller = requestController
try {
  await consume(requestController.signal, event => {
    if (requestId !== activeRequestId) return
    apply(event)
  })
} finally {
  if (requestId === activeRequestId) {
    controller = null
    streaming.value = false
  }
}
```
