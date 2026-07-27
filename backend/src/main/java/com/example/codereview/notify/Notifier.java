package com.example.codereview.notify;

/**
 * 审查结论的外部通知出口。实现按配置装配:未开启时装配 {@link NoopNotifier},
 * 因此调用方无需判断开关。实现必须自行吞掉异常——通知失败不能影响审查主流程。
 */
public interface Notifier {

    void reviewCompleted(ReviewNotification notification);
}
