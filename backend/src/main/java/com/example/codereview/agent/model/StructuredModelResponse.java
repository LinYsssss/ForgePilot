package com.example.codereview.agent.model;

import com.example.codereview.agent.plan.ReviewPlan;
import java.util.List;

public record StructuredModelResponse(
        String summary,
        List<ReviewPlan.PlanItem> plan
) {
}
