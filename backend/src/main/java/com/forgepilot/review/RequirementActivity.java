package com.forgepilot.review;

import java.util.List;

/**
 * <strong>单条需求</strong>的派生审查活动状态，由它关联的各个 PR 聚合而来
 * （PRD.md 5）。
 *
 * <p>八个取值：单 PR 层面的六个，加上只在这一层存在的 {@link #NO_PR}
 * 与 {@link #MIXED}。从不存储——PRD.md 明确规定需求状态与审查活动并列展示，
 * 绝不合并。
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
     * 风险状态按此顺序优先，其次是「全体一致」，最后才是 MIXED（PRD.md 5）。
     * {@code MIXED} 是残余项而非优先项：一条需求若其 PR 分别是
     * CHANGES_REQUESTED 与 APPROVED，报告的是 {@code CHANGES_REQUESTED}，
     * 因为风险状态在「是否一致」被考虑之前就已经胜出。
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
