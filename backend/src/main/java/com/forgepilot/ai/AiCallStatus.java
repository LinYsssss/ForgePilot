package com.forgepilot.ai;

/**
 * How one attempt ended, mirroring {@code ai_call_log.status}'s CHECK.
 *
 * <p>{@link #TIMEOUT} is kept apart from {@link #FAILED} on purpose: both are
 * transient by ARCHITECTURE.md 7.2 and both are retried once, but only the
 * separate value tells an operator afterwards whether the provider answered
 * badly or never answered at all (design.md 2.1).
 */
public enum AiCallStatus {

    SUCCESS,
    FAILED,
    TIMEOUT
}
