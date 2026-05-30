package com.example.codereview.ai;

import java.util.List;

public record AiReviewResult(
        String summary,
        String overallRisk,
        List<Issue> issues,
        String rawResponse
) {
    public record Issue(
            String severity,
            String category,
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            String title,
            String description,
            String impact,
            String evidence,
            String suggestion,
            Double confidence
    ) {
    }
}
