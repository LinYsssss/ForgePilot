package com.forgepilot.notification;

/**
 * 通知渠道的封闭词表。
 *
 * <p>它与 {@code ck_notification_channel_type} 是同一份词表的两处表达。词表外的值到达
 * 那条 CHECK 会让整条插入失败，因此应用侧先映射到本枚举；
 * {@code NotificationChannelTest} 走遍本枚举去比对那条 CHECK，
 * 与 {@code FindingCategory} 和 {@code DeletedResourceType} 是同一套纪律。
 */
public enum NotificationChannelType {

    /** 钉钉自定义机器人。 */
    DINGTALK
}
