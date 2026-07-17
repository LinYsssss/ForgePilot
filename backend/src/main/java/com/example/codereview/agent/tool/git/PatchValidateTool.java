package com.example.codereview.agent.tool.git;

import com.example.codereview.agent.tool.AgentTool;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.agent.tool.ToolRiskLevel;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class PatchValidateTool implements AgentTool<PatchValidateRequest, Map<String, Object>> {
    private final SandboxToolGateway gateway;
    public PatchValidateTool(SandboxToolGateway gateway) { this.gateway = gateway; }
    public String name() { return "patch.validate"; }
    public Class<PatchValidateRequest> inputType() { return PatchValidateRequest.class; }
    public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ_ONLY; }
    public ToolResult<Map<String, Object>> execute(ToolContext context, PatchValidateRequest input) {
        return gateway.execute(context, input);
    }
}
