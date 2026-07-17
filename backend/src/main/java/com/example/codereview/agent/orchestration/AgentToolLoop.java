package com.example.codereview.agent.orchestration;

import com.example.codereview.agent.tool.AgentToolRegistry;
import com.example.codereview.agent.tool.ToolContext;
import com.example.codereview.agent.tool.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class AgentToolLoop {

    private final AgentToolRegistry tools;
    private final ObjectMapper mapper;

    public AgentToolLoop(AgentToolRegistry tools, ObjectMapper mapper) {
        this.tools = tools;
        this.mapper = mapper;
    }

    public List<ToolResultEnvelope> execute(
            LoopContext context,
            List<ToolRequest> requests,
            LoopPolicy policy,
            BooleanSupplier canceled
    ) {
        List<ToolResultEnvelope> results = new ArrayList<>();
        Set<String> requestIds = new HashSet<>();
        int considered = 0;
        for (ToolRequest request : requests == null ? List.<ToolRequest>of() : requests) {
            if (canceled.getAsBoolean()) {
                results.add(rejected(request, ToolStatus.CANCELED, "Agent run cancellation requested"));
                break;
            }
            String violation = validate(request, policy, requestIds, considered++);
            if (violation != null) {
                results.add(rejected(request, ToolStatus.POLICY_REJECTED, violation));
                continue;
            }
            requestIds.add(request.requestId());
            ToolContext toolContext = new ToolContext(
                    context.agentRunId(),
                    context.agentStepId(),
                    "agent:" + context.agentRunId() + ":model-tool:" + request.requestId(),
                    context.approved(),
                    context.traceId()
            );
            try {
                ToolResult<?> result = tools.execute(request.toolName(), request.arguments(), toolContext);
                results.add(new ToolResultEnvelope(
                        request.requestId(),
                        request.toolName(),
                        result.success() ? ToolStatus.SUCCESS : classifyFailure(result.error()),
                        sanitize(result.data()),
                        bounded(result.error())
                ));
            } catch (IllegalArgumentException | IllegalStateException policyFailure) {
                results.add(rejected(request, ToolStatus.POLICY_REJECTED, policyFailure.getMessage()));
            } catch (RuntimeException executionFailure) {
                results.add(rejected(request, ToolStatus.EXECUTION_FAILED, executionFailure.getMessage()));
            }
        }
        return List.copyOf(results);
    }

    private String validate(
            ToolRequest request,
            LoopPolicy policy,
            Set<String> requestIds,
            int executed
    ) {
        if (request == null || request.requestId() == null || request.requestId().isBlank()) {
            return "model tool request ID is required";
        }
        if (!requestIds.add(request.requestId())) {
            return "duplicate model tool request ID";
        }
        requestIds.remove(request.requestId());
        if (executed >= policy.maxToolCalls()) {
            return "tool call budget exhausted";
        }
        if (request.toolName() == null || !policy.plannedTools().contains(request.toolName())) {
            return "tool is not a member of the validated plan";
        }
        if (!policy.allowedTools().contains(request.toolName())) {
            return "tool is not allowed in the current state or project";
        }
        if (request.arguments() == null || !request.arguments().isObject()) {
            return "tool arguments must be a JSON object";
        }
        if (jsonBytes(request.arguments()) > policy.maxArgumentBytes()) {
            return "tool arguments exceed byte limit";
        }
        return inspect(request.arguments());
    }

    private String inspect(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().toLowerCase(Locale.ROOT);
                if (normalized.equals("command") || normalized.equals("shell")
                        || normalized.contains("executable")) {
                    return "raw command and executable fields are forbidden";
                }
                if ((normalized.contains("path") || normalized.contains("file"))
                        && field.getValue().isTextual() && traversal(field.getValue().asText())) {
                    return "path traversal is forbidden";
                }
                String nested = inspect(field.getValue());
                if (nested != null) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = inspect(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private boolean traversal(String value) {
        String normalized = value.replace('\\', '/');
        return normalized.equals("..") || normalized.startsWith("../")
                || normalized.contains("/../") || normalized.endsWith("/..");
    }

    private int jsonBytes(JsonNode value) {
        try {
            return mapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("tool arguments are not serializable", ex);
        }
    }

    private JsonNode sanitize(Object value) {
        return value == null ? mapper.nullNode() : mapper.valueToTree(value);
    }

    private ToolStatus classifyFailure(String error) {
        String value = error == null ? "" : error.toLowerCase(Locale.ROOT);
        return value.contains("environment") || value.contains("unavailable")
                ? ToolStatus.ENVIRONMENT_INCOMPLETE
                : ToolStatus.EXECUTION_FAILED;
    }

    private ToolResultEnvelope rejected(ToolRequest request, ToolStatus status, String error) {
        return new ToolResultEnvelope(
                request == null ? null : request.requestId(),
                request == null ? null : request.toolName(),
                status,
                mapper.nullNode(),
                bounded(error)
        );
    }

    private String bounded(String value) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= 2_000 ? value : new String(bytes, 0, 2_000, StandardCharsets.UTF_8);
    }

    public record ToolRequest(String requestId, String toolName, JsonNode arguments) {
    }

    public record LoopContext(Long agentRunId, Long agentStepId, String traceId, boolean approved) {
    }

    public record LoopPolicy(
            Set<String> plannedTools,
            Set<String> allowedTools,
            int maxToolCalls,
            int maxArgumentBytes
    ) {
        public LoopPolicy {
            plannedTools = Set.copyOf(plannedTools);
            allowedTools = Set.copyOf(allowedTools);
            if (maxToolCalls < 0 || maxArgumentBytes <= 0) {
                throw new IllegalArgumentException("invalid tool loop limits");
            }
        }
    }

    public record ToolResultEnvelope(
            String requestId,
            String toolName,
            ToolStatus status,
            JsonNode data,
            String error
    ) {
    }

    public enum ToolStatus {
        SUCCESS,
        ENVIRONMENT_INCOMPLETE,
        POLICY_REJECTED,
        EXECUTION_FAILED,
        CANCELED
    }
}
