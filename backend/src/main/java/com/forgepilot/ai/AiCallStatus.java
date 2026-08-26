package com.forgepilot.ai;

/**
 * 一次尝试的结束方式，与 {@code ai_call_log.status} 的 CHECK 一一对应。
 *
 * <p>{@link #TIMEOUT} 有意与 {@link #FAILED} 分开：按 ARCHITECTURE.md 7.2
 * 两者都属瞬时失败、都会重试一次，但只有单独保留这个取值，运维事后才分得清
 * provider 是**答得不对**还是**根本没答**。
 */
public enum AiCallStatus {

    SUCCESS,
    FAILED,
    TIMEOUT
}
