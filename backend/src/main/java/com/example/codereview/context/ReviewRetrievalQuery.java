package com.example.codereview.context;

import java.util.List;
import java.util.Objects;

public record ReviewRetrievalQuery(
        Long projectId,
        List<Long> documentIds,
        String sourceVersion,
        int contextByteBudget,
        double scoreThreshold,
        int topK,
        List<String> changedPaths,
        List<String> symbols,
        List<String> imports,
        List<String> annotations,
        List<String> strings,
        List<String> toolRuleIds
) {
    public ReviewRetrievalQuery {
        Objects.requireNonNull(projectId, "projectId");
        sourceVersion = requireText(sourceVersion, "sourceVersion");
        documentIds = copy(documentIds);
        changedPaths = copy(changedPaths);
        symbols = copy(symbols);
        imports = copy(imports);
        annotations = copy(annotations);
        strings = copy(strings);
        toolRuleIds = copy(toolRuleIds);
        if (contextByteBudget <= 0) {
            throw new IllegalArgumentException("context byte budget must be bounded and positive");
        }
        if (scoreThreshold < 0 || scoreThreshold > 1) {
            throw new IllegalArgumentException("score threshold must be between 0 and 1");
        }
        if (topK <= 0 || topK > 100) {
            throw new IllegalArgumentException("topK must be between 1 and 100");
        }
    }

    public ReviewContextService.Request toDomainRequest() {
        return new ReviewContextService.Request(
                projectId,
                documentIds,
                sourceVersion,
                contextByteBudget,
                scoreThreshold,
                topK,
                changedPaths,
                symbols,
                imports,
                annotations,
                strings,
                toolRuleIds
        );
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
