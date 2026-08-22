package com.forgepilot.review;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.review.ReviewViews.DecisionResult;
import com.forgepilot.review.ReviewViews.ProjectReviewRow;
import com.forgepilot.review.ReviewViews.FindingView;
import com.forgepilot.review.ReviewViews.ReviewDetail;
import com.forgepilot.review.ReviewViews.ReviewSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one-shot human verdict on a Review, and the read paths that show it.
 *
 * <p>ARCHITECTURE.md 3.1 prescribes the write exactly: lock the {@code pull_request}
 * row, check six preconditions one by one, then update conditionally on
 * {@code decision = 'PENDING'} and treat anything but one affected row as a
 * conflict. It also forbids the two shortcuts that look equivalent — a plain
 * {@code EXISTS} check, or an unconditional save. This class follows that
 * sentence literally, because every clause of it was measured to matter:
 *
 * <ul>
 * <li>Without the pull request lock, the preconditions about head, fingerprint and
 * requirement revision are evaluated against a stale snapshot of the pull request,
 * and an {@code APPROVE} was granted for a head the SCM had already moved past.</li>
 * <li>Without the conditional update there is no affected-row count, and therefore
 * no honest basis for the 409 that a concurrent decision must receive.</li>
 * </ul>
 *
 * <p>Decision is not overwritable, reversible or re-writable, so there is no
 * update path here and no way to reach one.
 */
@Service
public class ReviewDecisionService {

    private final ReviewRepository reviews;
    private final FindingRepository findings;
    private final DecisionRepository decisions;
    private final ProjectAccessService access;
    private final ObjectMapper json;

    ReviewDecisionService(ReviewRepository reviews, FindingRepository findings,
            DecisionRepository decisions, ProjectAccessService access, ObjectMapper json) {
        this.reviews = reviews;
        this.findings = findings;
        this.decisions = decisions;
        this.access = access;
        this.json = json;
    }

