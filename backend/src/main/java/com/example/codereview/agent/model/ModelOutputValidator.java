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
        Set<String> allowed = Set.copyOf(allowedCitationIds == null ? List.of() : allowedCitationIds);
        ValidationResult first = validateOnce(rawOutput, approved, allowed);
        if (first.valid()) {
            return first;
        }
        String repaired;
        try {
            repaired = repair.apply(rawOutput);
        } catch (RuntimeException ex) {
            return invalid("JSON repair failed");
        }
        ValidationResult second = validateOnce(repaired, approved, allowed);
        return second.valid() ? second : invalid(second.error());
    }

    private ValidationResult validateOnce(String rawOutput, boolean approved, Set<String> allowedCitationIds) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return invalid("model output is empty");
        }
        if (rawOutput.getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
            return invalid("model output size exceeds " + maxOutputBytes + " bytes");
        }
        String json = unwrapMarkdown(rawOutput);
        try {
            StructuredModelResponse response = mapper.readValue(json, StructuredModelResponse.class);
            if (response.summary() == null || response.summary().isBlank()) {
                return invalid("summary is required");
            }
            var planResult = plans.validate(
                    response.plan() == null ? List.of() : response.plan(),
                    approved
            );
            if (!planResult.valid()) {
                return invalid(String.join("; ", planResult.errors()));
            }
            String citationError = validateCitations(response.claims(), allowedCitationIds);
            if (citationError != null) {
                return invalid(citationError);
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

    private String unwrapMarkdown(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int closing = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || closing <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, closing).trim();
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
