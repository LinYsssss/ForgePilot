package com.example.codereview.agent.outbox;

public enum AgentOutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    /** 终态:重试已耗尽。需要人工介入,永不再重发。 */
    FAILED
}
