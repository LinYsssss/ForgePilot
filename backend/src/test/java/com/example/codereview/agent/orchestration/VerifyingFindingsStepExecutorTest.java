package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.model.AgentFindingModelService;
import com.example.codereview.agent.orchestration.steps.VerifyingFindingsStepExecutor;
import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.finding.FindingConfidenceService;
import com.example.codereview.finding.FindingDecisionRepository;
import com.example.codereview.finding.FindingDeduplicator;
import com.example.codereview.finding.FindingEvidenceRepository;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.finding.FindingScoreContributionRepository;
import com.example.codereview.finding.FindingVerifier;
import com.example.codereview.finding.FindingCandidate;
import com.example.codereview.finding.FindingEvidence;
import com.example.codereview.finding.EvidenceType;
import com.example.codereview.finding.FindingSeverity;
import com.example.codereview.finding.FindingDecisionEntity;
import com.example.codereview.finding.FindingEvidenceEntity;
import com.example.codereview.language.ChangeAnalysis;
import com.example.codereview.finding.GateDecisionService;
import com.example.codereview.language.ChangeSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VerifyingFindingsStepExecutorTest {

    @Test
    void cleanRunSkipsPatchAndAdvancesDirectlyToPublication() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        AgentAnalysisContext stored = new AgentAnalysisContext(1L, "abcdef1");
        stored.repositoryPrepared("workspace://archive", "abcdef0", "{}", "diff");
        stored.changeAnalyzed(mapper.writeValueAsString(new AgentChangeAnalysisCheckpoint(
                "agent-change-analysis-v1",
                new ChangeSet("abcdef0", "abcdef1", List.of()),
                List.of(), List.of()
        )));
        stored.contextRetrieved(mapper.writeValueAsString(
                new AgentRetrievedContextCheckpoint("agent-retrieved-context-v1", List.of())
        ));
        when(contexts.findByAgentRunId(1L)).thenReturn(Optional.of(stored));
        when(contexts.save(any())).thenAnswer(call -> call.getArgument(0));
        AgentFindingPipeline pipeline = new AgentFindingPipeline(
                new FindingDeduplicator(), new FindingVerifier(),
                new GateDecisionService(new FindingConfidenceService(), 0.70)
        );

        AgentStepResult result = new VerifyingFindingsStepExecutor(
                contexts, Optional.empty(), mock(AgentFindingModelService.class),
                mock(AgentPromptAssembler.class), pipeline, findings,
                mock(FindingEvidenceRepository.class), mock(FindingDecisionRepository.class),
                mock(FindingScoreContributionRepository.class), mapper
        ).execute(new AgentStepExecutionContext(
                1L, 13L, 2L, 3L, 4L, "abcdef1",
                AgentRunStatus.VERIFYING_FINDINGS, 6, 0, "trace", false
        ));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.PUBLISHING_RESULT);
        assertThat(result.output()).containsEntry("blocking", false).containsEntry("findings", 0);
        verify(findings, never()).saveAndFlush(any());
        verify(contexts).save(stored);
    }

    @Test
    void staticFindingPersistsNormalizedEvidenceConfidenceAndBlocks() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        FindingEvidenceRepository evidence = mock(FindingEvidenceRepository.class);
        FindingDecisionRepository decisions = mock(FindingDecisionRepository.class);
        FindingScoreContributionRepository contributions = mock(FindingScoreContributionRepository.class);
        FindingCandidate candidate = new FindingCandidate(
                FindingSeverity.HIGH, "python.S101", "S101", "assert used",
                "app.py", 3, 3, null, "S101", "abcdef2",
                List.of(FindingEvidence.create(
                        EvidenceType.STATIC_ANALYZER, "abcdef2", "app.py", 3, 3,
                        "assert used", 1.0
                ))
        );
        AgentAnalysisContext stored = new AgentAnalysisContext(2L, "abcdef2");
        stored.repositoryPrepared("workspace://archive", "abcdef0", "{}", "diff");
        stored.changeAnalyzed(mapper.writeValueAsString(new AgentChangeAnalysisCheckpoint(
                "agent-change-analysis-v1",
                new ChangeSet("abcdef0", "abcdef2", List.of(
                        new ChangeSet.FileChange("app.py", ChangeSet.ChangeType.MODIFIED)
                )),
                List.of("python"),
                List.of(new ChangeAnalysis("python", List.of(), List.of(candidate), List.of()))
        )));
        stored.contextRetrieved(mapper.writeValueAsString(
                new AgentRetrievedContextCheckpoint("agent-retrieved-context-v1", List.of())
        ));
        when(contexts.findByAgentRunId(2L)).thenReturn(Optional.of(stored));
        when(contexts.save(any())).thenAnswer(call -> call.getArgument(0));
        when(findings.saveAndFlush(any())).thenAnswer(call -> {
            Object entity = call.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", 101L);
            return entity;
        });
        when(decisions.saveAndFlush(any())).thenAnswer(call -> {
            FindingDecisionEntity entity = call.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", 201L);
            return entity;
        });
        AgentFindingPipeline pipeline = new AgentFindingPipeline(
                new FindingDeduplicator(), new FindingVerifier(),
                new GateDecisionService(new FindingConfidenceService(), 0.70)
        );

        AgentStepResult result = new VerifyingFindingsStepExecutor(
                contexts, Optional.empty(), mock(AgentFindingModelService.class),
                mock(AgentPromptAssembler.class), pipeline, findings, evidence, decisions,
                contributions, mapper
        ).execute(new AgentStepExecutionContext(
                2L, 14L, 2L, 3L, 4L, "abcdef2",
                AgentRunStatus.VERIFYING_FINDINGS, 6, 0, "trace", false
        ));

        assertThat(result.nextState()).isEqualTo(AgentRunStatus.GENERATING_PATCH);
        verify(evidence, org.mockito.Mockito.times(3)).save(any(FindingEvidenceEntity.class));
        verify(decisions).saveAndFlush(any(FindingDecisionEntity.class));
        verify(contributions, org.mockito.Mockito.atLeast(3)).save(any());
    }
}
