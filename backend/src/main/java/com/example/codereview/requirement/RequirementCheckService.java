package com.example.codereview.requirement;

import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.context.ContextBuilder;
import com.example.codereview.context.ContextScene;
import com.example.codereview.member.ProjectRole;
import com.example.codereview.requirement.RequirementCheckDtos.CheckDimension;
import com.example.codereview.requirement.RequirementCheckDtos.CheckItem;
import com.example.codereview.requirement.RequirementCheckDtos.CheckReportResponse;
import com.example.codereview.requirement.RequirementCheckDtos.DimensionReport;
import com.example.codereview.requirement.RequirementCheckDtos.LlmCheckResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 需求体检流水线(P2,父 design §6):确定性规则层 → 知识检索(ContextBuilder,
 * REQUIREMENT_CHECK 场景)→ LLM 结构化分析(schema 校验不过整体拒绝)→ 报告落库。
 * 触发是手动按钮(LEADER/DEVELOPER),不做保存自动触发——控制 token 成本。
 */
@Service
public class RequirementCheckService {

    private final RequirementRepository requirements;
    private final AcceptanceCriterionRepository criteria;
    private final RequirementQualityReportRepository reports;
    private final RequirementRuleChecker ruleChecker;
    private final RequirementCheckClient checkClient;
    private final ContextBuilder contextBuilder;
    private final ProjectAuthorization projectAuthorization;
    private final AiCallLogService aiCallLogService;
    private final ObjectMapper objectMapper;
    private final String chatModel;

    public RequirementCheckService(RequirementRepository requirements,
                                   AcceptanceCriterionRepository criteria,
                                   RequirementQualityReportRepository reports,
                                   RequirementRuleChecker ruleChecker,
                                   RequirementCheckClient checkClient,
                                   ContextBuilder contextBuilder,
                                   ProjectAuthorization projectAuthorization,
                                   AiCallLogService aiCallLogService,
                                   ObjectMapper objectMapper,
                                   @Value("${app.ai.chat-model}") String chatModel) {
        this.requirements = requirements;
        this.criteria = criteria;
        this.reports = reports;
        this.ruleChecker = ruleChecker;
        this.checkClient = checkClient;
        this.contextBuilder = contextBuilder;
        this.projectAuthorization = projectAuthorization;
        this.aiCallLogService = aiCallLogService;
        this.objectMapper = objectMapper;
        this.chatModel = chatModel;
    }

    @Transactional
    public CheckReportResponse check(Long projectId, Long userId, Long requirementId) {
        projectAuthorization.requireRole(projectId, userId, Set.of(ProjectRole.LEADER, ProjectRole.DEVELOPER));
        RequirementEntity requirement = requirements.findByIdAndProjectId(requirementId, projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUIREMENT_NOT_FOUND));
        List<AcceptanceCriterionEntity> acs = criteria.findByRequirementIdOrderBySeqAsc(requirementId);

        // 1) 规则层(零 token)
        List<RequirementRuleChecker.RuleFinding> ruleFindings = ruleChecker.check(requirement, acs);

        // 2) 知识检索(场景化统一入口)
        ContextBuilder.ContextBundle bundle = contextBuilder.build(
                ContextScene.REQUIREMENT_CHECK, projectId,
                new ContextBuilder.Refs(requirement.getTitle() + "\n" + safe(requirement.getDescription())));
        String knowledgeBlock = renderKnowledge(bundle);

        // 3) LLM 层(mock 与真模型同一解析/校验路径;失败原样抛出,规则层结果不落库——
        //    半份报告比没有报告更误导)
        String requirementBlock = renderRequirement(requirement, acs);
        long start = System.nanoTime();
        LlmCheckResult llm;
        try {
            llm = checkClient.analyze(requirementBlock, knowledgeBlock);
        } catch (RuntimeException ex) {
            aiCallLogService.requirementCheckFailed(projectId,
                    requirementBlock.length() + knowledgeBlock.length(), elapsedMs(start), ex.getMessage());
            throw ex;
        }
        aiCallLogService.requirementCheckSuccess(projectId,
                requirementBlock.length() + knowledgeBlock.length(),
                llm.rawResponse() == null ? 0 : llm.rawResponse().length(),
                llm.totalTokens(), elapsedMs(start));

