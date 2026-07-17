package com.example.codereview.agent.orchestration;

import com.example.codereview.language.ChangeAnalysis;
import com.example.codereview.language.ChangeSet;
import java.util.List;

public record AgentChangeAnalysisCheckpoint(
        String version,
        ChangeSet changeSet,
        List<String> pluginIds,
        List<ChangeAnalysis> analyses
) {
    public AgentChangeAnalysisCheckpoint {
        if (!"agent-change-analysis-v1".equals(version)) {
            throw new IllegalArgumentException("unsupported change analysis checkpoint version");
        }
        pluginIds = pluginIds == null ? List.of() : List.copyOf(pluginIds);
        analyses = analyses == null ? List.of() : List.copyOf(analyses);
    }
}
