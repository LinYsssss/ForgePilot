package com.example.codereview.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AssistantDtos {

    private AssistantDtos() {
    }

    public record HistoryMessage(
            @NotBlank @Size(max = 16) String role,
            @NotBlank @Size(max = 8000) String content
    ) {
    }

    public record StreamRequest(
            @NotBlank @Size(max = 4000) String message,
            @Valid @Size(max = 12) List<HistoryMessage> history
    ) {
    }

    public record SourcePayload(String id, String type, String title, String ref) {
    }

    public record ContextPayload(List<SourcePayload> sources, List<String> truncatedSections,
                                 List<String> warnings) {
    }

    public record DeltaPayload(String text) {
    }

    public record DonePayload(int promptTokens, int completionTokens, int totalTokens) {
    }

    public record ErrorPayload(String errorCode, String message) {
    }

    public record ConfigResponse(boolean enabled) {
    }
}
