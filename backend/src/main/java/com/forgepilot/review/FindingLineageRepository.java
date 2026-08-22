package com.forgepilot.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The reads behind Finding lineage (ARCHITECTURE.md 3.6). Every one of them is
 * scoped to a single pull request, because continuity is only ever computed
 * inside one: the same {@code finding_key} in another pull request describes
 * another change and must not carry a rejection over.
 *
 * <p>Extends the plain {@code Repository} marker rather than {@code JpaRepository}:
 * lineage is a read, and nothing here should be able to write a Finding.
 */
public interface FindingLineageRepository extends Repository<Finding, Long> {

    /**
     * The immediately preceding COMPLETED Review of this pull request, ordered by
     * {@code (created_at, id)} as 3.6.3 requires — two Reviews created inside the
     * same clock tick would otherwise compare in whatever order the plan returned,
     * and "the previous round" would not be a fact.
     *
     * <p>Native because PostgreSQL's row-value comparison says "strictly before
     * this Review" in one expression; JPQL has no such comparison and would need
     * the created_at subquery written twice.
     */
    @Query(value = """
            SELECT prev.id FROM review prev
             WHERE prev.project_id = :projectId
               AND prev.pull_request_id = :pullRequestId
               AND prev.status = 'COMPLETED'
               AND (prev.created_at, prev.id)
                   < (SELECT self.created_at, self.id FROM review self WHERE self.id = :reviewId)
             ORDER BY prev.created_at DESC, prev.id DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<Long> findPreviousCompletedReviewId(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId, @Param("reviewId") long reviewId);

    @Query("SELECT f FROM Finding f WHERE f.projectId = :projectId AND f.reviewId = :reviewId ORDER BY f.id ASC")
    List<Finding> findFindingsOfReview(@Param("projectId") long projectId, @Param("reviewId") long reviewId);

    /**
     * The Finding behind the most recent human judgement on this
     * {@code finding_key} anywhere in this pull request's history — but only when
     * that judgement was a rejection (3.6.4).
     *
     * <p>The rejection test is applied <em>after</em> the ordering, not inside it.
     * Filtering on {@code to_status = 'REJECTED'} first would find the most recent
     * rejection rather than the most recent judgement, so a finding that was
     * rejected and later reopened would be re-suppressed on the next round and the
     * reopening would silently undo itself.
     *
     * <p>Ordering is {@code (finding_event.created_at, finding_event.id)}, which
     * 3.6.4 names, and the join to {@code review} is what keeps a judgement made on
     * another pull request out of this answer.
     */
    @Query(value = """
            SELECT latest.finding_id FROM (
                SELECT e.finding_id, e.to_status
                  FROM finding_event e
                  JOIN finding f ON f.project_id = e.project_id AND f.id = e.finding_id
                  JOIN review r ON r.project_id = f.project_id AND r.id = f.review_id
                 WHERE e.project_id = :projectId
                   AND r.pull_request_id = :pullRequestId
                   AND f.finding_key = :findingKey
                 ORDER BY e.created_at DESC, e.id DESC
                 LIMIT 1) AS latest
             WHERE latest.to_status = 'REJECTED'
            """, nativeQuery = true)
    Optional<Long> findMostRecentlyRejectedFindingId(@Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId, @Param("findingKey") String findingKey);

    @Query("SELECT f FROM Finding f WHERE f.projectId = :projectId AND f.id = :findingId")
    Optional<Finding> findFinding(@Param("projectId") long projectId, @Param("findingId") long findingId);
}
