# Logging Guidelines

> SLF4J(`LoggerFactory.getLogger`),Logback 默认输出;级别模式统一打印 traceId:
> `application.yml` → `logging.pattern.level: "%5p [%X{traceId:-}]"`。

---

## traceId 全链路纪律

一条请求的所有日志(含异步续段)必须共享同一个 traceId。链路各环节都已有实现,新代码接入而不是另起炉灶:

1. **HTTP 入口**:`common/web/TraceIdFilter`(`@Order(HIGHEST_PRECEDENCE)`,先于 Spring Security,认证失败也可追踪)。接受入站 `X-Trace-Id`(≤64 字符、`[A-Za-z0-9._-]+` 白名单——这同时是"入站头写入日志前先过白名单"的防注入范式),否则生成 16 位随机 id;写入 MDC key `traceId` 并回写响应头,`finally` 里 `MDC.remove`。
2. **响应体**:`ApiResponse.currentTraceId()` 从 MDC 读出放进信封,前端 `ApiError.traceId` 透传,报障可直接对日志。
3. **跨 MQ**:载荷显式携带——`agent/queue/AgentStepMessage` 的 `traceId` 字段;消费端 `AgentStepConsumer.consume` 用"保存旧值 → `MDC.put` → finally 恢复/移除"的样式还原上下文。outbox 事件持久化 `traceId` 列(`agent/outbox/AgentOutboxEvent`),投递侧同样回填。
4. **新增异步边界(新队列、新调度器)时**:载荷带 traceId 字段 + 消费侧照 `AgentStepConsumer` 的 save/restore 写法。MDC 是 ThreadLocal,漏恢复会把 A 请求的 id 染给 B。

---

## 级别约定(取自真实调用点)

- **WARN = 降级但继续**:超预算计划项被裁剪并留痕(`ReviewPlanValidator` 的 clamp,WARN 带 index/tool/reason)、无凭据安装跳过 SCM 投递、AI 熔断快速失败(`GlobalExceptionHandler.handleCircuitOpen`)、拒收的沙箱作业(`SandboxJobConsumer` WARN 带 jobId 与拒因)。WARN 必须带上下文标识(runId/jobId/index),使审计可回放。
- **ERROR = 需要运维介入的终局**:未捕获异常(`GlobalExceptionHandler` 的兜底 `log.error("未捕获的异常", ex)`,唯一允许带全栈)、outbox 投递超过最大尝试次数放弃(`AgentOutboxMaintenanceService`,消息明示 "operator action needed")。可重试/可降级的情况不要用 ERROR,会稀释告警。
- 摘要日志与异常消息一样受 no-blind-errors 约束:带有界原因,见 [error-handling.md](./error-handling.md)。

---

## 禁止入日志的内容

- **凭据与令牌**:出程序边界的提示词已由 `agent/prompt/AgentPromptAssembler.redact` 统一脱敏(私钥块、`Authorization: Bearer/Basic`、`*_TOKEN/PASSWORD/SECRET/API_KEY` 赋值、GitHub token 形态)。日志同理——不打印 `encryptedCredential`、签名 secret、cookie 值。
- **未过滤的入站头/用户输入**:先按 `TraceIdFilter.sanitize` 的白名单思路清洗再落日志,防日志注入。
- **模型原始 payload 全文**:审计表按设计不留 payload(这正是消息必须带有界原因的理由);日志同样只落有界片段(2000 字符上限,对齐 `AgentStep.fail` / `AgentModelCall.fail` 的持久化截断)。
