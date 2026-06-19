package com.example.codereview.agent.model;

public interface AgentModelClient {
    ModelResponse generate(PromptEnvelope prompt);

    String repairJson(String invalidOutput, String validationError);

    record ModelResponse(
            String provider,
            String model,
            String content,
            long inputTokens,
            long outputTokens
    ) {
    }
}
