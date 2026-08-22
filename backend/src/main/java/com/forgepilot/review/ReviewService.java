package com.forgepilot.review;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectMember;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.review.ReviewClaimRepository.PullRequestIdentity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one entry into the Review engine. Automatic delivery, a human pressing
 * "review", and a retry after a failure all end up in the same find-or-create
 * (ARCHITECTURE.md 3.1) — there is no second engine and no second pipeline.
 *
 * <p>The four columns that identity is made of are read from the pull request's
 * <em>current</em> values every time. That is why a new head, a moved base, a
 * re-synced diff or a newly published requirement revision each mint a new row
 * while the previous one stays exactly as it was decided.
 */
@Service
public class ReviewService {

    /**
     * Published inside the transaction that left a Review in PENDING, and consumed
     * after that transaction commits.
     *
     * <p>It exists because the two halves of the trigger cannot be one thing: the
     * row has to be created inside the caller's transaction so a failure rolls the
     * whole thing back, and the executor must not be handed anything until that
     * transaction has committed, or the worker looks for a row that READ COMMITTED
     * will not show it. Naming the row here means the after-commit half needs no
     * database access at all — which is what makes it possible to obey the rule
     * that it must not touch one.
     */
    public record ReviewReady(long projectId, long reviewId) {
    }

