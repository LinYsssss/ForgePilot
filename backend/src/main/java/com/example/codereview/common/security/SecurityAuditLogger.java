package com.example.codereview.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 统一的安全审计出口。
 *
 * <p>此前安全相关的痕迹散落在各处:登录失败只体现为一次 400、凭据变更完全无痕、越权拒绝只是
 * 一个 403 状态码。答辩要回答“谁在什么时候对什么做了什么、结果如何”,就需要一条固定格式、
 * 可 grep 可采集的事件流,而不是靠翻业务日志拼凑。
 *
 * <p>事件写到独立的 logger 名 {@code security.audit} 上,便于在部署侧单独路由/留存;格式是
 * 稳定的 {@code key=value} 序列,字段固定为:
 * {@code event / action / outcome / actor / actorId / resource / client / trace / reason}。
 *
 * <p><b>不记录的东西</b>:令牌、Cookie、口令、webhook secret、回写凭据、Patch 正文、
 * webhook payload。{@code reason} 只接受调用方给出的**稳定标识**(如 {@code BAD_CREDENTIALS}),
 * 不接受异常原文——异常消息可能带内部路径或地址。
 *
 * <p>来源 IP 以 HMAC 摘要形式落盘(取前 12 位十六进制):既能把同一来源的多条事件关联起来,
 * 又不把原始 IP 留在日志里。摘要用应用令牌密钥加盐,因此无法被离线穷举反推;密钥轮换后
 * 同一 IP 的摘要会变化,这是可接受的代价。
 */
@Component
public class SecurityAuditLogger {

    /** 独立 logger 名:部署侧可单独把这条流路由到审计文件或采集器。 */
    private static final Logger audit = LoggerFactory.getLogger("security.audit");

    /** 登录成功后由认证过滤器写入的请求属性,供请求结束时的审计过滤器读取 actor。 */
    public static final String ACTOR_ATTRIBUTE = "reposage.audit.actor";
    /** 客户端 IP 摘要放在 MDC 里,这样任意深度的调用点都能拿到,无需把 request 传下去。 */
    public static final String CLIENT_MDC_KEY = "auditClient";

    private static final int MAX_VALUE_LENGTH = 120;

    public enum Outcome {
        /** 操作被允许且完成。 */
        SUCCESS,
        /** 操作被允许但失败(口令错误、上游拒绝等)。 */
        FAILURE,
        /** 请求被安全边界拒绝(未认证、越权、验签失败、限流)。 */
        DENIED
    }

    private final byte[] digestKey;

    public SecurityAuditLogger(@Value("${app.security.token-secret}") String tokenSecret) {
        this.digestKey = (tokenSecret == null ? "" : tokenSecret).getBytes(StandardCharsets.UTF_8);
    }

    /** actor 取自当前 {@link SecurityContextHolder};匿名请求记为 {@code -}。 */
    public void record(String action, Outcome outcome, String resourceType, String resourceId, String reason) {
        CurrentUser actor = currentUser();
        recordFor(actor == null ? null : actor.userId(), actor == null ? null : actor.username(),
                action, outcome, resourceType, resourceId, reason);
    }

    public void success(String action, String resourceType, String resourceId) {
        record(action, Outcome.SUCCESS, resourceType, resourceId, null);
    }

    public void failure(String action, String resourceType, String resourceId, String reason) {
        record(action, Outcome.FAILURE, resourceType, resourceId, reason);
    }

    public void denied(String action, String resourceType, String resourceId, String reason) {
        record(action, Outcome.DENIED, resourceType, resourceId, reason);
    }

    /**
     * 显式给出 actor 的版本。登录、以及请求结束后的过滤器都需要它:前者此时还没有
     * SecurityContext,后者运行在安全过滤链之外——上下文已被清理。
     */
    public void recordFor(Long actorId, String actorName, String action, Outcome outcome,
                          String resourceType, String resourceId, String reason) {
        String line = "event=security_audit"
                + " action=" + sanitize(action)
                + " outcome=" + (outcome == null ? "-" : outcome.name())
                + " actor=" + sanitize(actorName)
                + " actorId=" + (actorId == null ? "-" : actorId)
                + " resource=" + sanitize(resourceType) + ":" + sanitize(resourceId)
                + " client=" + sanitize(MDC.get(CLIENT_MDC_KEY))
                + " trace=" + sanitize(MDC.get("traceId"))
                + " reason=" + sanitize(reason);
        if (outcome == Outcome.SUCCESS) {
            audit.info(line);
        } else {
            // 失败与拒绝是排查入侵与配置错误的主线索,抬到 WARN 让默认日志级别也留得下。
            audit.warn(line);
        }
    }

    /** 供过滤器把解析出来的客户端 IP 转成可落盘的摘要。 */
    public String digestClientIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "-";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(digestKey.length == 0 ? new byte[]{0} : digestKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(clientIp.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            // 摘要算不出来不该让业务失败,退化为不可用标记。
            return "-";
        }
    }

    private static CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        return null;
    }

    /**
     * 审计行本身也是日志:换行或制表符会让攻击者用一个用户名伪造出第二条审计记录,
     * 空格会把 key=value 解析切错。因此只保留可打印字符,并把分隔符换成下划线。
     */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String trimmed = value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) : value;
        StringBuilder out = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            if (c < 0x20 || c == 0x7F || c == ' ' || c == '=' || c == '"') {
                out.append('_');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
