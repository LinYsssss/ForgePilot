package com.forgepilot.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every read is project scoped, so a guessed id from another project answers
 * exactly like one that never existed.
 *
 * <p>The claim and completion statements are native and conditional on purpose.
 * Their affected-row count <em>is</em> the decision: a worker whose lease expired
 * matches zero rows and therefore cannot finish, fail or renew. Reading the row
 * first and then writing would reintroduce the window those conditions close.
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByProjectIdAndId(long projectId, long id);

    List<Review> findByProjectIdAndPullRequestIdOrderByCreatedAtAscIdAsc(long projectId, long pullRequestId);

    /**
     * The identity lookup behind "idempotently create or take" (ARCHITECTURE.md
     * 3.1). {@code IS NOT DISTINCT FROM} rather than {@code =}: a pull request with
     * no requirement has a null revision on both sides, and {@code =} would
     * evaluate to unknown and never match, so every delivery would try to insert
     * a duplicate.
     */
    @Query(value = """
            SELECT * FROM review
             WHERE pull_request_id = :pullRequestId
               AND head_sha = :headSha
               AND review_input_fingerprint = :fingerprint
               AND requirement_revision_id IS NOT DISTINCT FROM :revisionId
            """, nativeQuery = true)
    Optional<Review> findByIdentity(@Param("pullRequestId") long pullRequestId,
            @Param("headSha") String headSha, @Param("fingerprint") String fingerprint,
            @Param("revisionId") Long revisionId);

    /**
     * The Decision Gate (ARCHITECTURE.md 3.1): does this head already carry a
     * REQUEST_CHANGES? Derived every time rather than cached on the pull request —
     * a force-push back to an older head must re-lock automatically, which a
     * stored flag would not do.
     */
    boolean existsByProjectIdAndPullRequestIdAndHeadShaAndDecision(
            long projectId, long pullRequestId, String headSha, ReviewDecision decision);
}
