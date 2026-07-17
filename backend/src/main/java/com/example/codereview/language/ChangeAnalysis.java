package com.example.codereview.language;

import com.example.codereview.finding.FindingCandidate;
import java.util.List;

public record ChangeAnalysis(
        String pluginId,
        List<ToolCommand> commands,
        List<FindingCandidate> findingCandidates,
        List<String> environmentResults) {

    public ChangeAnalysis {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId is required");
        }
        commands = commands == null ? List.of() : List.copyOf(commands);
        findingCandidates = findingCandidates == null ? List.of() : List.copyOf(findingCandidates);
        environmentResults = environmentResults == null ? List.of() : List.copyOf(environmentResults);
    }
}
