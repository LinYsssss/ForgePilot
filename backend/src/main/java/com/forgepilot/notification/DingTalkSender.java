package com.forgepilot.notification;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * 往钉钉自定义机器人发一条 Markdown 消息。
 *
 * <p>加签按钉钉的规定：以 {@code timestamp + "\n" + secret} 为待签串、密钥同样是
 * {@code secret}，HmacSHA256 之后 Base64，再做一次 URL 编码，最后与 {@code timestamp}
 * 一并作为查询参数附在 webhook URL 后面。
 *
 * <p><strong>本类不抛异常。</strong>推送失败返回 {@code false} 并记一行日志，
 * 不重试、不上报。理由写在 {@code package-info}：通知是旁路，让它的失败影响到审查，
 * 就是把一个聊天机器人的可用性变成代码审查的前置条件。
 */
@Component
class DingTalkSender {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final Clock clock;

    DingTalkSender() {
        this(Clock.systemUTC());
    }

    /** 测试用：加签的输入之一是时间戳，因此它必须能被固定住。 */
    DingTalkSender(Clock clock) {
        this(clock, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    /**
     * 测试用：让「送不出去」这条分支可达，而<strong>不必</strong>放宽地址检查。
     *
     * <p>这是本类唯一的测试接缝，而且它有意开在客户端而不是 URL 上：把 origin 检查做成可配置，
     * 等于给这道防线留了一个开关，而一个能被测试重定向的防线也能被别的东西重定向。
     */
    DingTalkSender(Clock clock, HttpClient http) {
        this.clock = clock;
        this.http = http;
    }

    /**
     * @return 钉钉是否以 2xx 收下了这条消息。调用方除了记账之外不该据此做任何事——
     *         尤其不该重试：重复投递对一个群聊比丢一条更烦人。
     */
    boolean send(NotificationChannelService.Credentials credentials, String markdownTitle,
            String markdownText) {
        // 即便 URL 来自本库、写入时已经校验过，这里仍然再验一次：那一列可由 LEADER
        // 更新，而“只在写入时校验”的东西迟早会被某条绕过写入路径的改动放进来。
        // 与 GitHubClient 每次调用都重跑 OutboundUrlPolicy 是同一个理由。
        NotificationChannelService.requireDingTalkUrl(credentials.webhookUrl());

        long timestamp = clock.millis();
        URI target = URI.create(credentials.webhookUrl()
                + (credentials.webhookUrl().contains("?") ? "&" : "?")
                + "timestamp=" + timestamp
                + "&sign=" + sign(timestamp, credentials.secret()));

        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        body(markdownTitle, markdownText), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2;
        } catch (IOException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 钉钉规定的待签串与编码顺序：HmacSHA256 -> Base64 -> URL 编码。 */
    static String sign(long timestamp, String secret) {
        String payload = timestamp + "\n" + secret;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return URLEncoder.encode(Base64.getEncoder().encodeToString(digest),
                    StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // HmacSHA256 是每个 JDK 都必须提供的算法，密钥也来自本库的一个非空列。
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    /**
     * 手工拼 JSON 而不是用 ObjectMapper：这个载荷只有两个字符串槽位，一次序列化只会让
     * 「这里到底会发出去什么」更难一眼看清。
     *
     * <p>但 {@link #quote} 是<strong>承重的</strong>，不是保险：{@code text} 里带着项目名与
     * PR 标题，后者由 provider 提供、内容由提 PR 的人决定。一个带引号或换行的标题，
     * 若不转义就会把这份 JSON 撕开——最好的结果是钉钉拒收，最坏的结果是消息结构被改写。
     */
    static String body(String title, String text) {
        return "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":" + quote(title)
                + ",\"text\":" + quote(text) + "}}";
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
