package com.example.codereview.agent.model;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.plan.ReviewPlanValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ModelOutputValidator {

    private final ObjectMapper mapper;
    private final ReviewPlanValidator plans;
    private final int maxOutputBytes;

    public ModelOutputValidator(
            ObjectMapper mapper,
            ReviewPlanValidator plans,
            @Value("${app.agent.model.max-output-bytes:65536}") int maxOutputBytes
    ) {
        this.mapper = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.plans = plans;
        this.maxOutputBytes = maxOutputBytes;
    }

    public ValidationResult validate(
            String rawOutput,
            boolean approved,
            Function<String, String> repair
    ) {
        return validate(rawOutput, approved, List.of(), repair);
    }

    public ValidationResult validate(
            String rawOutput,
            boolean approved,
            List<String> allowedCitationIds,
            Function<String, String> repair
    ) {
        return validateInternal(rawOutput, approved, allowedCitationIds, null, repair);
    }

    public ValidationResult validate(
            String rawOutput,
            boolean approved,
            List<String> allowedCitationIds,
            ReviewPlanValidator.PlanPolicy planPolicy,
            Function<String, String> repair
    ) {
        return validateInternal(rawOutput, approved, allowedCitationIds, planPolicy, repair);
    }

    private ValidationResult validateInternal(
            String rawOutput,
            boolean approved,
            List<String> allowedCitationIds,
            ReviewPlanValidator.PlanPolicy planPolicy,
            Function<String, String> repair
    ) {
        Set<String> allowed = Set.copyOf(allowedCitationIds == null ? List.of() : allowedCitationIds);
        ValidationResult first = validateOnce(rawOutput, approved, allowed, planPolicy);
        if (first.valid()) {
            return first;
        }
        String repaired;
        try {
            repaired = repair.apply(rawOutput);
        } catch (RuntimeException ex) {
            return invalid("JSON repair failed");
        }
        ValidationResult second = validateOnce(repaired, approved, allowed, planPolicy);
        return second.valid() ? second : invalid(second.error());
    }

    private ValidationResult validateOnce(
            String rawOutput,
            boolean approved,
            Set<String> allowedCitationIds,
            ReviewPlanValidator.PlanPolicy planPolicy
    ) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return invalid("model output is empty");
        }
        if (rawOutput.getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
            return invalid("model output size exceeds " + maxOutputBytes + " bytes");
        }
        String json = ModelJsonOutputs.unwrapMarkdown(rawOutput);
        try {
            StructuredModelResponse response = mapper.readValue(json, StructuredModelResponse.class);
            if (response.summary() == null || response.summary().isBlank()) {
                return invalid("summary is required");
            }
            var planItems = response.plan() == null ? List.<com.example.codereview.agent.plan.ReviewPlan.PlanItem>of() : response.plan();
            var planResult = planPolicy == null
                    ? plans.validate(planItems, approved)
                    : plans.validate(planItems, approved, planPolicy);
            if (!planResult.valid()) {
                return invalid(String.join("; ", planResult.errors()));
            }
            String citationError = validateCitations(response.claims(), allowedCitationIds);
            if (citationError != null) {
                return invalid(citationError);
            }
            // 计划校验器可能裁剪了超预算条目(clampOverBudget)。验证器对外只交付
            // "经裁剪的规范形态":下游(持久化的 ReviewPlan、EXECUTING_TOOLS)只见
            // 存活条目,不必各自理解裁剪语义。
            if (planResult.validatedItems().size() != planItems.size()) {
                response = new StructuredModelResponse(
                        response.summary(), planResult.validatedItems(), response.claims()
                );
            }
            return new ValidationResult(true, response, null, null);
        } catch (JsonProcessingException ex) {
            String message = ex.getOriginalMessage();
            if (message != null && message.toLowerCase().contains("unrecognized field")) {
                message = "unknown field: " + message;
            }
            return invalid(limit(message));
        }
    }

    private String validateCitations(
            List<StructuredModelResponse.CitedClaim> claims,
            Set<String> allowedCitationIds
    ) {
        for (StructuredModelResponse.CitedClaim claim : claims) {
            List<String> citations = claim.citationIds();
            if (claim.knowledgeBacked() && citations.isEmpty()) {
                return "citation is required for knowledge-backed claim";
            }
            Set<String> unique = new HashSet<>();
            for (String citation : citations) {
                if (citation == null || citation.isBlank() || !allowedCitationIds.contains(citation)) {
                    return "unknown citation: " + limit(citation);
                }
                if (!unique.add(citation)) {
                    return "duplicate citation: " + citation;
                }
            }
        }
        return null;
    }

    private ValidationResult invalid(String error) {
        return new ValidationResult(
                false,
                null,
                AgentFailureType.INVALID_MODEL_OUTPUT,
                limit(error)
        );
    }

    private String limit(String error) {
        if (error == null) {
            return "invalid model output";
        }
        return error.length() <= 2_000 ? error : error.substring(0, 2_000);
    }

    public record ValidationResult(
            boolean valid,
            StructuredModelResponse response,
            AgentFailureType failureType,
            String error
    ) {
    }
}
