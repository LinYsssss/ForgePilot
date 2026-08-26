package com.forgepilot.ai;

/**
 * 本项目允许的唯一一次重试，以及判定它何时适用的规则表。
 *
 * <p>ARCHITECTURE.md 7.2 把两半都定死了：“LLM 重试次数 | 1 | 仅瞬时错误
 * (429/5xx/网络)”。本代码库其他任何地方都没有重试，也必须保持没有：这条例外
 * 只授权给网关（ARCHITECTURE.md 4.1 与 7.1）。
 * 知识入库、数据库周边以及 4xx 一律不得复用它。
 *
 * <p>这个区分不是偏好问题。429 或 5xx 说明 provider 这一次没能作答；400 说明
 * 请求本身就是错的，于是一模一样的第二次请求只能得到一模一样的回答，
 * 却把账单翻了一倍。
 *
 * <p>传输层失败——连接被拒、被重置以及超时——属于 7.2 的“网络”类，按定义即为
 * 瞬时失败，因此直接在 {@link AiGateway} 的 catch 分支处判定，而不是交给一个
 * 永远只会返回 {@code true} 的方法。
 */
public final class AiFailurePolicy {

    /**
     * 一次调用加上唯一那次重试，绝无第三次。这里没有退避等待：
     * 7.2 只定义了次数、没有定义延迟，凭空发明一个就等于多了一条
     * 没有出处的运行时规则。
     */
    public static final int MAX_ATTEMPTS = 2;

    private AiFailurePolicy() {
    }

    public static boolean isTransient(int httpStatus) {
        return httpStatus == 429 || httpStatus >= 500;
    }
}
