package com.example.codereview.agent.orchestration.steps;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.model.AgentFindingModelService;
import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.FindingModelResponse;
import com.example.codereview.agent.orchestration.AgentAnalysisContextRepository;
import com.example.codereview.agent.orchestration.AgentChangeAnalysisCheckpoint;
import com.example.codereview.agent.orchestration.AgentFindingPipeline;
import com.example.codereview.agent.orchestration.AgentRetrievedContextCheckpoint;
import com.example.codereview.agent.orchestration.AgentStepExecutionContext;
import com.example.codereview.agent.orchestration.AgentStepExecutor;
import com.example.codereview.agent.orchestration.AgentStepResult;
import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.context.ReviewContextService;
import com.example.codereview.finding.EvidenceType;
import com.example.codereview.finding.Finding;
import com.example.codereview.finding.FindingCandidate;
import com.example.codereview.finding.FindingDecisionEntity;
import com.example.codereview.finding.FindingDecisionRepository;
import com.example.codereview.finding.FindingEvidence;
import com.example.codereview.finding.FindingEvidenceEntity;
import com.example.codereview.finding.FindingEvidenceRepository;
import com.example.codereview.finding.FindingRepository;
import com.example.codereview.finding.FindingScoreContributionEntity;
import com.example.codereview.finding.FindingScoreContributionRepository;
import com.example.codereview.finding.FindingSeverity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class VerifyingFindingsStepExecutor implements AgentStepExecutor {

    /**
     * severity 合法值清单从枚举单源生成:提示词必须与反序列化契约同源,禁止手写
     * 字符串常量,防止提示词与校验规则漂移(与 ReviewPlanValidator.defaultToolLimit
     * 同一纪律,run11 实证教训)。
     */
    private static final String SEVERITY_VALUES = java.util.Arrays.stream(FindingSeverity.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.joining(", "));

    private final AgentAnalysisContextRepository contexts;
    private final Optional<AgentModelClient> client;
    private final AgentFindingModelService findingModel;
    private final AgentPromptAssembler prompts;
    private final AgentFindingPipeline pipeline;
    private final FindingRepository findings;
    private final FindingEvidenceRepository evidenceRepository;
    private final FindingDecisionRepository decisions;
    private final FindingScoreContributionRepository contributions;
    private final ObjectMapper mapper;

    @Autowired
    public VerifyingFindingsStepExecutor(
            AgentAnalysisContextRepository contexts,
            Optional<AgentModelClient> client,
            AgentFindingModelService findingModel,
            AgentPromptAssembler prompts,
            AgentFindingPipeline pipeline,
            FindingRepository findings,
            FindingEvidenceRepository evidenceRepository,
            FindingDecisionRepository decisions,
            FindingScoreContributionRepository contributions,
            ObjectMapper mapper
    ) {
        this.contexts = contexts;
        this.client = client;
        this.findingModel = findingModel;
        this.prompts = prompts;
        this.pipeline = pipeline;
        this.findings = findings;
        this.evidenceRepository = evidenceRepository;
        this.decisions = decisions;
        this.contributions = contributions;
        this.mapper = mapper;
    }

    public VerifyingFindingsStepExecutor() {
        this.contexts = null;
        this.client = Optional.empty();
        this.findingModel = null;
        this.prompts = null;
        this.pipeline = null;
        this.findings = null;
        this.evidenceRepository = null;
        this.decisions = null;
        this.contributions = null;
        this.mapper = null;
    }

    @Override
    public AgentRunStatus state() { return AgentRunStatus.VERIFYING_FINDINGS; }

    @Override
    public AgentStepResult execute(AgentStepExecutionContext context) {
        if (context.stepType() != state()) {
            throw new IllegalArgumentException("Executor state does not match context");
        }
        if (contexts == null) {
            return AgentStepResult.checkpoint(state());
        }
        var stored = contexts.findByAgentRunId(context.agentRunId())
                .orElseThrow(() -> new AgentStepExecutionException(
                        AgentFailureType.ENVIRONMENT_INCOMPLETE, "Retrieved context checkpoint is missing"
                ));
        stored.requireHead(context.headSha());
        try {
            AgentChangeAnalysisCheckpoint change = mapper.readValue(
                    stored.getChangeSetJson(), AgentChangeAnalysisCheckpoint.class
            );
            AgentRetrievedContextCheckpoint retrieved = mapper.readValue(
                    stored.getRetrievedContextJson(), AgentRetrievedContextCheckpoint.class
            );
            Set<String> changedPaths = change.changeSet().files().stream()
                    .map(file -> file.path()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<FindingCandidate> candidates = new ArrayList<>();
            change.analyses().forEach(analysis -> candidates.addAll(analysis.findingCandidates()));
            if (client.isPresent()) {
                candidates.addAll(modelCandidates(context, stored.getChangedDiff(), change, retrieved, changedPaths));
            }
            List<AgentFindingPipeline.EvaluatedFinding> evaluated = pipeline.evaluate(
                    candidates, changedPaths, context.headSha()
            );
            for (AgentFindingPipeline.EvaluatedFinding item : evaluated) {
                persist(context.agentRunId(), item);
            }
            boolean blocking = evaluated.stream().anyMatch(AgentFindingPipeline.EvaluatedFinding::blocking);
            stored.findingsVerified(
                    mapper.writeValueAsString(evaluated),
                    mapper.writeValueAsString(Map.of(
                            "blocking", blocking,
                            "accepted", evaluated.stream().filter(AgentFindingPipeline.EvaluatedFinding::accepted).count(),
                            "total", evaluated.size()
                    ))
            );
            contexts.save(stored);
            AgentRunStatus next = blocking
                    ? AgentRunStatus.GENERATING_PATCH
                    : AgentRunStatus.PUBLISHING_RESULT;
            return new AgentStepResult(
                    "agent-step-result-v1", state(), AgentStepResult.Disposition.ADVANCE,
                    next, Map.of("findings", evaluated.size(), "blocking", blocking)
            );
        } catch (JsonProcessingException ex) {
            throw new AgentStepExecutionException(
                    AgentFailureType.INTERNAL_ERROR, "Finding verification checkpoint is invalid", ex
            );
        }
    }

    private List<FindingCandidate> modelCandidates(
            AgentStepExecutionContext context,
            String diff,
            AgentChangeAnalysisCheckpoint change,
            AgentRetrievedContextCheckpoint retrieved,
            Set<String> changedPaths
    ) throws JsonProcessingException {
        List<ReviewContextService.ContextEvidence> knowledge = retrieved.evidence().stream()
                .map(item -> new ReviewContextService.ContextEvidence(
                        item.content(), item.citation(), item.sourceName(), item.chunkIndex(),
                        item.sourceVersion(), item.documentType(), item.score(), item.untrusted()
                )).toList();
        // 引用指令按知识是否为空分支(run16 实证,2026-08-09,与终稿计划步的 run13 修法
        // 同构):无知识项目检索证据为空,citation 白名单为空集,再强制"必须引用"即无解
        // 约束——模型只能编造 citation,在 AgentFindingModelService 全数被裁并触发非空
        // 全灭硬错。空知识分支明示 citationIds 必须为空数组,schema 示例同步改空,避免
        // 示例本身教模型编 id;非空分支维持强制引用不变。
        boolean hasKnowledge = !knowledge.isEmpty();
        String citationInstruction = hasKnowledge
                ? prompts.instruction("verifying-findings-citation-required-v1")
                : prompts.instruction("verifying-findings-citation-empty-v1");
        var prompt = prompts.assemble(new AgentPromptAssembler.Input(
                "review-v1",
                // 提示词是第一道防线(run15,2026-08-09:VERIFYING_FINDINGS 首次真实运行即死在
                // 解析层):单对象、键名、severity 合法值、citation 要求前置声明,减少烧在
                // 格式错误上的模型调用。解析与逐条裁剪防线仍在 AgentFindingModelService。
                // r8-R1:指令文本移入 verifying-findings-task-v1 模板,SEVERITY_VALUES(枚举
                // 单源)与 citation 分支片段仍由此处同源注入 %s 槽。
                prompts.instruction("verifying-findings-task-v1", SEVERITY_VALUES, citationInstruction),
                diff,
                "Changed paths: " + changedPaths,
                mapper.writeValueAsString(change.analyses()),
                knowledge,
                "{\"findings\":[{\"severity\":\"HIGH\",\"category\":\"string\","
                        + "\"title\":\"string\",\"description\":\"string\","
                        + "\"filePath\":\"string\",\"lineStart\":1,\"lineEnd\":1,"
                        + "\"symbol\":\"string\",\"ruleId\":\"string\","
                        + "\"citationIds\":[" + (hasKnowledge ? "\"citation\"" : "") + "]}]}",
                "finding-candidates-v1",
                budget(), budget(), budget(), budget()
        ));
        Set<String> citations = new HashSet<>(prompt.citationIds());
        AgentFindingModelService.Result result = findingModel.generate(
                context.agentRunId(), client.orElseThrow(), prompt, citations
        );
        return result.response().findings().stream()
                .map(item -> normalize(item, retrieved, changedPaths, context.headSha()))
                .toList();
    }

    private FindingCandidate normalize(
            FindingModelResponse.ModelFinding model,
            AgentRetrievedContextCheckpoint retrieved,
            Set<String> changedPaths,
            String headSha
    ) {
        List<FindingEvidence> evidence = new ArrayList<>();
        evidence.add(FindingEvidence.create(
                EvidenceType.MODEL, headSha, model.filePath(), model.lineStart(), model.lineEnd(),
                model.description(), 0.2
        ));
        if (model.filePath() != null && changedPaths.contains(model.filePath()) && model.lineStart() != null) {
            evidence.add(FindingEvidence.create(
                    EvidenceType.CODE_LOCATION, headSha, model.filePath(), model.lineStart(), model.lineEnd(),
                    model.title(), 0.8
            ));
        }
        for (String citation : model.citationIds()) {
            retrieved.evidence().stream().filter(item -> item.citation().equals(citation)).findFirst()
                    .ifPresent(item -> evidence.add(FindingEvidence.create(
                            EvidenceType.KNOWLEDGE, headSha, null, null, null, item.content(), item.score()
                    )));
        }
        return new FindingCandidate(
                model.severity(), model.category(), model.title(), model.description(),
                model.filePath(), model.lineStart(), model.lineEnd(), model.symbol(), model.ruleId(),
                headSha, evidence
        );
    }

    private void persist(Long runId, AgentFindingPipeline.EvaluatedFinding item) {
        FindingCandidate candidate = item.deduplicated().candidate();
        Finding entity = findings.saveAndFlush(new Finding(
                runId, candidate.severity(), candidate.category(), candidate.title(), candidate.description(),
                candidate.filePath(), candidate.lineStart(), candidate.lineEnd(), candidate.symbol(), "candidate"
        ));
        entity.applyVerification(
                item.deduplicated().fingerprint(), item.accepted(), item.verified().rejectionReason()
        );
        findings.save(entity);
        candidate.evidence().forEach(value -> evidenceRepository.save(
                FindingEvidenceEntity.from(entity.getId(), value)
        ));
        FindingDecisionEntity decision = decisions.saveAndFlush(
                FindingDecisionEntity.from(entity.getId(), item.decision())
        );
        item.decision().contributions().forEach(value -> contributions.save(
                FindingScoreContributionEntity.from(decision.getId(), value)
        ));
    }

    private AgentPromptAssembler.SectionBudget budget() {
        return new AgentPromptAssembler.SectionBudget(32_768, 8_192);
    }
}
