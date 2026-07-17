package com.example.codereview.agent.model;

import java.util.Objects;

public interface AgentModelClient {

    String provider();

    String model();

    ModelResponse generate(PromptEnvelope prompt);

    ModelResponse repairJson(String invalidOutput, String validationError);

    record ModelResponse(
            String provider,
            String model,
            String content,
            long inputTokens,
            long outputTokens,
            String finishReason,
            long latencyMs
    ) {
        public ModelResponse {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(content, "content");
            finishReason = finishReason == null || finishReason.isBlank() ? "UNKNOWN" : finishReason;
            if (inputTokens < 0 || outputTokens < 0 || latencyMs < 0) {
                throw new IllegalArgumentException("model response metrics must be non-negative");
            }
        }
    }
}
