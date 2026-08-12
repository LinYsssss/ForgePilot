package com.acme.modelio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class PlanResponseParser {

    private final ObjectMapper objectMapper;

    public PlanResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PlanResponse parse(String rawModelOutput) {
        try {
            return objectMapper.readValue(ModelOutputs.stripCodeFence(rawModelOutput), PlanResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("model returned an unreadable plan", e);
        }
    }
}
