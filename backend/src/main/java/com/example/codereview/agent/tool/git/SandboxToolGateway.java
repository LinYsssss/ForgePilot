package com.example.codereview.agent.tool.git;

import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import java.util.Map;

public interface SandboxToolGateway {

    ToolResult<Map<String, Object>> execute(ToolContext context, SandboxToolRequest request);
}
