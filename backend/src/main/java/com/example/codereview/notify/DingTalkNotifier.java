package com.example.codereview.notify;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 钉钉自定义机器人通知。支持「加签」安全设置(HMAC-SHA256 + base64,附在 URL 上);
 * 若机器人用的是「自定义关键词」,把关键词包含在 {@code app.notify.dingtalk.keyword} 里即可,
 * 它会被拼进标题。
 *
 * <p>只推结论摘要,不推 diff 或证据原文。任何发送失败只记录日志,绝不影响审查流程。
 */
@Service
@ConditionalOnProperty(prefix = "app.notify.dingtalk", name = "enabled", havingValue = "true")
public class DingTalkNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(DingTalkNotifier.class);
    private static final List<String> RISK_ORDER = List.of("NONE", "LOW", "MEDIUM", "HIGH");

    private final RestClient restClient;
    private final String webhookUrl;
    private final String secret;
    private final String keyword;
    private final String minRisk;
    private final String baseUrl;

    public DingTalkNotifier(RestClient.Builder restClientBuilder,
                            @Value("${app.notify.dingtalk.webhook-url:}") String webhookUrl,
                            @Value("${app.notify.dingtalk.secret:}") String secret,
                            @Value("${app.notify.dingtalk.keyword:}") String keyword,
                            @Value("${app.notify.dingtalk.min-risk:LOW}") String minRisk,
                            @Value("${app.notify.base-url:}") String baseUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException("DINGTALK_ENABLED=true 需要同时配置 DINGTALK_WEBHOOK_URL");
        }
        this.restClient = restClientBuilder.build();
        this.webhookUrl = webhookUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.keyword = keyword == null ? "" : keyword.trim();
        this.minRisk = minRisk == null || minRisk.isBlank() ? "LOW" : minRisk.trim().toUpperCase();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    @Override
    public void reviewCompleted(ReviewNotification notification) {
        try {
            if (!meetsThreshold(notification.overallRisk())) {
                return;
            }
            Map<String, Object> payload = Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of(
                            "title", title(notification),
                            "text", markdown(notification)));
            restClient.post()
                    .uri(signedUrl())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            // 通知是旁路能力:失败只记日志,不让审查结果写入回滚。
            log.warn("钉钉通知发送失败 reportId={}: {}", notification.reportId(), ex.getMessage());
        }
    }

    /** 低于阈值的风险不打扰(例如只关心 HIGH 时设 min-risk=HIGH)。 */
    private boolean meetsThreshold(String risk) {
        int current = RISK_ORDER.indexOf(risk == null ? "NONE" : risk.toUpperCase());
        int floor = RISK_ORDER.indexOf(minRisk);
        return current >= (floor < 0 ? 0 : floor);
    }

    private String title(ReviewNotification n) {
        String base = "RepoSage 审查完成 · " + safe(n.overallRisk());
        return keyword.isEmpty() ? base : keyword + " " + base;
    }

    private String markdown(ReviewNotification n) {
        StringBuilder text = new StringBuilder();
        text.append("### ").append(title(n)).append('\n');
        text.append("- 项目:").append(safe(n.projectName())).append('\n');
        text.append("- 总体风险:**").append(safe(n.overallRisk())).append("**\n");
        text.append("- 问题数:").append(n.issueCount());
        if (n.highCount() > 0) {
            text.append("(高危 ").append(n.highCount()).append(')');
        }
        text.append('\n');
        if (n.commitId() != null && !n.commitId().isBlank()) {
            text.append("- Commit:`").append(n.commitId().substring(0, Math.min(8, n.commitId().length()))).append("`\n");
        }
        if (n.summary() != null && !n.summary().isBlank()) {
            text.append("\n> ").append(truncate(n.summary(), 300)).append('\n');
        }
        if (!baseUrl.isEmpty()) {
            text.append("\n[查看完整报告](").append(baseUrl).append(")\n");
        }
        return text.toString();
    }

    /**
     * 钉钉加签:sign = urlEncode(base64(HmacSHA256(timestamp + "\n" + secret, secret)))。
     * 未配置 secret 时按「关键词」或「IP 白名单」模式直接发送。
     */
    private String signedUrl() {
        if (secret.isEmpty()) {
            return webhookUrl;
        }
        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8))),
                    StandardCharsets.UTF_8);
            return webhookUrl + (webhookUrl.contains("?") ? "&" : "?") + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception ex) {
            throw new IllegalStateException("钉钉签名计算失败", ex);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
