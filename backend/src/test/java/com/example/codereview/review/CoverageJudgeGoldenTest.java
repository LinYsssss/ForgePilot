package com.example.codereview.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.prompt.PromptTemplateRegistry;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.review.CoverageDtos.AcCoverage;
import com.example.codereview.review.CoverageDtos.AcRef;
import com.example.codereview.review.CoverageDtos.CoverageInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P4a 覆盖判定的模板 golden、schema 校验(未知 acId/verdict 拒绝、漏判补 NOT_FOUND)、
 * 证据引用校验(伪造引用丢弃 + COVERED 降级)与五臂 flags 语义(A1/A2)。
 */
class CoverageJudgeGoldenTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final CoverageInput INPUT = new CoverageInput(1L, "REQ-1", "订单取消库存释放",
            "取消未支付订单时释放占用库存",
            List.of(new AcRef("AC1", "取消后 5 分钟内库存回补"), new AcRef("AC2", "重复取消不重复回补")));

    @Test
    void templateAssemblesWithoutResidualSlots() {
        AgentPromptAssembler assembler = new AgentPromptAssembler(new PromptTemplateRegistry());
        String prompt = assembler.instruction("coverage-judge-v1",
                "COVERED / NOT_FOUND / AT_RISK", "REQ-1 订单取消库存释放", "AC1: 取消后回补",
                "分片摘要", "diff --git a/A.java b/A.java");
        assertThat(prompt).contains("COVERED / NOT_FOUND / AT_RISK");
        assertThat(prompt).contains("REQ-1 订单取消库存释放");
        assertThat(prompt).doesNotContain("%s");
    }

    @Test
    void parserBackfillsMissingAcAsNotFound() {
        String content = """
                {"coverage":[{"acId":"AC1","verdict":"COVERED",
                  "evidence":[{"filePath":"src/A.java","note":"释放逻辑"}],"rationale":"实现了释放"}]}
                """;
        List<AcCoverage> coverage = CoverageJudgeParser.parse(objectMapper, INPUT, content);
        assertThat(coverage).hasSize(2);
        assertThat(coverage.get(0).verdict()).isEqualTo("COVERED");
        assertThat(coverage.get(1).acId()).isEqualTo("AC2");
        assertThat(coverage.get(1).verdict()).isEqualTo("NOT_FOUND");
        assertThat(coverage.get(1).rationale()).contains("未对该 AC 给出结论");
    }

    @Test
    void parserRejectsUnknownAcIdAndVerdict() {
        assertThatThrownBy(() -> CoverageJudgeParser.parse(objectMapper, INPUT,
                "{\"coverage\":[{\"acId\":\"AC99\",\"verdict\":\"COVERED\"}]}"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
        assertThatThrownBy(() -> CoverageJudgeParser.parse(objectMapper, INPUT,
                "{\"coverage\":[{\"acId\":\"AC1\",\"verdict\":\"MAYBE\"}]}"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
    }

    @Test
    void fabricatedEvidenceIsDroppedAndCoveredDowngradesToAtRisk() {
        String content = """
                {"coverage":[
                  {"acId":"AC1","verdict":"COVERED",
                   "evidence":[{"filePath":"src/NotInDiff.java","note":"编造"}],"rationale":"看似覆盖"},
                  {"acId":"AC2","verdict":"COVERED",
                   "evidence":[{"filePath":"src/Real.java","note":"真实"},
                               {"filePath":"src/Fake.java","note":"编造"}],"rationale":"部分真实"}]}
                """;
        List<AcCoverage> parsed = CoverageJudgeParser.parse(objectMapper, INPUT, content);
        String diff = "diff --git a/src/Real.java b/src/Real.java\n+++ b/src/Real.java\n+code";
        List<AcCoverage> verified = CoverageJudgeParser.verifyEvidence(parsed, diff);
        // AC1:全部证据被丢 → COVERED 降级 AT_RISK 并标注
        assertThat(verified.get(0).verdict()).isEqualTo("AT_RISK");
        assertThat(verified.get(0).evidence()).isEmpty();
        assertThat(verified.get(0).rationale()).contains("降级");
        // AC2:保留真实证据,丢弃编造证据,结论不变
        assertThat(verified.get(1).verdict()).isEqualTo("COVERED");
        assertThat(verified.get(1).evidence()).hasSize(1);
        assertThat(verified.get(1).evidence().get(0).filePath()).isEqualTo("src/Real.java");
    }

    @Test
    void fiveArmFlagCombinationsParseCorrectly() {
        ObjectMapper mapper = new ObjectMapper();
        // 缺省 = 生产默认全开(引入 flags 前的行为)
        assertThat(ReviewFeatureFlags.parse(null, mapper))
                .isEqualTo(new ReviewFeatureFlags(true, true, true));
        // Baseline:纯 diff
        assertThat(ReviewFeatureFlags.parse(
                "{\"knowledge\":false,\"requirementContext\":false,\"evidenceVerification\":false}", mapper))
                .isEqualTo(new ReviewFeatureFlags(false, false, false));
        // A:+knowledge
        assertThat(ReviewFeatureFlags.parse(
                "{\"knowledge\":true,\"requirementContext\":false,\"evidenceVerification\":false}", mapper))
                .isEqualTo(new ReviewFeatureFlags(true, false, false));
        // B:+requirement/ac
        assertThat(ReviewFeatureFlags.parse(
                "{\"knowledge\":false,\"requirementContext\":true,\"evidenceVerification\":false}", mapper))
                .isEqualTo(new ReviewFeatureFlags(false, true, false));
        // C=A+B、D=C+verify
        ReviewFeatureFlags armC = new ReviewFeatureFlags(true, true, false);
        assertThat(ReviewFeatureFlags.parse(armC.toJson(), mapper)).isEqualTo(armC);
        ReviewFeatureFlags armD = new ReviewFeatureFlags(true, true, true);
        assertThat(ReviewFeatureFlags.parse(armD.toJson(), mapper)).isEqualTo(armD);
        // 解析失败兜底为生产默认
        assertThat(ReviewFeatureFlags.parse("not-json", mapper))
                .isEqualTo(ReviewFeatureFlags.productionDefaults());
    }
}
