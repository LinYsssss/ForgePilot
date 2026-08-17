package com.example.codereview.assistant;

import java.util.List;

public record AssistantPrompt(String systemMessage, String userMessage, int promptChars,
                              List<String> sourceIds, List<String> truncatedSections) {
    public AssistantPrompt {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        truncatedSections = truncatedSections == null ? List.of() : List.copyOf(truncatedSections);
    }
}
