package com.example.codereview.agent.tool.git;

import com.example.codereview.agent.tool.AgentTool;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.example.codereview.agent.tool.ToolRiskLevel;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class LanguageCommandTool implements AgentTool<LanguageCommandRequest, Map<String, Object>> {

    private final SandboxToolGateway gateway;

    public LanguageCommandTool(SandboxToolGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() { return "language.command"; }

    @Override
    public String description() {
        return "Execute one plugin-declared fixed language analysis command in the signed sandbox";
    }

    @Override
    public Class<LanguageCommandRequest> inputType() { return LanguageCommandRequest.class; }

    @Override
    public ToolRiskLevel riskLevel() { return ToolRiskLevel.READ_ONLY; }

    @Override
    public ToolResult<Map<String, Object>> execute(ToolContext context, LanguageCommandRequest input) {
        return gateway.execute(context, input);
    }
}
