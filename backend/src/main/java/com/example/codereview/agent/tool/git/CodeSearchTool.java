package com.example.codereview.agent.tool.git;

import com.example.codereview.agent.tool.AgentTool;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.agent.tool.ToolRiskLevel;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class CodeSearchTool implements AgentTool<CodeSearchRequest, Map<String, Object>> {

    private final SandboxToolGateway gateway;

    public CodeSearchTool(SandboxToolGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "code.search";
    }

    @Override
    public Class<CodeSearchRequest> inputType() {
        return CodeSearchRequest.class;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.READ_ONLY;
    }

    @Override
    public ToolResult<Map<String, Object>> execute(ToolContext context, CodeSearchRequest input) {
        return gateway.execute(context, input);
    }
}
