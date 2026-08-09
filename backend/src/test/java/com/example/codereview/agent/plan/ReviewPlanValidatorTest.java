package com.example.codereview.agent.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.codereview.agent.tool.AgentToolRegistry;
import com.example.codereview.agent.tool.ToolRiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewPlanValidatorTest {

    private final AgentToolRegistry tools = mock(AgentToolRegistry.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private ReviewPlanValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReviewPlanValidator(tools, mapper, Map.of("read_diff", 2, "apply_patch", 1), 256);
        when(tools.contains("read_diff")).thenReturn(true);
        when(tools.contains("apply_patch")).thenReturn(true);
        when(tools.riskLevel("read_diff")).thenReturn(ToolRiskLevel.READ_ONLY);
        when(tools.riskLevel("apply_patch")).thenReturn(ToolRiskLevel.WRITE_REQUIRES_APPROVAL);
    }

    @Test
    void acceptsOrderedReadOnlyPlan() {
        var result = validator.validate(List.of(
                item("read_diff", "inspect changed code"),
                item("read_diff", "collect evidence")
        ), false);

        assertThat(result.valid()).isTrue();
        assertThat(result.validatedItems()).extracting(ReviewPlan.PlanItem::purpose)
                .containsExactly("inspect changed code", "collect evidence");
    }

    @Test
    void rejectsEmptyUnknownAndOverRepeatedPlans() {
        assertThat(validator.validate(List.of(), false).errors()).contains("plan must not be empty");
        assertThat(validator.validate(List.of(item("missing", "unknown")), false).errors())
                .anyMatch(error -> error.contains("unknown tool"));
        assertThat(validator.validate(List.of(
                item("read_diff", "one"),
                item("read_diff", "two"),
                item("read_diff", "three")
        ), false).errors()).anyMatch(error -> error.contains("limit"));
    }

    @Test
    void rejectsWriteToolBeforeApprovalAndOversizedArguments() {
        assertThat(validator.validate(List.of(item("apply_patch", "write")), false).errors())
                .anyMatch(error -> error.contains("approval"));

        var oversized = new ReviewPlan.PlanItem(
                "read_diff",
                mapper.createObjectNode().put("path", "x".repeat(300)),
                "inspect",
                "diff evidence"
        );
        assertThat(validator.validate(List.of(oversized), true).errors())
                .anyMatch(error -> error.contains("arguments"));
    }

    @Test
    void rejectsToolsOutsideCurrentStatePluginAuthorizationAndBudget() {
        var policy = new ReviewPlanValidator.PlanPolicy(
                java.util.Set.of("read_diff"),
                java.util.Set.of("java"),
                java.util.Set.of("read_diff"),
                1
        );

        assertThat(validator.validate(List.of(item("apply_patch", "write")), true, policy).errors())
                .anyMatch(error -> error.contains("current state"));

        assertThat(validator.validate(List.of(
                item("read_diff", "one"),
                item("read_diff", "two")
        ), false, policy).errors()).anyMatch(error -> error.contains("remaining budget"));
    }

    // run12 实证:提示词声明上限后模型违规项 2→1 但仍越限,数值上限属基础设施约束,
    // 服务端确定性裁剪兜底——内在合法但超预算的条目被丢弃而非整计划报错。
    @Test
    void clampDropsOverRepeatedItemsInsteadOfFailing() {
        registerDefaultLimitTool("code_search");
        var result = validator.validate(List.of(
                item("code_search", "one"),
                item("code_search", "two"),
                item("code_search", "three"),
                item("code_search", "four")
        ), false, clampPolicy(8));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.validatedItems()).extracting(ReviewPlan.PlanItem::purpose)
                .containsExactly("one", "two", "three");
    }

    @Test
    void clampDropsItemsBeyondTotalBudgetWithoutConsumingIt() {
        registerDefaultLimitTool("tool_a");
        registerDefaultLimitTool("tool_b");
        registerDefaultLimitTool("tool_c");
        var items = new java.util.ArrayList<ReviewPlan.PlanItem>();
        for (String tool : List.of("tool_a", "tool_b", "tool_c")) {
            for (int i = 1; i <= 3; i++) {
                items.add(item(tool, tool + "-" + i));
            }
        }

        var result = validator.validate(items, false, clampPolicy(8));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.validatedItems()).hasSize(8);
    }

    @Test
    void clampToEmptyPlanIsStillAnError() {
        var result = validator.validate(List.of(item("read_diff", "one")), false, clampPolicy(0));

        assertThat(result.valid()).isFalse();
        assertThat(result.validatedItems()).isEmpty();
        assertThat(result.errors()).contains("plan must not be empty after clamping");
    }

    @Test
    void withoutClampOverBudgetStillFailsHard() {
        registerDefaultLimitTool("code_search");
        var result = validator.validate(List.of(
                item("code_search", "one"),
                item("code_search", "two"),
                item("code_search", "three"),
                item("code_search", "four")
        ), false, new ReviewPlanValidator.PlanPolicy(
                java.util.Set.of("*"), java.util.Set.of(), java.util.Set.of("*"), 8, false, false
        ));

        assertThat(result.valid()).isFalse();
        assertThat(result.validatedItems()).isEmpty();
        assertThat(result.errors()).anyMatch(error -> error.contains("tool repetition exceeds limit"));
    }

    private ReviewPlanValidator.PlanPolicy clampPolicy(int remainingToolCalls) {
        return new ReviewPlanValidator.PlanPolicy(
                java.util.Set.of("*"), java.util.Set.of(), java.util.Set.of("*"),
                remainingToolCalls, false, true
        );
    }

    private void registerDefaultLimitTool(String toolName) {
        when(tools.contains(toolName)).thenReturn(true);
        when(tools.riskLevel(toolName)).thenReturn(ToolRiskLevel.READ_ONLY);
    }

    private ReviewPlan.PlanItem item(String toolName, String purpose) {
        return new ReviewPlan.PlanItem(
                toolName,
                mapper.createObjectNode().put("path", "src/Main.java"),
                purpose,
                "line-level evidence"
        );
    }
}
