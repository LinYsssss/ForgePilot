package com.forgepilot.review;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * API.md 中审查与 Finding 相关端点的响应体。
 *
 * <p>它们放在同一个文件里，是因为它们本就是同一份契约；每一个都是没有行为的
 * 朴素 record，因此这里不决定任何事情。
 *
 * <p>其中两处形态是刻意的，而非顺手为之：
 *
 * <ul>
 * <li>{@code status} 与 {@code continuity} 是 {@link FindingView} 中两个**分开的**
 * 分量。PRD.md 5 禁止把它们合并成一个字段或一个 UI 标签——一个说的是人做了
 * 什么判断，另一个说的是这条问题从哪来——因此这里没有任何视图可以把它们压平。</li>
 * <li>{@code isCurrent} 是一个计算分量，不是数据库的列。ARCHITECTURE.md 3.5
 * 拒绝设置 {@code INVALIDATED} 状态：一次 Review 是否仍然适用，
 * 是在每一次读取时把它的身份与该 PR 的当前值比对后推导出来的。</li>
 * </ul>
 */
final class ReviewViews {

    private ReviewViews() {
    }

    /** 一次性决策所产生的三个事实。 */
    record DecisionResult(ReviewDecision decision, Long decisionBy, Instant decisionAt) {
    }

    /**
     * 项目级审查列表中的一行。它携带 PR 信息，因为这份列表是跨 PR 的，
     * 而按 PR 展开的历史列表则不必重复说明这一点。
     */
    record ProjectReviewRow(
            long id,
            long pullRequestId,
            int pullRequestNumber,
            String headSha,
            Long requirementId,
            ReviewStatus status,
            ReviewDecision decision,
            boolean isCurrent,
            Instant createdAt) {
    }

    /** 某个 PR 审查历史中的一行；旧行全部保留。 */
    record ReviewSummary(
            long id,
            String headSha,
            Long requirementRevisionId,
            ReviewStatus status,
            ReviewDecision decision,
            boolean isCurrent,
            Instant createdAt) {
    }

    /**
     * 一次审查的详情。
     *
     * <p>{@code coverage} 与 {@code acVerdicts} 是从 Review 自己的
     * {@code summary_json} 里原样透传的，而不是在这里重新塑形：那个结构归引擎
     * 切片所有，另造一个只会让页面描述出 Review 从未说过的东西。
     * 因此「字段为 null」与「空数组」是两个不同的答案，
     * 这正是对 {@code notReviewed} 的要求——
     * 未被审查的文件绝不能被静默丢弃。
     */
    record ReviewDetail(
            long id,
            long pullRequestId,
            String headSha,
            String reviewInputFingerprint,
            Long requirementId,
            Long requirementRevisionId,
            ReviewStatus status,
            ReviewDecision decision,
            Long decisionBy,
            Instant decisionAt,
            String decisionComment,
            boolean isCurrent,
            JsonNode contextSnapshot,
            JsonNode coverage,
            JsonNode acVerdicts,
            List<FindingView> findings,
            String engine,
            String promptVersion,
            String model,
            int executionAttempt) {
    }

    /**
     * 一条 Finding 的视图。之所以带 {@code acKey} 而不只是 {@code acId}，
     * 是因为 ARCHITECTURE.md 3.6 把 {@code ac_key} 定为跨修订稳定的业务身份，
     * 而行 id 绝不能拿来顶替它。
     */
    record FindingView(
            long id,
            FindingType findingType,
            String path,
            Integer line,
            String evidence,
            FindingCategory category,
            String explanation,
            String suggestion,
            FindingConfidence confidence,
            FindingStatus status,
            FindingContinuity continuity,
            Long requirementId,
            Long requirementRevisionId,
            Long acId,
            String acKey,
            Long assigneeId,
            Long carriedFromFindingId,
            String findingKey,
            String evidenceHash,
            String basisHash) {
    }

    /** 一次 Finding 状态流转的结果。 */
    record FindingStatusResult(FindingStatus status) {
    }

    /**
     * 一个二项比例的 95% 置信区间。
     *
     * <p>它作为 {@code null} 出现时表示<strong>样本量为 0</strong>，即没有区间可言。
     * 这与 {@code [0, 1]} 是两件事：后者说的是「测过了，但什么都没测出来」。
     */
    record Interval(double low, double high) {
    }

    /**
     * 需求覆盖度里的一行：当前修订的一条验收条件。
     *
     * <p>{@code verdict} 为 {@code null} 表示这条需求的当前修订<strong>还没有被审查过</strong>，
     * 与 {@link AcVerdict#NOT_FOUND}（审查过了，但 diff 里没有东西实现它）不是一回事。
     * 这条区分与 {@code AcVerdict} 拒绝设「无裁定」值出于同一个理由。
     */
    record AcCoverage(String acKey, String text, AcVerdict verdict, int openFindings) {
    }

    /**
     * 一条需求当前修订的覆盖度。
     *
     * <p>只看<strong>当前修订</strong>：旧修订上的裁定是针对另一套验收条件得出的，
     * 把它们混进来会让页面展示出一组「对不上号」的结论。因此发布新修订之后，
     * 覆盖度会回到「尚未审查」，直到针对新修订跑过一次审查为止。
     */
    record RequirementCoverage(long requirementId, Long requirementRevisionId, Long lastReviewId,
            List<AcCoverage> criteria) {
    }

    /**
     * 校准表里的一个置信度分箱。
     *
     * <p>{@code confirmedRate} 与 {@code interval} 在 {@code adjudicated} 为 0 时都是
     * {@code null}：一个没有样本的分箱既没有比例也没有区间，返回 0 会被读成
     * 「模型在这一档上从来没对过」。
     */
    record CalibrationBin(FindingConfidence confidence, long adjudicated, long confirmed,
            Double confirmedRate, Interval interval) {
    }

    /**
     * 模型自报置信度与人工裁决之间的校准。
     *
     * <p>{@code awaitingAdjudication} 与 {@code withoutConfidence} 存在，是为了让一张空表
     * 能说清自己为什么空：前者是「还没人裁决」，后者是「这些 finding 产自更早的
     * prompt 版本，本就没有置信度」。少了这两个数，空表只能被猜。
     */
    record ReviewCalibration(List<CalibrationBin> bins, long awaitingAdjudication,
            long withoutConfidence) {
    }

    /** Finding 审计轨迹中的一行。 */
    record FindingEventView(
            long id,
            long actorId,
            FindingAction action,
            FindingStatus fromStatus,
            FindingStatus toStatus,
            String comment,
            Instant createdAt) {
    }
}
