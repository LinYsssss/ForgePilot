package com.forgepilot.review;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.requirement.RequirementDirectory;
import com.forgepilot.review.ReviewActivityRepository.CurrentReview;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审查活动状态，即 PRD.md 5 中那个只读的派生量。它在每次读取时现算、
 * 任何地方都不存储：既没有 {@code IN_REVIEW} 这个需求状态，
 * 也没有 {@code INVALIDATED} 这个审查状态——因为一份存下来的副本，
 * 必须被每一次 head 推送、每一次 diff 变化、每一次修订发布所失效，
 * 而漏掉的那一次就会变成 UI 无从察觉的谎言。
 *
 * <p>它住在 {@code review} 而非 {@code requirement}，因为它同时需要
 * {@code pull_request} 与 {@code review}，而 ARCHITECTURE.md 1.1 的依赖箭头是
 * {@code review -> requirement}。让 {@code requirement} 反过来问 {@code review}，
 * 会在功能依赖图里闭合出一个环（design.md 2.1）。
 */
@Service
@Transactional(readOnly = true)
public class ReviewActivityService {

    /**
     * PRD.md 5 那张六行映射表，写成数据而不是 if 链，
     * 形态与 {@code RequirementService.ALLOWED_TARGETS} 一致。
     *
     * <p>只列出可达的组合。决策只能写到 COMPLETED 的 Review 上
     * （ARCHITECTURE.md 3.1 前置条件 1），而
     * {@code ck_review_decision_needs_completion} 让另外六种组合根本存不进去，
     * 因此下面若出现查不到的情况，意味着那条 CHECK 没了，
     * 而不是漏想了某种情形。
     *
     * <p>这里的三个 PENDING 是三件不同的事实，靠类型把它们区分开：
     * {@code ReviewStatus.PENDING} 是“已排队、未被抢占”，
     * {@code ReviewDecision.PENDING} 是“尚无人工裁定”，
     * 而 {@code PullRequestActivity.PENDING} 是前两者中第一个所产生的结果。
     * 若去读 decision 而不是 status，那么每一个已经跑完、正等待人工处理的
     * 审查都会被报成 {@code PENDING}，{@code REVIEWING} 则会被整个吞掉。
     */
    private static final Map<ReviewState, PullRequestActivity> ACTIVITY_BY_STATE = Map.of(
            new ReviewState(ReviewStatus.PENDING, ReviewDecision.PENDING),
            PullRequestActivity.PENDING,
            new ReviewState(ReviewStatus.RUNNING, ReviewDecision.PENDING),
            PullRequestActivity.REVIEWING,
            // 已经跑完，但仍在等人处理：PRD.md 5 的 REVIEWING 那一行同样覆盖
            // 这种情况，而它不是 PENDING。
            new ReviewState(ReviewStatus.COMPLETED, ReviewDecision.PENDING),
            PullRequestActivity.REVIEWING,
            new ReviewState(ReviewStatus.COMPLETED, ReviewDecision.APPROVE),
            PullRequestActivity.APPROVED,
            new ReviewState(ReviewStatus.COMPLETED, ReviewDecision.REQUEST_CHANGES),
            PullRequestActivity.CHANGES_REQUESTED,
            new ReviewState(ReviewStatus.FAILED, ReviewDecision.PENDING),
            PullRequestActivity.FAILED);

    private final ReviewActivityRepository activities;
    private final ProjectAccessService access;
    private final RequirementDirectory requirements;

    ReviewActivityService(ReviewActivityRepository activities, ProjectAccessService access,
            RequirementDirectory requirements) {
        this.activities = activities;
        this.access = access;
        this.requirements = requirements;
    }

    /**
     * 单条需求的活动状态。属于其他项目的需求会与一条从未被创建过的需求
     * 答得一模一样，因此无法跨项目探测 id。
     */
    public ActivityView forRequirement(long projectId, long userId, long requirementId) {
        access.requireMember(projectId, userId);
        if (!requirements.existsInProject(projectId, requirementId)) {
            throw ApiException.notFound();
        }
        return ActivityView.of(activities.ofRequirement(projectId, requirementId).stream()
                .map(ReviewActivityService::activityOf)
                .toList());
    }

    /**
     * 项目内的每一条需求，使列表页只取一次活动状态，
     * 而不是每行取一次。
     */
    public Map<Long, ActivityView> forProject(long projectId, long userId) {
        access.requireMember(projectId, userId);
        Map<Long, List<PullRequestActivity>> perRequirement = new LinkedHashMap<>();
        activities.requirementIds(projectId)
                .forEach(requirementId -> perRequirement.put(requirementId, new ArrayList<>()));
        for (CurrentReview row : activities.ofProject(projectId)) {
            if (row.requirementId() != null) {
                // PR 的复合外键已经证明了该需求就在本项目内，
                // 因此这个列表必定存在。
                perRequirement.get(row.requirementId()).add(activityOf(row));
            }
        }
        Map<Long, ActivityView> views = new LinkedHashMap<>();
        perRequirement.forEach((requirementId, subs) -> views.put(requirementId, ActivityView.of(subs)));
        return views;
    }

    /**
     * 单个 PR 的活动状态。返回类型有意用那个六值枚举：
     * {@code NO_PR} 与 {@code MIXED} 是关于**需求**的答案，
     * 在这里不可能有任何含义，因此干脆让它们无法被表达出来。
     */
    static PullRequestActivity activityOf(CurrentReview row) {
        if (!row.hasCurrentReview()) {
            // PRD.md 5 第一行：没有任何东西匹配当前的 head、指纹与修订。
            // 发布新修订或 diff 发生变化时产生的也是这个结果——
            // 而这正是“推导而非存储”的全部意义所在。
            return PullRequestActivity.REVIEW_REQUIRED;
        }
        PullRequestActivity activity = ACTIVITY_BY_STATE.get(new ReviewState(row.status(), row.decision()));
        if (activity == null) {
            throw new IllegalStateException("Review is " + row.status() + " with decision " + row.decision()
                    + ", which ck_review_decision_needs_completion should have made unstorable.");
        }
        return activity;
    }

    /** PRD.md 5 所读取的那两个正交列，合成一个查表键。 */
    private record ReviewState(ReviewStatus status, ReviewDecision decision) {
    }

    /**
     * 一条需求的聚合活动状态，连同各状态的计数。
     *
     * <p>计数永远存在，且永远携带全部六个单 PR 取值，包括为零的项
     * （design.md 2.8）。PRD.md 5 只在聚合结果为 {@code MIXED} 时才要求它们；
     * 而始终发送它们只多花几个字节，却从客户端消除了一整类
     * “键不存在”的分支处理。
     */
    public record ActivityView(RequirementActivity activity, Map<PullRequestActivity, Integer> counts) {

        private static ActivityView of(List<PullRequestActivity> perPullRequest) {
            Map<PullRequestActivity, Integer> counts = new EnumMap<>(PullRequestActivity.class);
            for (PullRequestActivity value : PullRequestActivity.values()) {
                counts.put(value, 0);
            }
            perPullRequest.forEach(activity -> counts.merge(activity, 1, Integer::sum));
            return new ActivityView(RequirementActivity.aggregate(perPullRequest), counts);
        }
    }
}
