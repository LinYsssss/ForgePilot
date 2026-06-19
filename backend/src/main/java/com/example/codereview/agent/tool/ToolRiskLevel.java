package com.example.codereview.agent.tool;

public enum ToolRiskLevel {
    READ_ONLY(false),
    WRITE_REQUIRES_APPROVAL(true),
    DESTRUCTIVE_REQUIRES_APPROVAL(true);

    private final boolean approvalRequired;

    ToolRiskLevel(boolean approvalRequired) {
        this.approvalRequired = approvalRequired;
    }

    public boolean isApprovalRequired() {
        return approvalRequired;
    }
}
