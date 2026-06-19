package com.example.codereview.agent.error;

public enum AgentFailureType {
    RETRYABLE_INFRASTRUCTURE,
    BUDGET_EXCEEDED,
    SECURITY_VIOLATION,
    INVALID_MODEL_OUTPUT,
    INVALID_PLAN,
    TOOL_FAILURE,
    CANCELED,
    INTERNAL_ERROR
}
