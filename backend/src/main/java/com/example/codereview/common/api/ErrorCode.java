package com.example.codereview.common.api;

/**
 * Stable, client-facing error vocabulary shared by both parallel workstreams.
 *
 * <p>Frozen in Phase 0 (see {@code docs/archive/并行实施拆分方案.md}). Both tracks draw from this single
 * enum so neither has to edit the other's files to introduce an error code. Entries are grouped by
 * owning track; adding a member is the one change that needs a quick cross-track heads-up, since
 * the file itself is shared.
 *
 * <p>Each entry carries three things:
 *
 * <ul>
 *   <li>{@link #httpStatus()} — the HTTP status the handler responds with;
 *   <li>{@link #legacyCode()} — the numeric {@code code} the existing API and frontend still read.
 *       Kept so this enum can be adopted incrementally without a breaking response change;
 *   <li>{@link #defaultMessage()} — a safe, user-facing message. Never put internal paths, SQL,
 *       URLs, tokens or raw exception text here.
 * </ul>
 *
 * <p>The enum constant name is the stable string identifier surfaced as {@code errorCode} in the
 * response body. Clients should branch on that, not on the numeric code.
 */
public enum ErrorCode {

    // ---------------------------------------------------------------- generic
    OK(200, 0, "success"),
    BAD_REQUEST(400, 400, "请求参数错误"),
    UNAUTHORIZED(401, 401, "未登录或登录已过期"),
    FORBIDDEN(403, 403, "无权访问该资源"),
    NOT_FOUND(404, 404, "资源不存在"),
    CONFLICT(409, 409, "资源状态冲突"),
    PAYLOAD_TOO_LARGE(413, 413, "请求内容超出大小限制"),
    RATE_LIMITED(429, 429, "请求过于频繁，请稍后重试"),
    INTERNAL_ERROR(500, 500, "系统内部错误"),
    SERVICE_UNAVAILABLE(503, 503, "服务暂时不可用，请稍后重试"),

    // ------------------------------------------------------------ Track A: 项目与业务资源
    PROJECT_NOT_FOUND(404, 404, "项目不存在"),
    PROJECT_FORBIDDEN(403, 403, "无权访问该项目"),
    REPOSITORY_NOT_FOUND(404, 404, "仓库不存在"),
    PULL_REQUEST_NOT_FOUND(404, 404, "Pull Request 不存在"),
    REVIEW_TASK_NOT_FOUND(404, 6002, "审查任务不存在"),
    REVIEW_REPORT_NOT_FOUND(404, 404, "审查报告不存在"),
    FINDING_NOT_FOUND(404, 404, "问题记录不存在"),
    FEEDBACK_NOT_FOUND(404, 404, "反馈记录不存在"),

    // ------------------------------------------------------------ Track A: Agent 执行闭环
    AGENT_RUN_NOT_FOUND(404, 404, "Agent 运行记录不存在"),
    AGENT_RUN_CONFLICT(409, 409, "Agent 运行状态冲突"),
    AGENT_STEP_NOT_FOUND(404, 404, "Agent 步骤不存在"),
    AGENT_STEP_LEASE_LOST(409, 409, "执行租约已失效，结果被丢弃"),
    AGENT_OUTBOX_PUBLISH_FAILED(503, 503, "任务暂时无法调度，请稍后重试"),
    AGENT_BUDGET_EXHAUSTED(409, 409, "本次运行已达资源预算上限"),
    AGENT_TRANSITION_ILLEGAL(409, 409, "当前状态不允许该操作"),

    // ------------------------------------------------------------ Track A: 知识库与补丁
    KNOWLEDGE_DOCUMENT_NOT_FOUND(404, 404, "知识文档不存在"),
    KNOWLEDGE_UPLOAD_REJECTED(400, 400, "文档不符合上传要求"),
    KNOWLEDGE_INDEX_FAILED(503, 503, "文档索引失败，请稍后重建"),
    PATCH_NOT_FOUND(404, 404, "补丁不存在"),
    PATCH_STALE(409, 409, "补丁已过期，请重新生成"),
    PATCH_APPROVAL_CONFLICT(409, 409, "补丁审批状态冲突"),

