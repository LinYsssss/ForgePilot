package com.example.codereview.agent.tool.git;

import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import java.util.Map;

/**
 * Safe deployment default until the asynchronous signed sandbox-result channel is wired. It never
 * executes repository commands in the backend process.
 */
final class UnavailableSandboxToolGateway implements SandboxToolGateway {

    @Override
    public ToolResult<Map<String, Object>> execute(ToolContext context, SandboxToolRequest request) {
        return ToolResult.failure("ENVIRONMENT_INCOMPLETE: sandbox result channel is unavailable");
    }
}