        // 4) 合并(规则层条目插到对应维度前部)并落库
        List<DimensionReport> merged = merge(ruleFindings, llm.dimensions());
        String reportJson = writeJson(merged);
        int round = reports.maxRound(requirementId) + 1;
        RequirementQualityReportEntity saved = reports.save(new RequirementQualityReportEntity(
                requirementId, round, reportJson, chatModel, llm.totalTokens()));
        return toResponse(saved, merged);
    }

    public List<CheckReportResponse> listReports(Long projectId, Long userId, Long requirementId) {
        projectAuthorization.requireRead(projectId, userId);
        requirements.findByIdAndProjectId(requirementId, projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REQUIREMENT_NOT_FOUND));
        return reports.findByRequirementIdOrderByRoundDesc(requirementId).stream()
                .map(entity -> toResponse(entity, readJson(entity.getReportJson())))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private List<DimensionReport> merge(List<RequirementRuleChecker.RuleFinding> ruleFindings,
                                        List<DimensionReport> llmReports) {
        Map<CheckDimension, List<CheckItem>> byDimension = new EnumMap<>(CheckDimension.class);
        for (CheckDimension dimension : CheckDimension.values()) {
            byDimension.put(dimension, new ArrayList<>());
        }
        for (RequirementRuleChecker.RuleFinding finding : ruleFindings) {
            byDimension.get(finding.dimension()).add(finding.item());
        }
        for (DimensionReport report : llmReports) {
            CheckDimension dimension = CheckDimension.valueOf(report.dimension());
            byDimension.get(dimension).addAll(report.items());
        }
        List<DimensionReport> merged = new ArrayList<>();
        for (CheckDimension dimension : CheckDimension.values()) {
            merged.add(new DimensionReport(dimension.name(), List.copyOf(byDimension.get(dimension))));
        }
        return List.copyOf(merged);
    }

    private String renderRequirement(RequirementEntity requirement, List<AcceptanceCriterionEntity> acs) {
        StringBuilder sb = new StringBuilder();
        sb.append("标题: ").append(requirement.getTitle()).append('\n');
        sb.append("优先级: ").append(requirement.getPriority()).append('\n');
        sb.append("背景: ").append(safe(requirement.getBackground())).append('\n');
        sb.append("描述: ").append(safe(requirement.getDescription())).append('\n');
        sb.append("验收标准:");
        if (acs.isEmpty()) {
            sb.append(" (无)");
        }
        for (AcceptanceCriterionEntity ac : acs) {
            sb.append("\nAC").append(ac.getSeq()).append(": ").append(ac.getText());
        }
        return sb.toString();
    }

    private String renderKnowledge(ContextBuilder.ContextBundle bundle) {
        if (bundle.knowledgeSnippets().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContextBuilder.KnowledgeSnippet snippet : bundle.knowledgeSnippets()) {
            sb.append("[").append(snippet.sourceName()).append("]\n").append(snippet.content()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private CheckReportResponse toResponse(RequirementQualityReportEntity entity, List<DimensionReport> dimensions) {
        return new CheckReportResponse(entity.getId(), entity.getRound(), entity.getModel(),
                entity.getTotalTokens(), entity.getCreatedAt(), dimensions);
    }

    private String writeJson(List<DimensionReport> merged) {
        try {
            return objectMapper.writeValueAsString(merged);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "体检报告序列化失败");
        }
    }

    private List<DimensionReport> readJson(String reportJson) {
        try {
            return objectMapper.readValue(reportJson, new TypeReference<List<DimensionReport>>() {
            });
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "体检报告反序列化失败");
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "(无)" : value;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
