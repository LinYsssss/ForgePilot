package com.example.codereview.agent.plan;

import com.example.codereview.agent.tool.AgentToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReviewPlanValidator {

    private final AgentToolRegistry tools;
    private final ObjectMapper mapper;
    private final Map<String, Integer> perToolLimits;
    private final int defaultToolLimit;
    private final int maxArgumentBytes;

    @Autowired
    public ReviewPlanValidator(
            AgentToolRegistry tools,
            ObjectMapper mapper,
            @Value("${app.agent.plan.default-tool-limit:3}") int defaultToolLimit,
            @Value("${app.agent.plan.max-argument-bytes:16384}") int maxArgumentBytes
    ) {
        this.tools = tools;
        this.mapper = mapper;
        this.perToolLimits = Map.of();
        this.defaultToolLimit = defaultToolLimit;
        this.maxArgumentBytes = maxArgumentBytes;
    }

    ReviewPlanValidator(
            AgentToolRegistry tools,
            ObjectMapper mapper,
            Map<String, Integer> perToolLimits,
            int maxArgumentBytes
    ) {
        this.tools = tools;
        this.mapper = mapper;
        this.perToolLimits = Map.copyOf(perToolLimits);
        this.defaultToolLimit = 3;
        this.maxArgumentBytes = maxArgumentBytes;
    }

    public ValidationResult validate(List<ReviewPlan.PlanItem> items, boolean approved) {
        return validate(items, approved, PlanPolicy.unrestricted());
    }

    public ValidationResult validate(
            List<ReviewPlan.PlanItem> items,
            boolean approved,
            PlanPolicy policy
    ) {
        List<String> errors = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return new ValidationResult(false, List.of(), List.of("plan must not be empty"));
        }

        Map<String, Integer> counts = new HashMap<>();
        Set<String> requestIds = new java.util.HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            ReviewPlan.PlanItem item = items.get(index);
            String prefix = "item[" + index + "]: ";
            if (item == null || item.toolName() == null || item.toolName().isBlank()) {
                errors.add(prefix + "tool name is required");
                continue;
            }
            if (!tools.contains(item.toolName())) {
                errors.add(prefix + "unknown tool " + item.toolName());
                continue;
            }
            if (!policy.allowsState(item.toolName())) {
                errors.add(prefix + "tool is not allowed in current state");
            }
            if (!policy.authorizesProject(item.toolName())) {
                errors.add(prefix + "tool is not authorized for project");
            }
            int count = counts.merge(item.toolName(), 1, Integer::sum);
            if (counts.values().stream().mapToInt(Integer::intValue).sum() > policy.remainingToolCalls()) {
                errors.add(prefix + "remaining budget does not allow another tool call");
            }
            int limit = perToolLimits.getOrDefault(item.toolName(), defaultToolLimit);
            if (count > limit) {
                errors.add(prefix + "tool repetition exceeds limit " + limit);
            }
            if (tools.riskLevel(item.toolName()).isApprovalRequired() && !approved) {
                errors.add(prefix + "tool requires approval");
            }
            if (item.arguments() == null) {
                errors.add(prefix + "arguments are required");
            } else if (jsonBytes(item.arguments()) > maxArgumentBytes) {
                errors.add(prefix + "arguments exceed " + maxArgumentBytes + " bytes");
            }
            if (item.purpose() == null || item.purpose().isBlank()) {
                errors.add(prefix + "purpose is required");
            }
            if (item.expectedEvidence() == null || item.expectedEvidence().isBlank()) {
                errors.add(prefix + "expected evidence is required");
            }
            if (policy.requireModelRequestIds()) {
                if (item.modelRequestId() == null || item.modelRequestId().isBlank()) {
                    errors.add(prefix + "model tool request ID is required");
                } else if (!requestIds.add(item.modelRequestId())) {
                    errors.add(prefix + "duplicate model tool request ID");
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors.isEmpty() ? List.copyOf(items) : List.of(), List.copyOf(errors));
    }

    private int jsonBytes(Object value) {
        try {
            return mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Plan arguments are not serializable", ex);
        }
    }

    public record ValidationResult(
            boolean valid,
            List<ReviewPlan.PlanItem> validatedItems,
            List<String> errors
    ) {
    }

    public record PlanPolicy(
            Set<String> stateAllowedTools,
            Set<String> activePlugins,
            Set<String> projectAuthorizedTools,
            int remainingToolCalls,
            boolean requireModelRequestIds
    ) {
        public PlanPolicy(
                Set<String> stateAllowedTools,
                Set<String> activePlugins,
                Set<String> projectAuthorizedTools,
                int remainingToolCalls
        ) {
            this(stateAllowedTools, activePlugins, projectAuthorizedTools, remainingToolCalls, false);
        }
        public PlanPolicy {
            stateAllowedTools = Set.copyOf(stateAllowedTools);
            activePlugins = Set.copyOf(activePlugins);
            projectAuthorizedTools = Set.copyOf(projectAuthorizedTools);
            if (remainingToolCalls < 0) {
                throw new IllegalArgumentException("remaining tool calls must be non-negative");
            }
        }

        boolean allowsState(String toolName) {
            return stateAllowedTools.contains("*") || stateAllowedTools.contains(toolName);
        }

        boolean authorizesProject(String toolName) {
            return projectAuthorizedTools.contains("*") || projectAuthorizedTools.contains(toolName);
        }

        public static PlanPolicy unrestricted() {
            return new PlanPolicy(Set.of("*"), Set.of(), Set.of("*"), Integer.MAX_VALUE, false);
        }
    }
}
