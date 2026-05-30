package com.example.codereview.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingJson {

    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public EmbeddingJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(List<Double> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize embedding", ex);
        }
    }

    public List<Double> read(String json) {
        try {
            return objectMapper.readValue(json, DOUBLE_LIST);
        } catch (Exception ex) {
            return List.of();
        }
    }

    public String toPgVector(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values.get(i));
        }
        return builder.append(']').toString();
    }
}
