package com.example.codereview.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.model.AgentFindingModelService;
import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.FindingModelResponse;
import com.example.codereview.agent.model.PromptEnvelope;
import com.example.codereview.agent.orchestration.steps.VerifyingFindingsStepExecutor;
import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.prompt.PromptTemplateRegistry;
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
import org.mockito.ArgumentCaptor;

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

    // run16 实证(2026-08-09):无知识项目检索证据为空,citation 白名单为空集,原提示词仍
    // 强制"必须引用"——模型只能编造 citation,在 AgentFindingModelService 全数被裁并触发
    // 非空全灭硬错。空知识分支必须改为要求 citationIds 为空数组,schema 示例同步改空。
    @Test
    void emptyKnowledgeSwitchesPromptToEmptyCitationContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // r8-R1 后指令文本来自模板注册表:spy 真组装器让 instruction() 走真模板
        // (citation 契约断言连带钉住模板文件),assemble 仍打桩隔离信封逻辑。
        AgentPromptAssembler prompts = spy(new AgentPromptAssembler(new PromptTemplateRegistry()));
        AgentPromptAssembler.Input input = capturedPromptInput(
                mapper, prompts, 3L, "abcdef3",
                new AgentRetrievedContextCheckpoint("agent-retrieved-context-v1", List.of())
        );

        assertThat(input.taskInstruction())
                .contains("\"citationIds\" must be an empty array")
                .contains("base every finding strictly on the supplied diff and change analyses")
                .contains("untrusted data")
                .doesNotContain("at least one citationId");
        assertThat(input.outputSchema()).contains("\"citationIds\":[]");
    }

    // 知识非空的档位回归:强制引用指令与带示例 id 的 schema 保持不变。
    @Test
    void suppliedKnowledgeKeepsMandatoryCitationContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // r8-R1 后指令文本来自模板注册表:spy 真组装器让 instruction() 走真模板
        // (citation 契约断言连带钉住模板文件),assemble 仍打桩隔离信封逻辑。
        AgentPromptAssembler prompts = spy(new AgentPromptAssembler(new PromptTemplateRegistry()));
        AgentPromptAssembler.Input input = capturedPromptInput(
                mapper, prompts, 4L, "abcdef4",
                new AgentRetrievedContextCheckpoint("agent-retrieved-context-v1", List.of(
                        new AgentRetrievedContextCheckpoint.Evidence(
                                "Close connections in finally blocks.", "rule#chunk-1",
                                "rule", 1, "GUIDELINE", "v1", 0.9, true
                        )
                ))
        );

        assertThat(input.taskInstruction())
                .contains("at least one citationId taken verbatim")
                .doesNotContain("must be an empty array");
        assertThat(input.outputSchema()).contains("\"citationIds\":[\"citation\"]");
    }

    private AgentPromptAssembler.Input capturedPromptInput(
            ObjectMapper mapper,
            AgentPromptAssembler prompts,
            Long runId,
            String headSha,
            AgentRetrievedContextCheckpoint retrieved
    ) throws Exception {
        AgentAnalysisContextRepository contexts = mock(AgentAnalysisContextRepository.class);
        AgentFindingModelService findingModel = mock(AgentFindingModelService.class);
        AgentAnalysisContext stored = new AgentAnalysisContext(runId, headSha);
        // 夹具沿用契约线格式(裸文件名),不再延续已被判死的 workspace:// 旧格式。
        stored.repositoryPrepared("agent-run-" + runId + "-" + headSha + ".tar", "abcdef0", "{}", "diff");
        stored.changeAnalyzed(mapper.writeValueAsString(new AgentChangeAnalysisCheckpoint(
                "agent-change-analysis-v1",
                new ChangeSet("abcdef0", headSha, List.of()),
                List.of(), List.of()
        )));
        stored.contextRetrieved(mapper.writeValueAsString(retrieved));
        when(contexts.findByAgentRunId(runId)).thenReturn(Optional.of(stored));
        when(contexts.save(any())).thenAnswer(call -> call.getArgument(0));
        doReturn(new PromptEnvelope(
                "policy", "findings", "diff", "", "", "", "{}",
                "review-v1", null, "finding-candidates-v1", List.of(), List.of()
        )).when(prompts).assemble(any());
        when(findingModel.generate(any(), any(), any(), any())).thenReturn(
                new AgentFindingModelService.Result(new FindingModelResponse(List.of()), 1, 1, 1)
        );
        AgentFindingPipeline pipeline = new AgentFindingPipeline(
                new FindingDeduplicator(), new FindingVerifier(),
                new GateDecisionService(new FindingConfidenceService(), 0.70)
        );

        new VerifyingFindingsStepExecutor(
                contexts, Optional.of(mock(AgentModelClient.class)), findingModel,
                prompts, pipeline, mock(FindingRepository.class),
                mock(FindingEvidenceRepository.class), mock(FindingDecisionRepository.class),
                mock(FindingScoreContributionRepository.class), mapper
        ).execute(new AgentStepExecutionContext(
                runId, 15L, 2L, 3L, 4L, headSha,
                AgentRunStatus.VERIFYING_FINDINGS, 6, 0, "trace", false
        ));

        ArgumentCaptor<AgentPromptAssembler.Input> captor =
                ArgumentCaptor.forClass(AgentPromptAssembler.Input.class);
        verify(prompts).assemble(captor.capture());
        return captor.getValue();
    }
}
