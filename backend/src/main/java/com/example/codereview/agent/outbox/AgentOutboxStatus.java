package com.example.codereview.agent.outbox;

public enum AgentOutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    /** Terminal: retries were exhausted. Requires operator attention; never republished. */
    FAILED
}
