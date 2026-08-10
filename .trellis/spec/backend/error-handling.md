# Error Handling

> How errors are handled in this project.

---

## Overview

对外(HTTP)错误走 `ErrorCode` 词汇表 + `BusinessException` + `GlobalExceptionHandler` + `ApiResponse` 信封;
Agent 执行链内部错误走 `AgentStepExecutionException` + `AgentFailureType`(RETRYABLE_* 与终态之分)。
两条通道共享同一条纪律:**摘要消息必须携带有界的底层原因,瞬态失败必须映射到可重试类型**(见下)。

---

## Error Types

- `common/api/ErrorCode.java` — 冻结的客户端错误词汇表(枚举名即响应体 `errorCode`),按 generic / Track A / Track B 分组;每个条目带 `httpStatus` / `legacyCode` / `defaultMessage`。`defaultMessage` 是安全的用户可见文案,**禁止**放内部路径、SQL、URL、token、原始异常文本(类头 Javadoc 明文规定)。新增条目允许,改名/删除/改 legacyCode 属于契约破坏,见 [frozen-contracts.md](./frozen-contracts.md)。
- `common/exception/BusinessException.java` — 业务失败的唯一抛出类型,新代码用 `new BusinessException(ErrorCode.X, "消息")`,不再用裸数字构造。
- `agent/error/AgentFailureType.java` — Agent 步骤失败分类;只有 `RETRYABLE_*` 类型会触发步骤重试机制(`AgentStepCompletionService.recordFailure` → `scheduleRetry`),其余是终态。
- `ai/AiCallTransientException.java` — AI 客户端对 429/5xx/超时的统一分类,必须被映射(见下方 Required 规则)。

---

## Error Handling Patterns

### Required: swallowed-cause messages must carry a bounded cause ("no blind errors")

Any `catch` that converts an exception into a summary message — step failures,
audit rows, `BusinessException` wrapping — MUST append a bounded slice of the
underlying cause. A bare summary makes production diagnosis impossible when the
payload itself is (by design) not retained.

**Contract**:

```java
// Bound the cause to 2_000 chars — matches the persistence caps of
// AgentStep.fail / AgentModelCall.fail. Prefer getMessage(); fall back to the
// exception's simple class name when the message is null/blank.
throw new AgentStepExecutionException(failureType,
        "finding model output is not schema-valid JSON: " + limit(ex.getOriginalMessage()));
call.fail("invalid finding model JSON: " + limit(detail));   // audit row too, not just the throw
```

**Incident record** (why this is a rule, not a preference): three blind errors in
one batch — `"finding model output is not schema-valid JSON"` with the parse
reason discarded left run15 undiagnosable from the DB (the audit table stores no
payload, so the discarded message was the only evidence); `"SCM publication failed"`
hid the null-apiBaseUrl cause (run17); `GitCliService.archiveLocked` dropped the
`IOException` behind a constant message (caught in review before it hurt).

**Test assertion point**: the thrown message AND the persisted audit/failure row
both contain a distinctive fragment of the injected cause
(`AgentFindingModelServiceTest`, `AgentPublicationServiceTest`).

### Required: transient provider failures map to the retryable failure type

`AiCallTransientException` (the AI clients' classification for 429/5xx/timeouts)
must reach `AgentFailureType.RETRYABLE_PROVIDER_ERROR`, never fall through a
generic `catch (RuntimeException)` into `INTERNAL_ERROR` (terminal). The step
retry machinery (`AgentStepCompletionService.recordFailure` → `scheduleRetry`)
only fires for `RETRYABLE_*` types — classification without mapping leaves the
whole retry stack dead code, and one provider hiccup kills the run (run14).
`AgentStepExecutionService.runClaimed` owns the catch; if a new dispatch path
around it appears, it needs the same catch and the same test pair
(retry scheduled / retries exhausted).

---

## API Error Responses

统一信封 `common/api/ApiResponse`:`{code, errorCode, message, traceId, data}`。

- `code` 是历史数字字段,前端仍按 `code !== 0` 分支,不许删;新代码同时给出字符串 `errorCode`(枚举名),客户端应逐步改读它。
- `traceId` 由 `ApiResponse.currentTraceId()` 自动从 MDC 取(见 [logging-guidelines.md](./logging-guidelines.md)),报障可直接引用。
- `GlobalExceptionHandler.handleBusiness` **原样使用异常上的 status 与数字 code,不从 ErrorCode 重推导**——部分 legacy code(6002 等)与自身 HTTP 状态不一致,重推导会静默改变存量响应(该方法注释即为此规则的事故说明)。
- 校验失败(`MethodArgumentNotValidException`/`ConstraintViolationException`)统一 400 `BAD_REQUEST`;AI 熔断开启映射 `AI_CIRCUIT_OPEN`(503)。

---

## Common Mistakes

- `catch (Exception e) { throw new BusinessException(X, "操作失败"); }` 丢弃原因 → 违反 no-blind-errors,生产不可诊断。
- 新增模型输出校验规则却不改提示词 → 链路必断,见 [agent-model-contracts.md](./agent-model-contracts.md)(七连断的同一缺陷类)。
- 在异常处理器里"顺手规整" legacy 数字码与 HTTP 状态的对应关系 → 存量客户端行为被静默改变。
- 把合法部署姿态(空知识库、无凭据安装)当错误抛 FAILED → 应显式降级,见 agent-model-contracts.md 的 posture 条目。
