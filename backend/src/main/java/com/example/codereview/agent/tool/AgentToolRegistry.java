package com.example.codereview.agent.tool;

import com.example.codereview.agent.observability.AgentMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentToolRegistry {

    private static final List<String> SECRET_MARKERS = List.of(
            "token", "password", "secret", "api_key", "apikey", "credential"
    );

    private final Map<String, AgentTool<?, ?>> tools;
    private final ObjectMapper mapper;
    private final ToolInvocationRepository invocations;
    private final AgentMetrics metrics;
    private final int maxInputBytes;
    private final int maxOutputBytes;

    public AgentToolRegistry(
            List<AgentTool<?, ?>> tools,
            ObjectMapper mapper,
            ToolInvocationRepository invocations,
            AgentMetrics metrics,
            @Value("${app.agent.tool.max-input-bytes:16384}") int maxInputBytes,
            @Value("${app.agent.tool.max-output-bytes:65536}") int maxOutputBytes
    ) {
        this.tools = index(tools);
        this.mapper = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.invocations = invocations;
        this.metrics = metrics;
        this.maxInputBytes = maxInputBytes;
        this.maxOutputBytes = maxOutputBytes;
    }

    public ToolResult<?> execute(String toolName, JsonNode input, ToolContext context) {
        AgentTool<?, ?> tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown Agent tool: " + toolName);
        }
        rejectRawCommands(input);
        if (tool.riskLevel().isApprovalRequired() && !context.approved()) {
            throw new IllegalStateException("Tool requires approval: " + toolName);
        }

        var existing = invocations.findByInvocationKey(context.invocationKey());
        if (existing.isPresent()) {
            ToolInvocation invocation = existing.get();
            if (!invocation.isSucceeded()) {
                throw new IllegalStateException("Tool invocation is already in progress or failed");
            }
            return cachedResult(invocation);
        }

        JsonNode sanitizedInput = sanitize(input.deepCopy());
        String inputJson = boundedJson(sanitizedInput, maxInputBytes, "Tool input");
        Object typedInput = convertInput(tool, input);
        ToolInvocation invocation = ToolInvocation.started(
                context.invocationKey(),
                context.agentRunId(),
                context.agentStepId(),
                toolName,
                inputJson,
                context.correlationId()
        );
        invocations.save(invocation);

        long started = System.nanoTime();
        try {
            ToolResult<?> result = executeTyped(tool, context, typedInput);
            JsonNode sanitizedOutput = sanitize(mapper.valueToTree(result.data()));
            String outputJson = boundedJson(sanitizedOutput, maxOutputBytes, "Tool output");
            long durationMs = elapsedMillis(started);
            if (result.success()) {
                invocation.succeed(outputJson, durationMs);
            } else {
                invocation.fail(redactText(result.error()), durationMs);
            }
            invocations.save(invocation);
            metrics.recordTool(toolName, result.success(), durationMs);
            return new ToolResult<>(result.success(), sanitizedOutput, redactText(result.error()));
        } catch (RuntimeException ex) {
            long durationMs = elapsedMillis(started);
            invocation.fail(redactText(ex.getMessage()), durationMs);
            invocations.save(invocation);
            metrics.recordTool(toolName, false, durationMs);
            throw ex;
        }
    }

    public boolean contains(String toolName) {
        return tools.containsKey(toolName);
    }

    public ToolRiskLevel riskLevel(String toolName) {
        AgentTool<?, ?> tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown Agent tool: " + toolName);
        }
        return tool.riskLevel();
    }

    private Map<String, AgentTool<?, ?>> index(List<AgentTool<?, ?>> candidates) {
        Map<String, AgentTool<?, ?>> indexed = new HashMap<>();
        for (AgentTool<?, ?> tool : candidates) {
            AgentTool<?, ?> previous = indexed.putIfAbsent(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("Duplicate Agent tool name: " + tool.name());
            }
        }
        return Map.copyOf(indexed);
    }

    private Object convertInput(AgentTool<?, ?> tool, JsonNode input) {
        try {
            return mapper.treeToValue(input, tool.inputType());
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Malformed input for tool " + tool.name(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResult<?> executeTyped(
            AgentTool<?, ?> rawTool,
            ToolContext context,
            Object input
    ) {
        AgentTool<Object, Object> tool = (AgentTool<Object, Object>) rawTool;
        return tool.execute(context, input);
    }

    private ToolResult<JsonNode> cachedResult(ToolInvocation invocation) {
        try {
            return ToolResult.success(mapper.readTree(invocation.getOutputJson()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored tool result is invalid JSON", ex);
        }
    }

    private void rejectRawCommands(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if ("command".equalsIgnoreCase(name) || "shell".equalsIgnoreCase(name)) {
                    throw new IllegalArgumentException("Raw command fields are forbidden");
                }
                rejectRawCommands(node.get(name));
            }
        } else if (node.isArray()) {
            node.forEach(this::rejectRawCommands);
        }
    }

    private JsonNode sanitize(JsonNode node) {
        if (node == null || node.isNull()) {
            return mapper.nullNode();
        }
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSecret(field.getKey())) {
                    object.put(field.getKey(), "[REDACTED]");
                } else {
                    object.set(field.getKey(), sanitize(field.getValue()));
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, sanitize(array.get(index)));
            }
        }
        return node;
    }

    private boolean isSecret(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return SECRET_MARKERS.stream().anyMatch(normalized::contains);
    }

    private String boundedJson(JsonNode node, int maxBytes, String label) {
        try {
            String json = mapper.writeValueAsString(node);
            if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IllegalArgumentException(label + " exceeds " + maxBytes + " bytes");
            }
            return json;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(label + " is not serializable", ex);
        }
    }

    private String redactText(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
