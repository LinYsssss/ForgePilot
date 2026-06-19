package com.example.codereview.agent.tool;

public interface AgentTool<I, O> {
    String name();

    Class<I> inputType();

    ToolRiskLevel riskLevel();

    ToolResult<O> execute(ToolContext context, I input);
}
