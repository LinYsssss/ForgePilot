package com.forgepilot.review;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The one read behind review activity: every pull request of a project paired
 * with the Review that is <em>currently valid</em> for it, or with nulls when
 * none is.
 *
 * <p>It is plain SQL rather than JPA for two reasons that both matter.
 *
 * <p>First, current validity has to be decided by the database. ARCHITECTURE.md
 * 3.1 pins the comparison for the requirement revision to
 * {@code IS NOT DISTINCT FROM}, "NULL 亦须相等". Written as {@code =}, a pull
 * request with no requirement compares null to null, the predicate is unknown,
 * the join drops the row, and that pull request reports {@code REVIEW_REQUIRED}
 * forever — no number of reviews and no manual trigger could ever clear it. The
 * Java equivalents fail the same way: {@code a.equals(b)} throws when {@code a}
 * is null, and {@code a == b} is false for two boxed {@code Long}s outside the
 * cache. Comparing in the database avoids all three.
 *
 * <p>Second, activity spans {@code pull_request} (owned by {@code scm}) and
 * {@code review}. ARCHITECTURE.md 1.1 runs the arrow {@code review -> scm}, and
 * {@code review} may not inject another feature's repository, so the join is
 * expressed here, in SQL, where it creates no type dependency.
 *
 * <p>One statement, not one per pull request: a requirement list page asks for a
 * whole project at once.
 */
@Repository
class ReviewActivityRepository {

    /**
     * Written once and shared by both callers so the current-validity test cannot
     * drift between them. The two joins are different in kind: {@code requirement}
     * supplies the pull request's <em>current</em> revision (there is no such
     * column on {@code pull_request}), and {@code review} is the identity match.
     *
     * <p>{@code review.requirement_id} is deliberately absent from the match
     * (design.md 2.5): a revision belongs to exactly one requirement and the
     * three-column foreign key enforces it, so matching the revision already
     * implies matching the requirement.
     */
    private static final String CURRENT_REVIEW_PER_PULL_REQUEST = """
            select pr.requirement_id as requirement_id,
                   cur.status        as review_status,
                   cur.decision      as review_decision
              from pull_request pr
              left join requirement r
                     on r.project_id = pr.project_id
                    and r.id = pr.requirement_id
              left join review cur
                     on cur.project_id = pr.project_id
                    and cur.pull_request_id = pr.id
                    and cur.head_sha = pr.head_sha
                    and cur.review_input_fingerprint = pr.review_input_fingerprint
                    and cur.requirement_revision_id is not distinct from r.current_revision_id
             where pr.project_id = ?
            """;

    /**
     * {@code review_status} and {@code review_decision} are both NOT NULL columns,
     * so they are null together exactly when the left join found no currently
     * valid Review.
     */
    private static final RowMapper<CurrentReview> ROW = (rs, index) -> {
        String status = rs.getString("review_status");
        return new CurrentReview(rs.getObject("requirement_id", Long.class),
                status == null ? null : ReviewStatus.valueOf(status),
                status == null ? null : ReviewDecision.valueOf(rs.getString("review_decision")));
    };

    private final JdbcTemplate jdbc;

    ReviewActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every pull request in the project, including the ones with no requirement.
     * Those are not filtered out here because they are not a defect: P1 lets a
     * pull request exist with no association at all, it still has an activity of
     * its own, and it belongs to no requirement's aggregate. Which of those two
     * facts applies is the caller's decision, not this query's.
     */
    List<CurrentReview> ofProject(long projectId) {
        return jdbc.query(CURRENT_REVIEW_PER_PULL_REQUEST + "order by pr.id", ROW, projectId);
    }

    List<CurrentReview> ofRequirement(long projectId, long requirementId) {
        return jdbc.query(CURRENT_REVIEW_PER_PULL_REQUEST + "and pr.requirement_id = ? order by pr.id",
                ROW, projectId, requirementId);
    }

    /**
     * Needed because a requirement with no pull request at all still has an
     * activity — {@code NO_PR} — and no join over {@code pull_request} can produce
     * a row for it.
     */
    List<Long> requirementIds(long projectId) {
        return jdbc.queryForList("select id from requirement where project_id = ? order by id",
                Long.class, projectId);
    }

    /**
     * One pull request, the requirement it is associated with (null when it has
     * none), and the execution status and decision of its currently valid Review
     * (both null when it has none).
     */
    record CurrentReview(Long requirementId, ReviewStatus status, ReviewDecision decision) {

        boolean hasCurrentReview() {
            return status != null;
        }
    }
}
