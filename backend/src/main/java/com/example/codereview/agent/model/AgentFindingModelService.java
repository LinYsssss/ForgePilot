package com.example.codereview.agent.model;

import com.example.codereview.agent.error.AgentFailureType;
import com.example.codereview.agent.queue.AgentStepExecutionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentFindingModelService {

    private static final Logger log = LoggerFactory.getLogger(AgentFindingModelService.class);

    private final ObjectMapper mapper;
    private final AgentModelCallAuditService audit;
    private final int maxOutputBytes;

    public AgentFindingModelService(
            ObjectMapper mapper,
            AgentModelCallAuditService audit,
            @Value("${app.agent.model.max-output-bytes:65536}") int maxOutputBytes
    ) {
        // FAIL_ON_UNKNOWN_PROPERTIES 是契约防线,保持硬拒;枚举大小写另当别论——
        // "high" 与 "HIGH" 语义无歧义,为大小写差异烧掉一次模型调用不值(run15 加固)。
        this.mapper = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
        this.audit = audit;
        this.maxOutputBytes = maxOutputBytes;
    }

    public Result generate(
            Long agentRunId,
            AgentModelClient client,
            PromptEnvelope prompt,
            Set<String> allowedCitations
    ) {
        AgentModelClient.ModelResponse response = client.generate(prompt);
        AgentModelCall call = audit.save(new AgentModelCall(agentRunId, response, prompt, "FINDINGS"));
        try {
            if (response.content().getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
                throw invalid("finding model output exceeds byte limit");
            }
            FindingModelResponse parsed = mapper.readValue(
                    ModelJsonOutputs.unwrapMarkdown(response.content()), FindingModelResponse.class
            );
            FindingModelResponse validated = clampInvalidFindings(parsed, allowedCitations);
            call.complete();
            audit.save(call);
            return new Result(validated, response.inputTokens(), response.outputTokens(), response.latencyMs());
        } catch (JsonProcessingException ex) {
            // 审计表按设计不存原始载荷,错误信息就是唯一的现场——首次真实运行即暴露
            // (run15,2026-08-09):只有一句 schema-invalid,连"围栏包裹还是缺字段"都分不出来。
            // 追加解析器定界原因,截断上限沿用 ModelOutputValidator.limit 的手法。
            String detail = limit(ex.getOriginalMessage());
            call.fail("invalid finding model JSON: " + detail);
            audit.save(call);
            throw invalid("finding model output is not schema-valid JSON: " + detail);
        } catch (AgentStepExecutionException ex) {
            call.fail(ex.getMessage());
            audit.save(call);
            throw ex;
        }
    }

    /**
     * 引用校验从"任一条目非法则整步硬失败"改为逐条裁剪,镜像
     * {@link com.example.codereview.agent.plan.ReviewPlanValidator} clampOverBudget 的语义
     * (run12 实证确立的模式):个别残缺/越权条目不该拖着同批合法发现一起死
     * (run15 整步 INVALID_MODEL_OUTPUT 且无重试,正是该硬失败的代价)。
     */
    private FindingModelResponse clampInvalidFindings(
            FindingModelResponse response,
            Set<String> allowedCitations
    ) {
        List<FindingModelResponse.ModelFinding> findings = response.findings();
        if (findings.isEmpty()) {
            // 原始就是空列表:零发现是合法的干净结果,照旧放行。
            return response;
        }
        Set<String> allowed = Set.copyOf(allowedCitations);
        List<FindingModelResponse.ModelFinding> survivors = new ArrayList<>();
        for (int index = 0; index < findings.size(); index++) {
            String reason = rejectionReason(findings.get(index), allowed);
            if (reason == null) {
                survivors.add(findings.get(index));
                continue;
            }
            // no silent drops:裁剪必须留痕,WARN 是该决策唯一的可审计出口。
            log.warn("Dropped invalid model finding [{}] reason={}", index, reason);
        }
        if (survivors.isEmpty()) {
            // 非空列表被全数丢弃仍是硬错误:全垃圾输出不得伪装成"零发现的干净结果"。
            throw invalid("all " + findings.size() + " model findings were dropped as invalid");
        }
        return survivors.size() == findings.size() ? response : new FindingModelResponse(survivors);
    }

    private String rejectionReason(FindingModelResponse.ModelFinding finding, Set<String> allowed) {
        if (finding.severity() == null || blank(finding.category()) || blank(finding.title())
                || blank(finding.description())) {
            return "required fields are missing";
        }
        if (finding.citationIds().isEmpty()) {
            // 强制引用按白名单是否为空分档(run16 实证,2026-08-09):e2e 项目从未入库知识
            // 文档,检索证据为空——这是合法部署姿态,却让 citation 白名单成为空集,而原规则
            // 仍要求每条 finding ≥1 个白名单引用。空白名单 + 强制引用 = 无解约束:模型
            // 只能编造 citation,全部被裁,触发下方"非空全灭"硬错,该步在无知识项目上
            // 永远失败。分档后:白名单为空 → 零引用存活,质量门由 AgentFindingPipeline
            // 的 diff 锚定证据分承担;白名单非空 → 维持强制引用不变。知识入库/检索姿态
            // 归 r5/r8 批次收敛,生产级强制引用的全面回归属彼处议题。
            return allowed.isEmpty() ? null : "requires at least one supplied citation";
        }
        Set<String> unique = new HashSet<>();
        for (String citation : finding.citationIds()) {
            // 白名单为空时任何非空 citation 也必命中此分支:无知识可引却带引用,只能是
            // 凭空捏造,照旧丢弃。
            if (citation == null || citation.isBlank() || !allowed.contains(citation)) {
                return "unknown citation";
            }
            if (!unique.add(citation)) {
                return "duplicate citation";
            }
        }
        return null;
    }

    private AgentStepExecutionException invalid(String message) {
        return new AgentStepExecutionException(AgentFailureType.INVALID_MODEL_OUTPUT, message);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String limit(String detail) {
        if (detail == null || detail.isBlank()) {
            return "no parser detail";
        }
        return detail.length() <= 2_000 ? detail : detail.substring(0, 2_000);
    }

    public record Result(FindingModelResponse response, long inputTokens, long outputTokens, long latencyMs) {
    }
}