    // ------------------------------------------------------------ Track A: AI 调用
    AI_CALL_FAILED(503, 6004, "AI 服务调用失败"),
    AI_RESPONSE_INVALID(503, 6005, "AI 返回内容无法解析"),
    AI_EMBEDDING_FAILED(503, 6003, "向量化服务调用失败"),
    AI_CIRCUIT_OPEN(503, 6006, "AI 服务连续失败，熔断已开启，请稍后再试"),

    // ------------------------------------------------------------ Track B: 认证与会话
    AUTH_REQUIRED(401, 401, "未登录"),
    AUTH_INVALID_CREDENTIALS(401, 401, "用户名或密码错误"),
    AUTH_RATE_LIMITED(429, 429, "登录尝试过于频繁，请稍后重试"),
    CSRF_TOKEN_INVALID(403, 403, "请求校验失败，请刷新页面后重试"),

    // ------------------------------------------------------------ Track B: 出站与 Git 边界
    OUTBOUND_URL_REJECTED(400, 400, "目标地址不在允许范围内"),
    GIT_COMMAND_FAILED(503, 6001, "Git 操作失败"),
    GIT_REPOSITORY_TOO_LARGE(413, 413, "仓库超出允许的大小限制"),

    // ------------------------------------------------------------ Track B: Webhook 与 SCM
    WEBHOOK_SIGNATURE_INVALID(401, 401, "Webhook 签名校验失败"),
    WEBHOOK_INSTALLATION_UNKNOWN(404, 404, "未找到对应的接入配置"),
    WEBHOOK_EVENT_UNSUPPORTED(400, 400, "暂不支持该事件类型"),
    SCM_PUBLISH_FAILED(503, 503, "回写代码托管平台失败"),
    CREDENTIAL_DECRYPT_FAILED(500, 500, "凭据解密失败"),

    // ------------------------------------------------------------ Track B: Sandbox
    SANDBOX_SIGNATURE_INVALID(401, 401, "沙箱任务签名无效"),
    SANDBOX_REPLAY_REJECTED(409, 409, "沙箱任务重复提交已被拒绝");

    private final int httpStatus;
    private final int legacyCode;
    private final String defaultMessage;

    ErrorCode(int httpStatus, int legacyCode, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.legacyCode = legacyCode;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int legacyCode() {
        return legacyCode;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * Best-effort mapping for the legacy {@code new BusinessException(int, String)} call sites that
     * have not been migrated yet, so every response can carry a string {@code errorCode} from day
     * one. Unrecognised numbers fall back to a status-shaped code rather than inventing one.
     */
    public static ErrorCode fromLegacy(int legacyCode) {
        for (ErrorCode candidate : values()) {
            if (candidate.legacyCode == legacyCode && candidate.isGeneric()) {
                return candidate;
            }
        }
        return switch (legacyCode) {
            case 6001 -> GIT_COMMAND_FAILED;
            case 6002 -> REVIEW_TASK_NOT_FOUND;
            case 6003 -> AI_EMBEDDING_FAILED;
            case 6004 -> AI_CALL_FAILED;
            case 6005 -> AI_RESPONSE_INVALID;
            case 6006 -> AI_CIRCUIT_OPEN;
            // Mirrors BusinessException#resolveHttpStatus: only a real 5xx is a server error.
            // Codes outside the HTTP range fall back to 400 there, so they must fall back to
            // BAD_REQUEST here too, or an exception would advertise a 400 status alongside an
            // errorCode that claims 500.
            default -> legacyCode >= 500 && legacyCode <= 599 ? INTERNAL_ERROR : BAD_REQUEST;
        };
    }

    private boolean isGeneric() {
        return ordinal() <= SERVICE_UNAVAILABLE.ordinal();
    }
}
