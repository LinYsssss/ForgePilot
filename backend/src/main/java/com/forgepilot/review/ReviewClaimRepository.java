package com.forgepilot.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The execution-side statements: everything whose <em>affected row count</em> is
 * the decision rather than a detail. They are native and conditional on purpose —
 * reading a row and then writing it back would reopen exactly the window the
 * WHERE clauses close, and Hibernate's dirty checking cannot report "you matched
 * zero rows".
 *
 * <p>Two of these read {@code pull_request} and {@code requirement}. That is not
 * a boundary violation: design.md 2.1 rules that {@code review} owns every
 * derivation that needs both its own table and the pull request, because the
 * dependency graph only allows {@code review -> scm} and the reverse would put a
 * cycle in front of ArchUnit's most important rule. No {@code scm} repository is
 * injected anywhere.
 *
 * <p>All times come from {@code now()}, which PostgreSQL freezes at transaction
 * start. Measured, that direction is the safe one — a stale reference time finds
 * <em>fewer</em> expired leases, so a live worker is never preempted — and
 * design.md 6.6 rules against {@code clock_timestamp()} in exchange for keeping
 * every one of these statements in its own short transaction.
 */
public interface ReviewClaimRepository extends Repository<Review, Long> {

    /**
     * The pull request's <em>current</em> identity, which is what a Review is
     * created from (ARCHITECTURE.md 3.1). The revision comes from the requirement's
     * {@code current_revision_id} rather than from the pull request, which stores
     * only the requirement.
     */
    @Query(value = """
            SELECT p.project_id               AS "projectId",
                   p.head_sha                 AS "headSha",
                   p.review_input_fingerprint AS "reviewInputFingerprint",
                   p.requirement_id           AS "requirementId",
                   q.current_revision_id      AS "requirementRevisionId",
                   p.author_external_user_id  AS "authorExternalUserId"
              FROM pull_request p
              LEFT JOIN requirement q ON q.project_id = p.project_id AND q.id = p.requirement_id
             WHERE p.id = :pullRequestId
            """, nativeQuery = true)
    Optional<PullRequestIdentity> findPullRequestIdentity(@Param("pullRequestId") long pullRequestId);

    /**
     * Drops the output of an attempt that is about to be abandoned. It has to run
     * in the same transaction as the claim that follows it, because
     * {@code fk_finding_review} points at {@code (project_id, id,
     * execution_attempt)}: a crashed worker's findings hold the current attempt
     * number, and incrementing it while they exist fails with 23503. Measured, that
     * is not a corner case — it pins the Review permanently, and the only worker
     * that could clear it is the one that died.
     *
     * <p>{@code ON UPDATE CASCADE} would also make the increment succeed and must
     * never be used: measured, it silently relabels the dead attempt's findings as
     * the new attempt's output, which is worse than having no fence at all because
     * it manufactures evidence.
     *
     * <p>The EXISTS clause is what makes this safe to call before knowing whether
     * the claim will succeed. Without it, a caller that tries and fails to claim a
     * <em>live</em> RUNNING review would still have deleted that live worker's
     * findings. COMPLETED is excluded outright: its findings are history.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM finding f
             WHERE f.project_id = :projectId
               AND f.review_id = :reviewId
               AND EXISTS (SELECT 1 FROM review r
                            WHERE r.project_id = f.project_id
                              AND r.id = f.review_id
                              AND r.execution_attempt = f.review_attempt
                              AND r.status <> 'COMPLETED'
                              AND (r.status <> 'RUNNING' OR r.lease_until < now()))
            """, nativeQuery = true)
    int discardAbandonedFindings(@Param("projectId") long projectId, @Param("reviewId") long reviewId);

    /**
     * The claim (ARCHITECTURE.md 3.2): one atomic conditional update that admits
     * only a PENDING row or a RUNNING one whose lease has expired, and that mints
     * the fencing triple in the same statement. Two workers racing an expired lease
     * both reach the row; the loser re-evaluates the WHERE clause against the
     * winner's committed version, finds a lease in the future and matches zero rows.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review
               SET status = 'RUNNING',
                   execution_attempt = execution_attempt + 1,
                   execution_token = cast(:token AS uuid),
                   lease_until = now() + (:leaseSeconds * interval '1 second'),
                   updated_at = now()
             WHERE project_id = :projectId
               AND id = :reviewId
               AND (status = 'PENDING' OR (status = 'RUNNING' AND lease_until < now()))
            """, nativeQuery = true)
    int claim(@Param("projectId") long projectId, @Param("reviewId") long reviewId,
            @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);

    /** Read back under the claim's own row lock, so nobody can have moved it. */
    @Query(value = "SELECT execution_attempt FROM review WHERE project_id = :projectId AND id = :reviewId",
            nativeQuery = true)
    Integer attemptOf(@Param("projectId") long projectId, @Param("reviewId") long reviewId);

