package com.example.codereview.model;

public record ModelRiskSignal(
        String riskType,
        String severity,
        double confidence,
        String modelVersion,
        String source
) {
    public String toPromptContext() {
        return """
                轻量模型风险预判：
                - riskType: %s
                - severity: %s
                - confidence: %.4f
                - modelVersion: %s
                - source: %s
                """.formatted(
                blankToUnknown(riskType),
                blankToUnknown(severity),
                confidence,
                blankToUnknown(modelVersion),
                blankToUnknown(source)
        ).trim();
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
