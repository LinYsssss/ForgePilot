package com.example.codereview.agent.model;

import com.example.codereview.agent.plan.ReviewPlan;
import java.util.List;

public record StructuredModelResponse(
        String summary,
        List<ReviewPlan.PlanItem> plan,
        List<CitedClaim> claims
) {
    public StructuredModelResponse(String summary, List<ReviewPlan.PlanItem> plan) {
        this(summary, plan, List.of());
    }

    public StructuredModelResponse {
        plan = plan == null ? List.of() : List.copyOf(plan);
        claims = claims == null ? List.of() : List.copyOf(claims);
    }

    public record CitedClaim(String text, boolean knowledgeBacked, List<String> citationIds) {
        public CitedClaim {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
        }
    }
}
