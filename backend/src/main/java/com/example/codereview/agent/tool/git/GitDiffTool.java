package com.example.codereview.agent.tool.git;

import com.example.codereview.agent.tool.AgentTool;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.agent.tool.ToolRiskLevel;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class GitDiffTool implements AgentTool<GitDiffRequest, Map<String, Object>> {

    private final SandboxToolGateway gateway;

    public GitDiffTool(SandboxToolGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "git.diff";
    }

    @Override
    public Class<GitDiffRequest> inputType() {
        return GitDiffRequest.class;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.READ_ONLY;
    }

    @Override
    public ToolResult<Map<String, Object>> execute(ToolContext context, GitDiffRequest input) {
        return gateway.execute(context, input);
    }
}