    /**
     * Renewal, one of the three writes ARCHITECTURE.md 3.2 requires to match the
     * token. It is the heartbeat too: an expired lease is the stall signal, so
     * there is no second liveness mechanism and no seventeenth table.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review
               SET lease_until = now() + (:leaseSeconds * interval '1 second'),
                   updated_at = now()
             WHERE project_id = :projectId
               AND id = :reviewId
               AND execution_token = cast(:token AS uuid)
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int renew(@Param("projectId") long projectId, @Param("reviewId") long reviewId,
            @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);

    /**
     * The token stays on the row after completion. It is audit, and status alone
     * already fences later writes — nothing but RUNNING is writable by a worker.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review
               SET status = 'COMPLETED', lease_until = NULL, updated_at = now()
             WHERE project_id = :projectId
               AND id = :reviewId
               AND execution_token = cast(:token AS uuid)
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int complete(@Param("projectId") long projectId, @Param("reviewId") long reviewId,
            @Param("token") String token);

    /**
     * The most commonly forgotten fence. A worker whose lease expired must not be
     * able to mark the review FAILED either: measured, an unfenced statement here
     * lets a dead attempt fail the attempt that replaced it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review
               SET status = 'FAILED', lease_until = NULL, updated_at = now()
             WHERE project_id = :projectId
               AND id = :reviewId
               AND execution_token = cast(:token AS uuid)
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int fail(@Param("projectId") long projectId, @Param("reviewId") long reviewId,
            @Param("token") String token);

    /**
     * Manual retry (ARCHITECTURE.md 3.2): the same row goes back to PENDING. The
     * attempt is minted by the subsequent atomic claim, exactly as it is for every
     * other execution; incrementing here as well would make one retry consume two
     * attempt numbers. Conditional on FAILED, so two people retrying at once
     * produce one retry and one 409 rather than two queued executions.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review
               SET status = 'PENDING',
                   execution_token = NULL,
                   lease_until = NULL,
                   updated_at = now()
             WHERE project_id = :projectId AND id = :reviewId AND status = 'FAILED'
            """, nativeQuery = true)
    int retryFailed(@Param("projectId") long projectId, @Param("reviewId") long reviewId);

    /**
     * Reconciliation's only query, and the structural rule that makes "never
     * backfill" checkable rather than remembered: <strong>{@code review} is the
     * one and only table in the FROM clause.</strong>
     *
     * <p>Any result set driven by {@code review} is a subset of rows that already
     * exist, so however wrong the conditions might be, this can never produce a
     * Review that was never created. Drive it from {@code pull_request} instead —
     * even with {@code NOT EXISTS (SELECT ... FROM review ...)} — and the result
     * set becomes a subset of pull requests, for which the only possible
     * "recovery" is creation. Measured on the same data, the pull-request-driven
     * shape returns a row to create and this one returns nothing.
     *
     * <p>That is not hygiene. A backfill here would fire whenever somebody edits a
     * requirement association, which ARCHITECTURE.md 3.1 says must be re-reviewed
     * by hand — an automatic trigger that bypasses {@code requestReview} and its
     * authorization, spending tokens nobody asked for, and a second pipeline
     * hidden inside one SQL statement.
     *
     * <p>{@code FAILED} is deliberately outside the set: retry is a human act
     * (3.2), and picking it up here would be an unrequested retry policy.
     * {@code COMPLETED} is never re-run at all. PENDING is measured against
     * {@code updated_at} rather than {@code created_at} because a retried row is
     * old but freshly pending.
     */
    @Query(value = """
            SELECT r.project_id AS "projectId", r.id AS "reviewId"
              FROM review r
             WHERE (r.status = 'PENDING'
                        AND r.updated_at < now() - (:pendingStallSeconds * interval '1 second'))
                OR (r.status = 'RUNNING' AND r.lease_until < now())
             ORDER BY r.id
             LIMIT :limit
            """, nativeQuery = true)
    List<StalledReview> findStalled(@Param("pendingStallSeconds") int pendingStallSeconds,
            @Param("limit") int limit);

    /** The pull request's current four-tuple inputs, plus who opened it (D010). */
    interface PullRequestIdentity {

        Long getProjectId();

        String getHeadSha();

        String getReviewInputFingerprint();

        Long getRequirementId();

        Long getRequirementRevisionId();

        String getAuthorExternalUserId();
    }

    /** A row that is stored but not running: either never claimed, or abandoned. */
    interface StalledReview {

        Long getProjectId();

        Long getReviewId();
    }
}
