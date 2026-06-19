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

    private ReviewPlan.PlanItem item(String toolName, String purpose) {
        return new ReviewPlan.PlanItem(
                toolName,
                mapper.createObjectNode().put("path", "src/Main.java"),
                purpose,
                "line-level evidence"
        );
    }
}
