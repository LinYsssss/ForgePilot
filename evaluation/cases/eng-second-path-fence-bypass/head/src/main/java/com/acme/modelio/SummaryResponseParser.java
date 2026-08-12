package com.acme.modelio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class SummaryResponseParser {

    private final ObjectMapper objectMapper;

    public SummaryResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SummaryResponse parse(String rawModelOutput) {
        try {
            return objectMapper.readValue(rawModelOutput, SummaryResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("model returned an unreadable summary", e);
        }
    }
}