    private final ReviewRepository reviews;
    private final ReviewClaimRepository claims;
    private final ProjectAccessService access;
    private final ApplicationEventPublisher publisher;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ReviewService(ReviewRepository reviews, ReviewClaimRepository claims, ProjectAccessService access,
            ApplicationEventPublisher publisher, JdbcTemplate jdbc, ObjectMapper json) {
        this.reviews = reviews;
        this.claims = claims;
        this.access = access;
        this.publisher = publisher;
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * The human path: trigger, re-trigger after a requirement revision, or retry a
     * failure. Which of those it is depends only on what already exists under the
     * pull request's current identity, so the caller has nothing to choose.
     *
     * <p>A COMPLETED row answers 409 rather than starting again. That is the one
     * place where this differs from the delivery path, and deliberately: 3.2 says
     * COMPLETED is never re-run or overwritten, and a person asking for it deserves
     * to be told so.
     */
    @Transactional
    public Review requestReview(long projectId, long pullRequestId, long actorId) {
        PullRequestIdentity pullRequest = pullRequestIn(projectId, pullRequestId);
        authorize(projectId, actorId, pullRequest);
        Review review = openOrTake(projectId, pullRequestId, pullRequest);
        return switch (review.getStatus()) {
            case PENDING, RUNNING -> review;
            case COMPLETED -> throw ApiException.conflict(
                    "This review is already complete. A completed review is never re-run; "
                            + "push a new commit or publish a new requirement revision to review again.");
            case FAILED -> retry(projectId, review);
        };
    }

    /**
     * The automatic path, called by the listener that joins the SCM transaction.
     *
     * <p>{@code MANDATORY} rather than {@code REQUIRED}: this must join the caller's
     * transaction, never start one of its own. If it ever ran outside one, a pull
     * request could commit while the Review it implies did not — the exact state
     * 3.1 forbids — and the failure would be silent. This way it is loud.
     *
     * <p>Unlike {@link #requestReview}, an existing COMPLETED row is simply taken.
     * A redelivery of the same webhook must be harmless; raising a conflict here
     * would roll the pull request update back over a duplicate GitHub delivery.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Review openForDelivery(long pullRequestId) {
        PullRequestIdentity pullRequest = claims.findPullRequestIdentity(pullRequestId)
                .orElseThrow(ApiException::notFound);
        return openOrTake(pullRequest.getProjectId(), pullRequestId, pullRequest);
    }

    /**
     * Idempotent by the four-tuple. The lookup is NULL-safe: a pull request with no
     * requirement has a null revision on both sides, and a plain {@code =} would
     * evaluate to unknown, miss the existing row and try to insert a duplicate on
     * every single delivery.
     */
    private Review openOrTake(long projectId, long pullRequestId, PullRequestIdentity pullRequest) {
        // A requirement whose first revision has not been published yet gives a
        // requirement id with no revision, and the review's pairing CHECK refuses
        // that half-set state — correctly, since a review against a requirement
        // with no content is not a thing. Such a pull request is reviewed as one
        // with no requirement at all.
        Long revisionId = pullRequest.getRequirementRevisionId();
        Long requirementId = revisionId == null ? null : pullRequest.getRequirementId();
        return reviews.findByIdentity(pullRequestId, pullRequest.getHeadSha(),
                        pullRequest.getReviewInputFingerprint(), revisionId)
                .orElseGet(() -> create(projectId, pullRequestId, pullRequest, requirementId, revisionId));
    }

    private Review create(long projectId, long pullRequestId, PullRequestIdentity pullRequest,
            Long requirementId, Long revisionId) {
        Review review = new Review(projectId, pullRequestId, pullRequest.getHeadSha(),
                pullRequest.getReviewInputFingerprint(), requirementId, revisionId);
        // The pull_request row is a mutable current snapshot. Capturing it here,
        // inside the same transaction that creates the Review, is therefore not an
        // optional audit nicety: without this copy a queued worker would review a
        // later diff under this row's older fingerprint. Requirement revisions are
        // immutable, but copying them as well keeps every historical read on one
        // source of truth instead of reconstructing meaning from live tables.
        review.recordContextSnapshot(contextSnapshot(projectId, pullRequestId, revisionId));
        review = reviews.saveAndFlush(review);
        publisher.publishEvent(new ReviewReady(projectId, review.getId()));
        return review;
    }

    /**
     * The immutable, provider-free portion of ARCHITECTURE.md 4.2's
     * {@code ReviewContext}. Knowledge evidence is added to the completed summary
     * by the worker because retrieving it requires an external embedding call and
     * may only happen after this transaction commits. The read API combines the
     * two immutable stored halves, while the engine always reads requirement, AC
     * and diff inputs from this half.
     */
    private String contextSnapshot(long projectId, long pullRequestId, Long revisionId) {
        PullRequestSnapshot pullRequest = jdbc.queryForObject("""
                SELECT p.external_number, p.base_sha, p.head_sha, p.review_input_fingerprint,
                       p.title, p.changed_files, r.provider, r.instance_identity, r.external_id
                  FROM pull_request p
                  JOIN scm_repository r ON r.project_id = p.project_id AND r.id = p.repository_id
                 WHERE p.project_id = ? AND p.id = ?
                """, (rs, row) -> new PullRequestSnapshot(
                        rs.getInt("external_number"), rs.getString("base_sha"),
                        rs.getString("head_sha"), rs.getString("review_input_fingerprint"),
                        rs.getString("title"), rs.getString("changed_files"),
                        rs.getString("provider"), rs.getString("instance_identity"),
                        rs.getString("external_id")), projectId, pullRequestId);

        ObjectNode snapshot = json.createObjectNode();
        if (revisionId == null) {
            snapshot.putNull("requirement");
        } else {
            jdbc.queryForObject("""
                    SELECT rr.requirement_id, rr.id, rr.title, rr.background, rr.description
                      FROM requirement_revision rr
                     WHERE rr.project_id = ? AND rr.id = ?
                    """, (rs, row) -> {
                        snapshot.putObject("requirement")
                                .put("id", rs.getLong("requirement_id"))
                                .put("revisionId", rs.getLong("id"))
                                .put("title", rs.getString("title"))
                                .put("background", rs.getString("background"))
                                .put("description", rs.getString("description"));
                        return Boolean.TRUE;
                    }, projectId, revisionId);
        }

        ArrayNode criteria = snapshot.putArray("acceptanceCriteria");
        if (revisionId != null) {
            jdbc.query("""
                    SELECT id, ac_key, text
                      FROM acceptance_criterion
                     WHERE project_id = ? AND requirement_revision_id = ?
                     ORDER BY sort_order, id
                    """, rs -> {
                        criteria.addObject()
                                .put("id", rs.getLong("id"))
                                .put("acKey", rs.getString("ac_key"))
                                .put("text", rs.getString("text"));
                    }, projectId, revisionId);
        }

        snapshot.putObject("pullRequest")
                .put("provider", pullRequest.provider())
                .put("instance", pullRequest.instanceIdentity())
                .put("repository", pullRequest.repositoryExternalId())
                .put("number", pullRequest.externalNumber())
                .put("baseSha", pullRequest.baseSha())
                .put("headSha", pullRequest.headSha())
                .put("inputFingerprint", pullRequest.inputFingerprint())
                .put("title", pullRequest.title());
        JsonNode changedFiles = json.readTree(pullRequest.changedFilesJson());
        snapshot.set("changedFiles", changedFiles);
        return json.writeValueAsString(snapshot);
    }

    private record PullRequestSnapshot(int externalNumber, String baseSha, String headSha,
            String inputFingerprint, String title, String changedFilesJson, String provider,
            String instanceIdentity, String repositoryExternalId) {
    }

    /**
     * Retry reuses the row, so every attempt of one identity stays on one row
     * (3.2). The abandoned attempt's findings go first: they hold the old attempt
     * number in a foreign key, and without deleting them the worker's subsequent
     * claim cannot increment it.
     */
    private Review retry(long projectId, Review failed) {
        claims.discardAbandonedFindings(projectId, failed.getId());
        if (claims.retryFailed(projectId, failed.getId()) != 1) {
            throw ApiException.conflict("This review is no longer failed; somebody else retried it.");
        }
        Review reset = reviews.findByProjectIdAndId(projectId, failed.getId())
                .orElseThrow(ApiException::notFound);
        publisher.publishEvent(new ReviewReady(projectId, reset.getId()));
        return reset;
    }

    /** A pull request in another project answers exactly like one that never existed. */
    private PullRequestIdentity pullRequestIn(long projectId, long pullRequestId) {
        return claims.findPullRequestIdentity(pullRequestId)
                .filter(pullRequest -> pullRequest.getProjectId() == projectId)
                .orElseThrow(ApiException::notFound);
    }

    /**
     * LEADER and REVIEWER may review anything in their project; a DEVELOPER may
     * only trigger their own pull request (PRD 3).
     *
     * <p>"Their own" is decided by the provider's external user id against the
     * member's verified SCM identity, never by username (D010): usernames are
     * re-assignable, so matching on one would hand a renamed account somebody
     * else's pull requests. A member with no verified SCM identity matches nothing.
     */
    private void authorize(long projectId, long actorId, PullRequestIdentity pullRequest) {
        ProjectMember member = access.requireMember(projectId, actorId);
        if (member.getRole() != ProjectRole.DEVELOPER) {
            return;
        }
        if (!pullRequest.getAuthorExternalUserId().equals(member.getScmExternalUserId())) {
            throw ApiException.forbidden();
        }
    }
}
