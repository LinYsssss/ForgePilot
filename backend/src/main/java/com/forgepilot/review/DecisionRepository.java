package com.forgepilot.review;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The statements behind the two human loops: the one-shot Review Decision and the
 * Finding lifecycle. Every write here is a <em>conditional</em> update whose
 * affected-row count is the answer — one means the caller performed the move,
 * anything else means the state was not what the caller was authorized against
 * and the request is a 409.
 *
 * <p>Reading first and then writing unconditionally was measured to be wrong in
 * two separate ways, both of which this file exists to avoid. It lets two
 * concurrent decisions both succeed, and it makes the audit trail record a
 * {@code from_status} that was already stale — two events out of {@code OPEN}
 * describing a history that never happened
 * (research/fencing-and-concurrency-measured.md 5.1, 7.5).
 *
 * <p>Several statements name {@code pull_request}, {@code requirement} and
 * {@code acceptance_criterion}. {@code review} is the one cross-module
 * orchestrator (ARCHITECTURE.md 1.3), and it reaches those tables in its own SQL
 * rather than by injecting another feature's Repository, which ArchUnit rule 4
 * forbids and which would also make the six preconditions of 3.1 impossible to
 * evaluate under one row lock. A query facade on {@code scm} would be the tidier
 * home for the three pull-request reads and is worth adding when that module is
 * next opened.
 */
interface DecisionRepository extends Repository<Review, Long> {

    /**
     * Reads only the immutable parent id, so the caller can take the pull request
     * lock <em>before</em> loading the Review itself.
     *
     * <p>That order is load-bearing. Under READ COMMITTED a read issued before the
     * lock sees a snapshot from before the winner of a concurrent race committed,
     * so the preconditions would be checked against a state that no longer exists
     * (research 5.1). This deliberately does not load the entity: a second
     * {@code findByProjectIdAndId} would be answered from the persistence context
     * and would not be a fresh read at all.
     */
    @Query(value = """
            SELECT r.pull_request_id FROM review r
             WHERE r.project_id = :projectId AND r.id = :reviewId
            """, nativeQuery = true)
    Optional<Long> pullRequestIdOf(@Param("projectId") long projectId, @Param("reviewId") long reviewId);

    /**
     * Takes the pull request row lock required by ARCHITECTURE.md 3.1 and returns
     * the head it locked.
     *
     * <p>This lock is not insurance around the conditional update, it is the only
     * thing that makes preconditions 3, 4 and 5 mean anything under concurrency.
     * Measured: without it the update's EvalPlanQual re-checks only its target row,
     * so the joined {@code pull_request} stays a plain snapshot read and an
     * {@code APPROVE} was granted for {@code head1} while the SCM had already moved
     * the pull request to {@code head2} (research 5.5). The SCM's own writer takes
     * the same row lock before it rolls head forward, so the two serialise.
     */
    @Query(value = """
            SELECT p.head_sha FROM pull_request p
             WHERE p.project_id = :projectId AND p.id = :pullRequestId
             FOR UPDATE
            """, nativeQuery = true)
    Optional<String> lockPullRequestAndReadHead(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId);

    /** The read-path counterpart of the lock: deriving {@code isCurrent} must not lock anything. */
    @Query(value = """
            SELECT p.head_sha FROM pull_request p
             WHERE p.project_id = :projectId AND p.id = :pullRequestId
            """, nativeQuery = true)
    Optional<String> currentHeadSha(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId);

    @Query(value = """
            SELECT p.review_input_fingerprint FROM pull_request p
             WHERE p.project_id = :projectId AND p.id = :pullRequestId
            """, nativeQuery = true)
    Optional<String> currentReviewInputFingerprint(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId);

    /**
     * The pull request's display number. Read once per project for the review list
     * rather than once per row: the list spans pull requests and would otherwise be
     * one query per review.
     */
    @Query(value = """
            SELECT p.id, p.external_number FROM pull_request p WHERE p.project_id = :projectId
            """, nativeQuery = true)
    List<Object[]> pullRequestNumbers(@Param("projectId") long projectId);

    /**
     * The requirement revision the pull request currently points at, along
     * {@code pull_request.requirement_id -> requirement.current_revision_id}.
     *
     * <p>Empty means "no revision", and both ways of getting there — no association
     * at all, or an association whose requirement has no published revision — are
     * the same answer. The caller has already established that the pull request
     * exists, so empty is never ambiguous with a missing row.
     */
    @Query(value = """
            SELECT req.current_revision_id FROM pull_request p
              JOIN requirement req ON req.project_id = p.project_id AND req.id = p.requirement_id
             WHERE p.project_id = :projectId AND p.id = :pullRequestId
            """, nativeQuery = true)
    Optional<Long> currentRequirementRevisionId(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId);

