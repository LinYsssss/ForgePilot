package com.forgepilot.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.forgepilot.common.ApiException;
import com.forgepilot.notification.NotificationChannelService.Credentials;
import org.junit.jupiter.api.Test;

/**
 * 钉钉发送器的四条承重性质：地址必须真的是钉钉、加签算法固定、载荷里的用户输入被转义、
 * 以及送不出去时<strong>不抛异常</strong>。
 *
 * <p>没有端到端的出站测试，这是钉死 origin 换来的代价：地址被限定在
 * {@code oapi.dingtalk.com}，就无法把它指向一个回环上的桩。这个取舍是明知的——
 * 一个能被测试重定向的 SSRF 防线，也能被别的东西重定向。
 */
class DingTalkSenderTest {

    private static final Clock FIXED = Clock.fixed(Instant.ofEpochMilli(1_600_000_000_000L),
            ZoneOffset.UTC);

    // ------------------------------------------------------------------- 地址

    /**
     * <strong>前缀陷阱。</strong>{@code oapi.dingtalk.com.evil.com} 以
     * {@code https://oapi.dingtalk.com} 开头，因此一个不带结尾斜杠的前缀比较会放它过去
     * ——而这是本模块<em>唯一</em>的 SSRF 防线，放过就等于没有。
     */
    @Test
    void aLookalikeHostIsRefused() {
        assertThatThrownBy(() -> NotificationChannelService
                .requireDingTalkUrl("https://oapi.dingtalk.com.evil.com/robot/send?access_token=x"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void onlyTheDingTalkOriginOverHttpsIsAccepted() {
        assertThatThrownBy(() -> NotificationChannelService.requireDingTalkUrl(null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NotificationChannelService
                .requireDingTalkUrl("http://oapi.dingtalk.com/robot/send"))
                .as("明文 http 不接受：凭据就写在这个 URL 里")
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NotificationChannelService
                .requireDingTalkUrl("https://169.254.169.254/robot/send"))
                .isInstanceOf(ApiException.class);

        NotificationChannelService.requireDingTalkUrl(
                "https://oapi.dingtalk.com/robot/send?access_token=abc");
    }

    // ------------------------------------------------------------------- 加签

    /**
     * 加签值对着钉钉文档的算法固定断言：待签串 {@code timestamp + "\n" + secret}，
     * 密钥也是 {@code secret}，HmacSHA256 之后 Base64，再 URL 编码。
     *
     * <p>任何一步换了顺序，钉钉都会以 sign 不匹配拒收——而那个失败只在真实调用时才看得见，
     * 本地没有任何东西会变红。
     */
    @Test
    void theSignatureFollowsTheDocumentedAlgorithm() {
        String signed = DingTalkSender.sign(1_600_000_000_000L, "SECabcdef");

        assertThat(signed).isEqualTo(independentlySigned(1_600_000_000_000L, "SECabcdef"));
        // Base64 会产出 '+' 与 '='，拼进查询串前必须编码，否则到服务端就是另一个值。
        assertThat(signed).doesNotContain("+").doesNotContain("=");
    }

    /** 与被测实现同一个公式，但不共享它的任何代码路径。 */
    private static String independentlySigned(long timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    (timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
            return URLEncoder.encode(Base64.getEncoder().encodeToString(digest),
                    StandardCharsets.UTF_8);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ------------------------------------------------------------------- 载荷

    /**
     * 载荷里带着<strong>用户输入</strong>：PR 标题由提 PR 的人决定，项目名由 LEADER 决定。
     * 一个带引号或换行的标题若不转义，就会把这份手写的 JSON 撕开。
     */
    @Test
    void userSuppliedTextCannotBreakOutOfThePayload() {
        // 显式构造那个控制字符，而不是把一个不可见字节留在源码里。
        String bell = String.valueOf((char) 0x07);
        String body = DingTalkSender.body("标题", "PR: \"fix\" \\ 第一行\n第二行" + bell);

        assertThat(body).startsWith("{\"msgtype\":\"markdown\"");
        // 结构没有被撕开：正文里的引号、反斜杠、换行与控制字符全部转义。
        assertThat(body).contains("\\\"fix\\\"").contains("\\\\").contains("\\n")
                .contains("\\u0007");
        assertThat(body).doesNotContain("\n");
        // 花括号只剩结构本身的那几个，正文没有引入新的。
        assertThat(body).endsWith("}}");
    }

    // ------------------------------------------------------------------- 出站

    /**
     * 送不出去时返回 false，<strong>不抛异常</strong>。
     *
     * <p>这条是「通知是旁路」在代码里的落点。一旦这里抛出去，一次成功的审查就会因为
     * 一个聊天机器人不可达而在日志里变成失败——而它什么也补救不了。
     */
    @Test
    void anUnreachableRobotIsReportedAsFalseAndNotThrown() {
        // 一个指向 127.0.0.1:1 的代理，那里什么也没有监听。地址仍是合法的钉钉地址，
        // 因此走到的是出站失败这条分支而不是地址校验那条；同时没有一个包会真的发往钉钉
        // ——测试不联网是 quality-guidelines.md 的硬要求。
        HttpClient viaDeadProxy = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", 1)))
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        DingTalkSender sender = new DingTalkSender(FIXED, viaDeadProxy);
        Credentials credentials = new Credentials(
                "https://oapi.dingtalk.com/robot/send?access_token=x", "SECx");

        assertThat(sender.send(credentials, "标题", "正文")).isFalse();
    }
}
