package com.example.codereview.patch;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatchCandidateService {
    private final AgentRunRepository runs;
    private final FindingRepository findings;
    private final PatchCandidateRepository patches;
    private final UnifiedDiffValidator validator;

    public PatchCandidateService(AgentRunRepository runs, FindingRepository findings,
                                 PatchCandidateRepository patches, UnifiedDiffValidator validator) {
        this.runs = runs; this.findings = findings; this.patches = patches; this.validator = validator;
    }

    @Transactional
    public PatchCandidate create(CreateCommand command) {
        AgentRun run = runs.findById(command.agentRunId())
                .orElseThrow(() -> new IllegalArgumentException("agent run not found"));
        if (!run.getHeadSha().equals(command.boundHeadSha())
                || !command.boundHeadSha().equals(command.currentHeadSha())) {
            throw new IllegalArgumentException("stale head SHA");
        }
        Set<Long> ids = new LinkedHashSet<>(command.findingIds());
        List<Finding> attached = findings.findAllById(command.findingIds());
        if (attached.size() != ids.size()
                || attached.stream().anyMatch(f -> !command.agentRunId().equals(f.getAgentRunId()))) {
            throw new IllegalArgumentException("finding does not belong to agent run");
        }
        PatchValidation validation = validator.validate(command.patchContent());
        PatchCandidate candidate = new PatchCandidate(command.agentRunId(), command.boundHeadSha(), ids,
                command.generatorModel(), command.promptVersion(), command.patchContent(), validation);
        return patches.save(candidate);
    }

    public record CreateCommand(Long agentRunId, String boundHeadSha, String currentHeadSha,
                                List<Long> findingIds, String generatorModel, String promptVersion,
                                String patchContent) {
        public CreateCommand { findingIds = findingIds == null ? List.of() : List.copyOf(findingIds); }
    }
}
