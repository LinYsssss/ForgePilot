package com.example.codereview.assistant;

import com.example.codereview.ai.TokenUsage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface AssistantModelClient {
    TokenUsage stream(AssistantPrompt prompt, Consumer<String> onDelta, BooleanSupplier cancelled);
}
