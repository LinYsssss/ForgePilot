package com.forgepilot.review;

import java.util.List;

/**
 * The derived review activity of <strong>one requirement</strong>, aggregated
 * over its associated pull requests (PRD.md 5).
 *
 * <p>Eight values: the six single-pull-request ones plus {@link #NO_PR} and
 * {@link #MIXED}, which exist only at this level. Never stored — PRD.md is
 * explicit that requirement status and review activity are shown side by side
 * and never merged.
 */
public enum RequirementActivity {

    NO_PR,
    REVIEW_REQUIRED,
    FAILED,
    CHANGES_REQUESTED,
    REVIEWING,
    PENDING,
    APPROVED,
    MIXED;

    /**
     * Risk states take precedence in this order, then unanimity, then MIXED
     * (PRD.md 5). {@code MIXED} is the residue, not a priority: a requirement whose
     * pull requests are CHANGES_REQUESTED and APPROVED reports
     * {@code CHANGES_REQUESTED}, because the risk state wins before unanimity is
     * ever considered.
     */
    private static final List<PullRequestActivity> RISK_ORDER =
            List.of(PullRequestActivity.FAILED, PullRequestActivity.CHANGES_REQUESTED);

    public static RequirementActivity aggregate(List<PullRequestActivity> perPullRequest) {
        if (perPullRequest.isEmpty()) {
            return NO_PR;
        }
        for (PullRequestActivity risk : RISK_ORDER) {
            if (perPullRequest.contains(risk)) {
                return valueOf(risk.name());
            }
        }
        PullRequestActivity first = perPullRequest.getFirst();
        return perPullRequest.stream().allMatch(first::equals) ? valueOf(first.name()) : MIXED;
    }
}
