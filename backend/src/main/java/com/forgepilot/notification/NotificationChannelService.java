package com.forgepilot.notification;

import java.util.Optional;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.scm.ScmSecretCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目通知渠道的配置。只有 LEADER 能读写——与配置仓库凭据是同一件事、同一个门槛。
 *
 * <p>凭据<strong>只进不出</strong>：{@link #view} 返回的是「配没配、开没开、什么时候改的」，
 * 从不返回 URL 或密钥本身，与 {@code scm_repository} 的 token/secret 处理一致。
 * 想换就整组重填，没有「看一眼原值再改」这条路。
 */
@Service
@Transactional
public class NotificationChannelService {

    /**
     * 钉钉自定义机器人的地址前缀，写死。
     *
     * <p>这不是便利，是本模块唯一的 SSRF 防线：webhook URL 由 LEADER 提供、随后由服务端
     * 解引用，若不约束就是一个把本系统变成跳板机的入口。写死一个已知的公网 origin
     * 比通用的「拒内网」策略<strong>更紧</strong>，代价是不支持自建的钉钉兼容端点——
     * 这个代价是明知且接受的。
     */
    static final String DINGTALK_ORIGIN = "https://oapi.dingtalk.com/";

    private final NotificationChannelRepository channels;
    private final ProjectAccessService access;
    private final ScmSecretCipher cipher;

    NotificationChannelService(NotificationChannelRepository channels, ProjectAccessService access,
            ScmSecretCipher cipher) {
        this.channels = channels;
        this.access = access;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public NotificationViews.ChannelView view(long projectId, long actorId) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        return channels.findByProjectIdAndChannel(projectId, NotificationChannelType.DINGTALK)
                .map(channel -> new NotificationViews.ChannelView(true, channel.isEnabled(),
                        channel.getEncryptedSecret() != null, channel.getUpdatedAt()))
                .orElseGet(() -> new NotificationViews.ChannelView(false, false, false, null));
    }

    /**
     * 整组写入：没有就建，有就换掉两个凭据。
     *
     * <p>用 PUT 的语义而不是 PATCH，正因为凭据不可读——一个读不回来的字段没有「部分更新」
     * 可言，调用方无从知道自己没填的那一半现在是什么。
     */
    public NotificationViews.ChannelView configure(long projectId, long actorId, String webhookUrl,
            String secret, boolean enabled) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        requireDingTalkUrl(webhookUrl);

        String encryptedUrl = cipher.encrypt(webhookUrl);
        // 空白与缺失都当作「不加签」。用一个空串去加签会算出一个看着像样、
        // 却必然被钉钉拒收的签名——那种失败只在真实推送时才暴露。
        String encryptedSecret = secret == null || secret.isBlank() ? null : cipher.encrypt(secret);
        NotificationChannel channel = channels
                .findByProjectIdAndChannel(projectId, NotificationChannelType.DINGTALK)
                .orElseGet(() -> new NotificationChannel(projectId, NotificationChannelType.DINGTALK,
                        encryptedUrl, encryptedSecret));
        channel.replaceCredentials(encryptedUrl, encryptedSecret);
        channel.enable(enabled);
        channels.save(channel);

        return new NotificationViews.ChannelView(true, channel.isEnabled(),
                channel.getEncryptedSecret() != null, channel.getUpdatedAt());
    }

    public void remove(long projectId, long actorId) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        channels.deleteByProjectIdAndChannel(projectId, NotificationChannelType.DINGTALK);
    }

    /** 推送路径要用的明文凭据。仅供本包内部使用，绝不经由任何控制器出去。 */
    @Transactional(readOnly = true)
    Optional<Credentials> credentialsOf(long projectId) {
        return channels.findByProjectIdAndChannel(projectId, NotificationChannelType.DINGTALK)
                .filter(NotificationChannel::isEnabled)
                .map(channel -> new Credentials(cipher.decrypt(channel.getEncryptedWebhookUrl()),
                        channel.getEncryptedSecret() == null
                                ? null
                                : cipher.decrypt(channel.getEncryptedSecret())));
    }

    /**
     * 前缀比对必须带上那个斜杠。少了它，{@code https://oapi.dingtalk.com.evil.com/...}
     * 会通过检查——这是主机前缀匹配的经典漏法，而它恰好把本模块唯一的防线打穿。
     */
    static void requireDingTalkUrl(String url) {
        if (url == null || !url.startsWith(DINGTALK_ORIGIN)) {
            throw ApiException.unprocessable(
                    "The webhook URL must be a DingTalk robot address under " + DINGTALK_ORIGIN);
        }
    }

    /**
     * 明文凭据，只在进程内从服务传到发送器。
     *
     * <p>{@code secret} 为 {@code null} 表示这个渠道不加签——此时防护只剩 URL 本身的保密性。
     */
    record Credentials(String webhookUrl, String secret) {

        boolean signed() {
            return secret != null && !secret.isBlank();
        }
    }
}
