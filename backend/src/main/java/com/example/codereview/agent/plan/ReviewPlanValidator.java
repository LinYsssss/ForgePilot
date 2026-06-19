package com.example.codereview.agent.plan;

import com.example.codereview.agent.tool.AgentToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        List<String> errors = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return new ValidationResult(false, List.of(), List.of("plan must not be empty"));
        }

        Map<String, Integer> counts = new HashMap<>();
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
            int count = counts.merge(item.toolName(), 1, Integer::sum);
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
}
