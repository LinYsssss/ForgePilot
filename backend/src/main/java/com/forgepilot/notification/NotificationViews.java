package com.forgepilot.notification;

import java.time.Instant;

/**
 * 本模块 API 的响应体。
 *
 * <p>只有一个，而且它<strong>不含任何凭据</strong>：webhook URL 与加签密钥写进去之后就再也
 * 读不出来，页面能知道的只是「配过没有、开着没有、什么时候改的」。这与
 * {@code scm_repository} 的 token/secret 是同一套 write-only 语义。
 */
final class NotificationViews {

    private NotificationViews() {
    }

    /**
     * {@code updatedAt} 为 null 当且仅当 {@code configured} 为 false。
     *
     * <p>{@code signed} 说的是这个渠道有没有配加签密钥。它不是凭据，而是一个必须被看见的
     * 事实：不加签时，任何拿到那个 webhook URL 的人都能往群里发消息，而界面若不说，
     * 配置的人就无从知道自己停在了哪一档。
     */
    record ChannelView(boolean configured, boolean enabled, boolean signed, Instant updatedAt) {
    }
}