    /**
     * Records the final human verdict (api-contract.md 2.4).
     *
     * <p>LEADER and REVIEWER only. A DEVELOPER may trigger a review and may fix
     * what it found, but PRD.md 3 does not let them close it, not even on their own
     * pull request.
     */
    @Transactional
    public DecisionResult decide(long projectId, long actorId, long reviewId, ReviewDecision decision,
            String comment) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER, ProjectRole.REVIEWER);
        // After authorization, so a caller outside the project learns nothing about
        // it, not even that their body was well formed.
        if (decision != ReviewDecision.APPROVE && decision != ReviewDecision.REQUEST_CHANGES) {
            throw ApiException.unprocessable("A decision is either APPROVE or REQUEST_CHANGES.");
        }

        // The parent id is read on its own so the lock can be taken before the
        // Review itself is loaded. It is immutable, so reading it early is safe.
        long pullRequestId = decisions.pullRequestIdOf(projectId, reviewId)
                .orElseThrow(ApiException::notFound);
        String currentHead = decisions.lockPullRequestAndReadHead(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        String currentFingerprint = decisions.currentReviewInputFingerprint(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        Long currentRevisionId = decisions.currentRequirementRevisionId(projectId, pullRequestId)
                .orElse(null);

        // Loaded only now: under READ COMMITTED this is the first read of the row in
        // this transaction, so it is a fresh one taken after the lock. A read issued
        // before the lock would still show PENDING to the loser of a race.
        Review review = reviews.findByProjectIdAndId(projectId, reviewId)
                .orElseThrow(ApiException::notFound);

        checkPreconditions(review, currentHead, currentFingerprint, currentRevisionId);

        int updated = decisions.decideIfStillPending(projectId, reviewId, decision.name(), actorId, comment);
        if (updated != 1) {
            // The six were true a moment ago and are not now. Nothing is written and
            // nothing is retried here: a final human verdict is not something to
            // re-attempt on the caller's behalf.
            throw ApiException.conflict("This review was decided or moved on concurrently.");
        }

        Review decided = reviews.findByProjectIdAndId(projectId, reviewId)
                .orElseThrow(ApiException::notFound);
        return new DecisionResult(decided.getDecision(), decided.getDecisionBy(), decided.getDecisionAt());
    }

    /** The six preconditions of ARCHITECTURE.md 3.1, in its order, so a refusal can name itself. */
    private void checkPreconditions(Review review, String currentHead, String currentFingerprint,
            Long currentRevisionId) {
        if (review.getStatus() != ReviewStatus.COMPLETED) {
            throw ApiException.conflict("Only a completed review can be decided.");
        }
        if (review.getDecision() != ReviewDecision.PENDING) {
            throw ApiException.conflict("This review already carries a final decision.");
        }
        if (!currentHead.equals(review.getHeadSha())) {
            throw ApiException.conflict("The pull request has moved to a different head since this review.");
        }
        if (!currentFingerprint.equals(review.getReviewInputFingerprint())) {
            throw ApiException.conflict("The pull request's review inputs have changed since this review.");
        }
        // NULL-safe on purpose: "neither side has a requirement revision" is a match,
        // and comparing with equals() here is the same rule the SQL states with
        // IS NOT DISTINCT FROM.
        if (!Objects.equals(currentRevisionId, review.getRequirementRevisionId())) {
            throw ApiException.conflict("The pull request now points at a different requirement revision.");
        }
        // Derived from the rows, against the pull request's current head. Changing
        // the base, the association, the requirement revision or re-syncing the diff
        // does not lift it; only a new head SHA does.
        if (reviews.existsByProjectIdAndPullRequestIdAndHeadShaAndDecision(
                review.getProjectId(), review.getPullRequestId(), currentHead,
                ReviewDecision.REQUEST_CHANGES)) {
            throw ApiException.conflict("This head already has changes requested; only a new head clears it.");
        }
    }

    /** api-contract.md 2.2. Any member of the project may read a review. */
    @Transactional(readOnly = true)
    public ReviewDetail detail(long projectId, long actorId, long reviewId) {
        access.requireMember(projectId, actorId);
        Review review = reviews.findByProjectIdAndId(projectId, reviewId)
                .orElseThrow(ApiException::notFound);
        PullRequestInputs inputs = inputsOf(projectId, review.getPullRequestId());

        List<Finding> rows = findings.findByProjectIdAndReviewIdOrderByIdAsc(projectId, reviewId);
        Map<Long, String> acKeys = acKeysOf(projectId, rows);
        JsonNode summary = parse(review.getSummaryJson());

        return new ReviewDetail(review.getId(), review.getPullRequestId(), review.getHeadSha(),
                review.getReviewInputFingerprint(), review.getRequirementId(),
                review.getRequirementRevisionId(), review.getStatus(), review.getDecision(),
                review.getDecisionBy(), review.getDecisionAt(), review.getDecisionComment(),
                inputs.matches(review), contextSnapshot(review, summary),
                summary == null ? null : summary.get("coverage"),
                summary == null ? null : summary.get("acVerdicts"),
                rows.stream().map(finding -> view(finding, acKeys)).toList(),
                review.getEngine(), review.getPromptVersion(), review.getModel(),
                review.getExecutionAttempt());
    }

    /**
     * api-contract.md 2.3. Every review of this pull request, oldest first by
     * {@code (created_at, id)} so the order is the same on every run.
     */
    @Transactional(readOnly = true)
    public List<ReviewSummary> history(long projectId, long actorId, long pullRequestId) {
        access.requireMember(projectId, actorId);
        PullRequestInputs inputs = inputsOf(projectId, pullRequestId);
        return reviews.findByProjectIdAndPullRequestIdOrderByCreatedAtAscIdAsc(projectId, pullRequestId)
                .stream()
                .map(review -> new ReviewSummary(review.getId(), review.getHeadSha(),
                        review.getRequirementRevisionId(), review.getStatus(), review.getDecision(),
                        inputs.matches(review), review.getCreatedAt()))
                .toList();
    }

    /**
     * Every review in the project, newest first — the 代码审查 page's list.
     *
     * <p>{@code isCurrent} is derived per pull request rather than per review, so a
     * project with many reviews over few pull requests costs one input read each
     * rather than one per row.
     */
    @Transactional(readOnly = true)
    public List<ProjectReviewRow> listForProject(long projectId, long actorId) {
        access.requireMember(projectId, actorId);
        Map<Long, Integer> numbers = new HashMap<>();
        for (Object[] row : decisions.pullRequestNumbers(projectId)) {
            numbers.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        Map<Long, PullRequestInputs> inputs = new HashMap<>();
        return reviews.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
                .map(review -> new ProjectReviewRow(review.getId(), review.getPullRequestId(),
                        numbers.getOrDefault(review.getPullRequestId(), 0), review.getHeadSha(),
                        review.getRequirementId(), review.getStatus(), review.getDecision(),
                        inputs.computeIfAbsent(review.getPullRequestId(),
                                pullRequest -> inputsOf(projectId, pullRequest)).matches(review),
                        review.getCreatedAt()))
                .toList();
    }

    private PullRequestInputs inputsOf(long projectId, long pullRequestId) {
        String head = decisions.currentHeadSha(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        String fingerprint = decisions.currentReviewInputFingerprint(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        return new PullRequestInputs(head, fingerprint,
                decisions.currentRequirementRevisionId(projectId, pullRequestId).orElse(null));
    }

    private Map<Long, String> acKeysOf(long projectId, List<Finding> rows) {
        Collection<Long> acIds = rows.stream().map(Finding::getAcId).filter(Objects::nonNull).distinct().toList();
        if (acIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> keys = new HashMap<>();
        for (Object[] row : decisions.acceptanceCriterionKeys(projectId, acIds)) {
            keys.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return keys;
    }

    private static FindingView view(Finding finding, Map<Long, String> acKeys) {
        return new FindingView(finding.getId(), finding.getFindingType(), finding.getPath(),
                finding.getLine(), finding.getEvidence(), finding.getStatus(), finding.getContinuity(),
                finding.getRequirementId(), finding.getRequirementRevisionId(), finding.getAcId(),
                finding.getAcId() == null ? null : acKeys.get(finding.getAcId()),
                finding.getAssigneeId(), finding.getCarriedFromFindingId(), finding.getFindingKey(),
                finding.getEvidenceHash(), finding.getBasisHash());
    }

    /** Absent stays absent: an empty snapshot and a missing one are not the same answer. */
    private JsonNode parse(String stored) {
        return stored == null ? null : json.readTree(stored);
    }

    /**
     * The Review context is stored in two immutable-at-completion halves for a
     * causal reason: requirement/AC/PR/diff inputs are copied in the SCM
     * transaction that creates the Review, while knowledge evidence and the
     * truncation plan only exist after the post-commit worker performs retrieval
     * and batching. The public context is their union, never a reconstruction from
     * the pull request's current association or changed-files row.
     */
    private JsonNode contextSnapshot(Review review, JsonNode summary) {
        JsonNode stored = parse(review.getContextSnapshotJson());
        if (stored == null || !stored.isObject()) {
            return stored;
        }
        ObjectNode combined = (ObjectNode) stored.deepCopy();
        if (summary == null) {
            combined.set("knowledgeEvidence", json.createArrayNode());
            combined.putNull("truncation");
        } else {
            JsonNode evidence = summary.get("knowledgeEvidence");
            combined.set("knowledgeEvidence",
                    evidence == null ? json.createArrayNode() : evidence.deepCopy());
            JsonNode coverage = summary.get("coverage");
            if (coverage == null) {
                combined.putNull("truncation");
            } else {
                combined.set("truncation", coverage.deepCopy());
            }
        }
        return combined;
    }

    /**
     * The pull request's present inputs, and the derivation of {@code isCurrent}
     * against them. The requirement <em>id</em> is deliberately not part of it: only
     * head, diff fingerprint and requirement revision decide whether a review still
     * applies (design.md 2.5).
     */
    private record PullRequestInputs(String headSha, String fingerprint, Long requirementRevisionId) {

        boolean matches(Review review) {
            return headSha.equals(review.getHeadSha())
                    && fingerprint.equals(review.getReviewInputFingerprint())
                    && Objects.equals(requirementRevisionId, review.getRequirementRevisionId());
        }
    }
}
