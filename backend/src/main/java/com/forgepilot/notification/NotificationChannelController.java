package com.forgepilot.notification;

import java.security.Principal;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 项目的钉钉通知配置。三个动作都要求 LEADER，由服务层判定。 */
@RestController
@RequestMapping("/api/projects/{projectId}/notifications/dingtalk")
class NotificationChannelController {

    private final NotificationChannelService channels;
    private final UserDirectory users;

    NotificationChannelController(NotificationChannelService channels, UserDirectory users) {
        this.channels = channels;
        this.users = users;
    }

    @GetMapping
    NotificationViews.ChannelView get(@PathVariable long projectId, Principal principal) {
        return channels.view(projectId, userIdOf(principal));
    }

    /**
     * PUT 而非 PATCH：凭据读不回来，因此「部分更新」没有意义——调用方无从知道自己
     * 没填的那一半现在是什么。整组重填是唯一诚实的写法。
     */
    @PutMapping
    NotificationViews.ChannelView configure(@PathVariable long projectId,
            @Valid @RequestBody ConfigureRequest request, Principal principal) {
        return channels.configure(projectId, userIdOf(principal), request.webhookUrl(),
                request.secret(), request.keyword(), request.enabled());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable long projectId, Principal principal) {
        channels.remove(projectId, userIdOf(principal));
    }

    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    /**
     * {@code enabled} 是基本类型 boolean，因此缺失即为 false——这是安全的方向：
     * 一个没说清楚要不要开的配置，默认不往外发消息。
     *
     * <p>{@code secret} 可以缺失或为空，表示这个渠道不加签。钉钉的三种安全设置只在
     * <em>创建</em>机器人时可选，已存在的机器人在很多客户端里改不了，因此强制要求加签
     * 换不来更安全的部署，只会换来无法部署。代价记在 SECURITY.md 的残余风险里，
     * 配置表单也在做选择的那个位置直说。
     */
    record ConfigureRequest(
            @NotBlank @Size(max = 512) String webhookUrl,
            @Size(max = 256) String secret,
            @Size(max = 64) String keyword,
            boolean enabled) {
    }
}
