package com.example.codereview.agent.tool.git;

import com.example.codereview.agent.tool.AgentTool;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.agent.tool.ToolRiskLevel;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class GitFileTool implements AgentTool<GitFileRequest, Map<String, Object>> {

    private final SandboxToolGateway gateway;

    public GitFileTool(SandboxToolGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "git.file";
    }

    @Override
    public Class<GitFileRequest> inputType() {
        return GitFileRequest.class;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.READ_ONLY;
    }

    @Override
    public ToolResult<Map<String, Object>> execute(ToolContext context, GitFileRequest input) {
        return gateway.execute(context, input);
    }
}