    /**
     * The Decision gate of ARCHITECTURE.md 3.1, all six preconditions folded into
     * the {@code WHERE} clause so that the affected-row count is the verdict.
     *
     * <p>The service also checks the six one at a time, because a caller deserves
     * to be told which one refused. That pre-check is not the gate: it runs before
     * this statement and could go stale between the two. This is the gate.
     *
     * <p>Precondition 5 is written {@code IS NOT DISTINCT FROM} rather than
     * {@code =}. With {@code =}, two NULLs give NULL, the row never matches, and a
     * pull request that implements no requirement could never be decided at all —
     * measured, and it presents as "the button does nothing" rather than as an
     * error (research 5.6).
     *
     * <p>Precondition 6 is derived from the rows every time, against the pull
     * request's <em>current</em> head. It is never cached and never stored as a
     * flag on {@code pull_request}: measured, a force-push back to a blocked head
     * re-locks correctly this way and does not with a stored flag, and reading the
     * latest decision or "is there an APPROVE" instead lifts the block without a
     * line of code changing (research 6.2, 6.3).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE review r
               SET decision = :decision,
                   decision_by = :actorId,
                   decision_at = now(),
                   decision_comment = CAST(:comment AS TEXT),
                   updated_at = now()
              FROM pull_request p
             WHERE r.project_id = :projectId
               AND r.id = :reviewId
               AND p.project_id = r.project_id
               AND p.id = r.pull_request_id
               AND r.status = 'COMPLETED'
               AND r.decision = 'PENDING'
               AND r.head_sha = p.head_sha
               AND r.review_input_fingerprint = p.review_input_fingerprint
               AND r.requirement_revision_id IS NOT DISTINCT FROM
                   (SELECT req.current_revision_id FROM requirement req
                     WHERE req.project_id = p.project_id AND req.id = p.requirement_id)
               AND NOT EXISTS (
                   SELECT 1 FROM review blocking
                    WHERE blocking.project_id = r.project_id
                      AND blocking.pull_request_id = r.pull_request_id
                      AND blocking.head_sha = p.head_sha
                      AND blocking.decision = 'REQUEST_CHANGES')
            """, nativeQuery = true)
    int decideIfStillPending(@Param("projectId") long projectId, @Param("reviewId") long reviewId,
            @Param("decision") String decision, @Param("actorId") long actorId,
            @Param("comment") String comment);

    /**
     * Every Finding move except claiming and reopening, which carry an extra column
     * and an extra condition respectively.
     *
     * <p>{@code status = :fromStatus} is what makes the audit row honest: the
     * {@code from_status} written afterwards is this parameter, and it was matched
     * by the database rather than read a moment earlier.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE finding SET status = :toStatus, updated_at = now()
             WHERE project_id = :projectId AND id = :findingId AND status = :fromStatus
            """, nativeQuery = true)
    int moveFinding(@Param("projectId") long projectId, @Param("findingId") long findingId,
            @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    /**
     * Claiming is assigning yourself: PRD.md 3 grants a DEVELOPER "Finding 认领"
     * and grants nobody "assign to someone else", so the assignment happens here
     * and there is no separate endpoint that could grant more (design.md 3.3).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE finding SET status = 'IN_PROGRESS', assignee_id = :actorId, updated_at = now()
             WHERE project_id = :projectId AND id = :findingId AND status = 'CONFIRMED'
            """, nativeQuery = true)
    int claimFinding(@Param("projectId") long projectId, @Param("findingId") long findingId,
            @Param("actorId") long actorId);

    /**
     * Reopening, restricted to an inherited suppression by the {@code WHERE} clause
     * itself. PRD.md 5 allows it <strong>only</strong> for
     * {@code continuity = SUPPRESSED}; an ordinary rejection is irreversible for
     * every role.
     *
     * <p>A CHECK cannot express this — it would need a subquery — and design.md 6.8
     * declined to add a second constraint trigger, since ARCHITECTURE.md 2.1
     * authorizes constraint triggers one at a time rather than by category. So the
     * rule lives here, and it lives in the condition rather than in a preceding
     * read, which would have a window.
     *
     * <p>{@code continuity} is untouched. Reopening does not erase where the
     * finding came from (PRD.md 5: lineage is a fact about history).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE finding SET status = 'OPEN', updated_at = now()
             WHERE project_id = :projectId AND id = :findingId
               AND status = 'REJECTED' AND continuity = 'SUPPRESSED'
            """, nativeQuery = true)
    int reopenSuppressedFinding(@Param("projectId") long projectId, @Param("findingId") long findingId);

    /**
     * The stable {@code ac_key} for the criteria a review's findings cite. Read by
     * id and filtered by project, so an id from elsewhere simply has no key.
     */
    @Query(value = """
            SELECT ac.id, ac.ac_key FROM acceptance_criterion ac
             WHERE ac.project_id = :projectId AND ac.id IN (:acIds)
            """, nativeQuery = true)
    List<Object[]> acceptanceCriterionKeys(@Param("projectId") long projectId,
            @Param("acIds") Collection<Long> acIds);
}
