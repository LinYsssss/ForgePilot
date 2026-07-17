package com.example.codereview.agent.tool;

public interface AgentTool<I, O> {
    String name();

    default String description() {
        return "Execute the registered " + name() + " Agent tool";
    }

    Class<I> inputType();

    ToolRiskLevel riskLevel();

    ToolResult<O> execute(ToolContext context, I input);
}
