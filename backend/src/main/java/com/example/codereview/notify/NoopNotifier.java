package com.example.codereview.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 未配置通知渠道时的空实现(默认)。 */
@Service
@ConditionalOnProperty(prefix = "app.notify.dingtalk", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopNotifier implements Notifier {

    @Override
    public void reviewCompleted(ReviewNotification notification) {
        // 通知未启用,什么也不做。
    }
}
