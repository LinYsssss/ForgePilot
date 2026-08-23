package com.forgepilot.review;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * api-contract.md 2.2、2.3、2.4、3.1、3.2 与 3.4 的响应体。
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

    /** api-contract.md 2.4。一次性决策所产生的三个事实。 */
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

    /** api-contract.md 2.3。某个 PR 审查历史中的一行；旧行全部保留。 */
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
     * api-contract.md 2.2。
     *
     * <p>{@code coverage} 与 {@code acVerdicts} 是从 Review 自己的
     * {@code summary_json} 里原样透传的，而不是在这里重新塑形：那个结构归引擎
     * 切片所有，另造一个只会让页面描述出 Review 从未说过的东西。
     * 因此「字段为 null」与「空数组」是两个不同的答案，
     * 这正是 D002 对 {@code notReviewed} 的要求——
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
     * api-contract.md 3.1。之所以带 {@code acKey} 而不只是 {@code acId}，
     * 是因为 ARCHITECTURE.md 3.6 把 {@code ac_key} 定为跨修订稳定的业务身份，
     * 而行 id 绝不能拿来顶替它。
     */
    record FindingView(
            long id,
            FindingType findingType,
            String path,
            Integer line,
            String evidence,
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

    /** api-contract.md 3.2。 */
    record FindingStatusResult(FindingStatus status) {
    }

    /** api-contract.md 3.4。 */
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
