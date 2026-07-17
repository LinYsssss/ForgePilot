package com.example.codereview.agent.model;

import com.example.codereview.finding.FindingSeverity;
import java.util.List;

public record FindingModelResponse(List<ModelFinding> findings) {
    public FindingModelResponse {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public record ModelFinding(
            FindingSeverity severity,
            String category,
            String title,
            String description,
            String filePath,
            Integer lineStart,
            Integer lineEnd,
            String symbol,
            String ruleId,
            List<String> citationIds
    ) {
        public ModelFinding {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }
}
