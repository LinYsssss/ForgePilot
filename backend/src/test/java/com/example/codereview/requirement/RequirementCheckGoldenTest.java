package com.example.codereview.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.agent.prompt.PromptTemplateRegistry;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.requirement.RequirementCheckDtos.CheckDimension;
import com.example.codereview.requirement.RequirementCheckDtos.DimensionReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * P2 体检的模板 golden(A2)与输出 schema 校验(A3)、规则层行为(A1 的规则半边)。
 * 模板经注册表 + instruction() 组装:枚举由调用方同源注入,组装后不得残留 %s 槽。
 */
class RequirementCheckGoldenTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentPromptAssembler assembler = new AgentPromptAssembler(new PromptTemplateRegistry());

    @Test
    void templateAssemblesWithInjectedEnumsAndNoResidualSlots() {
        String dimensions = Arrays.stream(CheckDimension.values())
                .map(Enum::name).collect(Collectors.joining(" / "));
        String prompt = assembler.instruction("requirement-check-v1",
                dimensions, "HIGH / MEDIUM / LOW", "标题: 演示需求", "(无)");
        for (CheckDimension dimension : CheckDimension.values()) {
            assertThat(prompt).contains(dimension.name());
        }
        assertThat(prompt).contains("标题: 演示需求");
        assertThat(prompt).doesNotContain("%s");
        // 模板禁写死枚举:正文里的维度名必须全部来自注入(把注入串移除后不应再出现任何维度名)
        String withoutInjected = prompt.replace(dimensions, "");
        for (CheckDimension dimension : CheckDimension.values()) {
            assertThat(withoutInjected).doesNotContain(dimension.name());
        }
    }

    @Test
    void parserAcceptsValidOutputAndBackfillsMissingDimensions() {
        String content = """
                {"dimensions":[{"dimension":"EXCEPTION_COVERAGE","items":[
                  {"severity":"HIGH","message":"未覆盖超时场景","suggestion":"补充超时 AC"}]}]}
                """;
        List<DimensionReport> reports = RequirementCheckParser.parse(objectMapper, content);
        assertThat(reports).hasSize(CheckDimension.values().length);
        DimensionReport exception = reports.stream()
                .filter(r -> r.dimension().equals("EXCEPTION_COVERAGE")).findFirst().orElseThrow();
        assertThat(exception.items()).hasSize(1);
        assertThat(exception.items().get(0).source()).isEqualTo("LLM");
    }

    @Test
    void parserRejectsUnknownDimensionAndSeverityOutright() {
        String unknownDimension = "{\"dimensions\":[{\"dimension\":\"MADE_UP\",\"items\":[]}]}";
        assertThatThrownBy(() -> RequirementCheckParser.parse(objectMapper, unknownDimension))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AI_RESPONSE_INVALID);

        String unknownSeverity = """
                {"dimensions":[{"dimension":"RISK","items":[
                  {"severity":"CRITICAL","message":"x","suggestion":"y"}]}]}
                """;
        assertThatThrownBy(() -> RequirementCheckParser.parse(objectMapper, unknownSeverity))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
    }

    @Test
    void ruleLayerFlagsMissingAcsAndVagueWording() {
        RequirementRuleChecker checker = new RequirementRuleChecker();
        RequirementEntity bare = new RequirementEntity(1L, 1L, "订单取消库存释放", null, null, "HIGH", 9L);
        List<RequirementRuleChecker.RuleFinding> findings = checker.check(bare, List.of());
        assertThat(findings).anyMatch(f ->
                f.dimension() == CheckDimension.TESTABILITY && f.item().severity().equals("HIGH"));
        assertThat(findings).anyMatch(f -> f.dimension() == CheckDimension.COMPLETENESS);

        List<AcceptanceCriterionEntity> vague = List.of(
                new AcceptanceCriterionEntity(1L, 1, "取消后尽量快地回补库存"),
                new AcceptanceCriterionEntity(1L, 2, "取消后 5 分钟内库存回补，重复取消不重复回补"));
        List<RequirementRuleChecker.RuleFinding> vagueFindings = checker.check(bare, vague);
        assertThat(vagueFindings).anyMatch(f ->
                f.dimension() == CheckDimension.CLARITY && f.item().message().contains("AC1"));
        assertThat(vagueFindings).noneMatch(f ->
                f.dimension() == CheckDimension.CLARITY && f.item().message().contains("AC2"));
    }
}
