package com.forgepilot.review;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * The response bodies of api-contract.md 2.2, 2.3, 2.4, 3.1, 3.2 and 3.4.
 *
 * <p>They are grouped in one file because they are one contract; each is a plain
 * record with no behaviour, so nothing here decides anything.
 *
 * <p>Two shapes are deliberate rather than incidental:
 *
 * <ul>
 * <li>{@code status} and {@code continuity} are two separate components of
 * {@link FindingView}. PRD.md 5 forbids merging them into one field or one UI
 * label — one says what a person decided, the other says where the finding came
 * from — so no view here may collapse them.</li>
 * <li>{@code isCurrent} is a component, not a column. ARCHITECTURE.md 3.5 refuses
 * an {@code INVALIDATED} status: whether a Review still applies is derived by
 * comparing its identity against the pull request's present values, every time
 * it is read.</li>
 * </ul>
 */
final class ReviewViews {

    private ReviewViews() {
    }

    /** api-contract.md 2.4. The three facts a one-shot decision creates. */
    record DecisionResult(ReviewDecision decision, Long decisionBy, Instant decisionAt) {
    }

    /**
     * One row of the project-wide review list. It carries the pull request because
     * that list spans pull requests, which the per-pull-request history does not
     * have to say.
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

    /** api-contract.md 2.3. One row of a pull request's review history; old rows are all kept. */
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
     * api-contract.md 2.2.
     *
     * <p>{@code coverage} and {@code acVerdicts} are passed through from the
     * Review's own {@code summary_json} rather than reshaped here: the engine slice
     * owns that structure, and inventing a second one would make the page describe
     * something the Review never said. A null field and an empty array are
     * therefore different answers, which is what D002 requires of
     * {@code notReviewed} — an unreviewed file may never be silently dropped.
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
     * api-contract.md 3.1. {@code acKey} rather than only {@code acId} because
     * ARCHITECTURE.md 3.6 makes {@code ac_key} the stable business identity across
     * revisions, and a row id must never stand in for it.
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

    /** api-contract.md 3.2. */
    record FindingStatusResult(FindingStatus status) {
    }

    /** api-contract.md 3.4. */
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
