package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import com.forgepilot.review.ReviewActivityRepository.CurrentReview;
import com.forgepilot.review.ReviewActivityService.ActivityView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Review activity against real rows: the six-row single-pull-request table of
 * PRD.md 5, the current-validity comparison of ARCHITECTURE.md 3.1 including its
 * null-safe half, and the requirement-level aggregation with its counts.
 *
 * <p>Both matrices are encoded as data and asserted row by row, the same shape
 * {@code RequirementLifecycleTest} uses for the requirement state machine, so a
 * failure names the row that broke rather than the line number.
 */
@SpringBootTest
class ReviewActivityTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String BASE_SHA = "0".repeat(40);
    private static final String HEAD = "1".repeat(40);
    private static final String OTHER_HEAD = "2".repeat(40);
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "b".repeat(64);
    private static final Instant DECIDED_AT = Instant.parse("2026-08-21T12:00:00Z");
    private static final Instant LEASE_UNTIL = Instant.parse("2026-08-21T13:00:00Z");

    /**
     * PRD.md 5's table read forwards. The first row has no Review at all, which is
     * the "nothing matches the current inputs" case; the rest are the reachable
     * {@code (status, decision)} pairs, one per row of the table, with REVIEWING
     * appearing twice because the table gives it two branches.
     */
    private static final List<SingleCase> SINGLE_PULL_REQUEST = List.of(
            new SingleCase(null, null, PullRequestActivity.REVIEW_REQUIRED),
            new SingleCase(ReviewStatus.FAILED, ReviewDecision.PENDING, PullRequestActivity.FAILED),
            new SingleCase(ReviewStatus.COMPLETED, ReviewDecision.REQUEST_CHANGES,
                    PullRequestActivity.CHANGES_REQUESTED),
            new SingleCase(ReviewStatus.RUNNING, ReviewDecision.PENDING, PullRequestActivity.REVIEWING),
            // Finished but still waiting for a person. PRD.md 5 puts this on the
            // REVIEWING row; reading the decision instead of the status would report
            // PENDING here and lose REVIEWING entirely.
            new SingleCase(ReviewStatus.COMPLETED, ReviewDecision.PENDING, PullRequestActivity.REVIEWING),
            new SingleCase(ReviewStatus.PENDING, ReviewDecision.PENDING, PullRequestActivity.PENDING),
            new SingleCase(ReviewStatus.COMPLETED, ReviewDecision.APPROVE, PullRequestActivity.APPROVED));

    /**
     * PRD.md 5's aggregation rule, with the counts written out by hand rather than
     * recomputed from the left column.
     */
    private static final List<AggregateCase> AGGREGATION = List.of(
            new AggregateCase(List.of(PullRequestActivity.APPROVED),
                    RequirementActivity.APPROVED,
                    Map.of(PullRequestActivity.APPROVED, 1)),
            new AggregateCase(List.of(PullRequestActivity.REVIEW_REQUIRED),
                    RequirementActivity.REVIEW_REQUIRED,
                    Map.of(PullRequestActivity.REVIEW_REQUIRED, 1)),
            new AggregateCase(List.of(PullRequestActivity.FAILED, PullRequestActivity.APPROVED),
                    RequirementActivity.FAILED,
                    Map.of(PullRequestActivity.FAILED, 1, PullRequestActivity.APPROVED, 1)),
            new AggregateCase(List.of(PullRequestActivity.FAILED, PullRequestActivity.CHANGES_REQUESTED),
                    RequirementActivity.FAILED,
                    Map.of(PullRequestActivity.FAILED, 1, PullRequestActivity.CHANGES_REQUESTED, 1)),
            // The one everybody gets wrong: a risk state wins before unanimity is
            // ever considered, so this is CHANGES_REQUESTED and not MIXED. MIXED is
            // the residue, not a priority.
            new AggregateCase(List.of(PullRequestActivity.CHANGES_REQUESTED, PullRequestActivity.APPROVED),
                    RequirementActivity.CHANGES_REQUESTED,
                    Map.of(PullRequestActivity.CHANGES_REQUESTED, 1, PullRequestActivity.APPROVED, 1)),
            new AggregateCase(List.of(PullRequestActivity.CHANGES_REQUESTED,
                    PullRequestActivity.REVIEW_REQUIRED, PullRequestActivity.PENDING),
                    RequirementActivity.CHANGES_REQUESTED,
                    Map.of(PullRequestActivity.CHANGES_REQUESTED, 1, PullRequestActivity.REVIEW_REQUIRED, 1,
                            PullRequestActivity.PENDING, 1)),
            new AggregateCase(List.of(PullRequestActivity.APPROVED, PullRequestActivity.APPROVED),
                    RequirementActivity.APPROVED,
                    Map.of(PullRequestActivity.APPROVED, 2)),
            new AggregateCase(List.of(PullRequestActivity.PENDING, PullRequestActivity.PENDING),
                    RequirementActivity.PENDING,
                    Map.of(PullRequestActivity.PENDING, 2)),
            new AggregateCase(List.of(PullRequestActivity.REVIEW_REQUIRED,
                    PullRequestActivity.REVIEW_REQUIRED),
                    RequirementActivity.REVIEW_REQUIRED,
                    Map.of(PullRequestActivity.REVIEW_REQUIRED, 2)),
            // The smallest MIXED there is: two pull requests, two different
            // non-risk states.
            new AggregateCase(List.of(PullRequestActivity.PENDING, PullRequestActivity.APPROVED),
                    RequirementActivity.MIXED,
                    Map.of(PullRequestActivity.PENDING, 1, PullRequestActivity.APPROVED, 1)),
            new AggregateCase(List.of(PullRequestActivity.REVIEW_REQUIRED, PullRequestActivity.REVIEWING,
                    PullRequestActivity.APPROVED),
                    RequirementActivity.MIXED,
                    Map.of(PullRequestActivity.REVIEW_REQUIRED, 1, PullRequestActivity.REVIEWING, 1,
                            PullRequestActivity.APPROVED, 1)));

    @Autowired
    private ReviewActivityService activities;

    @Autowired
    private ReviewActivityRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ------------------------------------------------------- single pull request

    @Test
    void everyRowOfTheSinglePullRequestTableIsReachedByItsOwnData() {
        Fixture fixture = new Fixture();

        for (SingleCase testCase : SINGLE_PULL_REQUEST) {
            long requirement = fixture.requirement();
            long revision = fixture.currentRevision(requirement);
            long pullRequest = fixture.pullRequest(requirement, HEAD, FINGERPRINT);
            if (testCase.status() != null) {
                fixture.review(pullRequest, HEAD, FINGERPRINT, revision, testCase.status(),
                        testCase.decision());
            }

            ActivityView view = activities.forRequirement(fixture.project, fixture.owner, requirement);

            assertThat(view.activity()).as("%s / %s", testCase.status(), testCase.decision())
                    .isEqualTo(RequirementActivity.valueOf(testCase.expected().name()));
            assertThat(view.counts()).as("%s / %s", testCase.status(), testCase.decision())
                    .containsEntry(testCase.expected(), 1);
        }

        // The table above must keep covering the whole domain: dropping a row would
        // otherwise leave a value silently untested.
        assertThat(SINGLE_PULL_REQUEST.stream().map(SingleCase::expected).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(PullRequestActivity.values());
    }

    // ---------------------------------------------------------- current validity

    @Test
    void aNewHeadMakesTheApprovedReviewStale() {
        Fixture fixture = new Fixture();
        long requirement = fixture.requirement();
        long pullRequest = fixture.pullRequest(requirement, HEAD, FINGERPRINT);
        fixture.review(pullRequest, HEAD, FINGERPRINT, fixture.currentRevision(requirement),
                ReviewStatus.COMPLETED, ReviewDecision.APPROVE);
        assertThat(activityOf(fixture, requirement)).isEqualTo(RequirementActivity.APPROVED);

        jdbc.update("update pull_request set head_sha = ? where id = ?", OTHER_HEAD, pullRequest);

        assertThat(activityOf(fixture, requirement)).isEqualTo(RequirementActivity.REVIEW_REQUIRED);
    }

    @Test
    void aChangedDiffFingerprintMakesTheReviewStaleEvenAtTheSameHead() {
        Fixture fixture = new Fixture();
        long requirement = fixture.requirement();
        long pullRequest = fixture.pullRequest(requirement, HEAD, FINGERPRINT);
        fixture.review(pullRequest, HEAD, FINGERPRINT, fixture.currentRevision(requirement),
                ReviewStatus.COMPLETED, ReviewDecision.APPROVE);
        assertThat(activityOf(fixture, requirement)).isEqualTo(RequirementActivity.APPROVED);

        // The base, the changed files or a patch moved. D003 is explicit that this
        // mints a new identity even though the head SHA did not change; comparing
        // only the head would leave this pull request showing APPROVED.
        jdbc.update("update pull_request set review_input_fingerprint = ? where id = ?",
                OTHER_FINGERPRINT, pullRequest);

        assertThat(activityOf(fixture, requirement)).isEqualTo(RequirementActivity.REVIEW_REQUIRED);
    }

    @Test
    void aPublishedRevisionMakesTheReviewStale() {
        Fixture fixture = new Fixture();
        long requirement = fixture.requirement();
        long pullRequest = fixture.pullRequest(requirement, HEAD, FINGERPRINT);
        fixture.review(pullRequest, HEAD, FINGERPRINT, fixture.currentRevision(requirement),
                ReviewStatus.COMPLETED, ReviewDecision.APPROVE);
        assertThat(activityOf(fixture, requirement)).isEqualTo(RequirementActivity.APPROVED);

        fixture.publishRevision(requirement);

        // A requirement change never re-reviews by itself (D007); it just stops the
        // old conclusion from applying, and a person triggers the new one.
        assertThat(activityOf(fixture, requirement)).isEqualTo(RequirementActivity.REVIEW_REQUIRED);
    }

    @Test
    void aPullRequestWithNoRequirementStillFindsItsOwnReview() {
        Fixture fixture = new Fixture();
        long pullRequest = fixture.pullRequest(null, HEAD, FINGERPRINT);
        fixture.review(pullRequest, HEAD, FINGERPRINT, null,
                ReviewStatus.COMPLETED, ReviewDecision.APPROVE);

        CurrentReview row = unassociatedRow(fixture);

        // Both sides of the revision comparison are null here. Written as `=` this
        // would evaluate to unknown, the join would drop the row, and this pull
        // request would report REVIEW_REQUIRED forever no matter how many reviews
        // ran on it.
        assertThat(row.hasCurrentReview())
                .as("a null revision on both sides must still match")
                .isTrue();
        assertThat(ReviewActivityService.activityOf(row)).isEqualTo(PullRequestActivity.APPROVED);
    }

    @Test
    void theNullSafeComparisonIsNotAWildcardInEitherDirection() {
        Fixture fixture = new Fixture();

        // The Review was produced with no requirement context; the pull request has
        // one now. The old conclusion does not carry over.
        long requirement = fixture.requirement();
        long associated = fixture.pullRequest(requirement, HEAD, FINGERPRINT);
        fixture.review(associated, HEAD, FINGERPRINT, null,
                ReviewStatus.COMPLETED, ReviewDecision.APPROVE);
        assertThat(activityOf(fixture, requirement)).isEqualTo(RequirementActivity.REVIEW_REQUIRED);

        // The mirror image: the association was cleared, so a Review that carried a
        // revision is no longer current either.
        long other = fixture.requirement();
        long cleared = fixture.pullRequest(null, HEAD, FINGERPRINT);
        fixture.review(cleared, HEAD, FINGERPRINT, fixture.currentRevision(other),
                ReviewStatus.COMPLETED, ReviewDecision.APPROVE);
        assertThat(ReviewActivityService.activityOf(unassociatedRow(fixture)))
                .isEqualTo(PullRequestActivity.REVIEW_REQUIRED);
    }

    @Test
    void theNewestReviewOnAPullRequestIsNotAutomaticallyTheCurrentOne() {
        Fixture fixture = new Fixture();
        long requirement = fixture.requirement();
        long revision = fixture.currentRevision(requirement);
        long pullRequest = fixture.pullRequest(requirement, HEAD, FINGERPRINT);
        fixture.review(pullRequest, HEAD, FINGERPRINT, revision,
                ReviewStatus.COMPLETED, ReviewDecision.APPROVE);

        // The developer pushes, gets sent back, then force-pushes to the old head.
        jdbc.update("update pull_request set head_sha = ? where id = ?", OTHER_HEAD, pullRequest);
        fixture.review(pullRequest, OTHER_HEAD, FINGERPRINT, revision,
                ReviewStatus.COMPLETED, ReviewDecision.REQUEST_CHANGES);
        assertThat(activityOf(fixture, requirement)).isEqualTo(RequirementActivity.CHANGES_REQUESTED);
        jdbc.update("update pull_request set head_sha = ? where id = ?", HEAD, pullRequest);

        // Old reviews are kept forever and never overwritten, so the pull request
        // now has two of them. Reading the most recent row would answer
        // CHANGES_REQUESTED; the identity match answers with the one that applies.
        assertThat(reviewCountOf(pullRequest)).isEqualTo(2);
        assertThat(activityOf(fixture, requirement)).isEqualTo(RequirementActivity.APPROVED);
    }

    // ------------------------------------------------------------- aggregation

    @Test
    void aRequirementWithNoPullRequestIsNoPr() {
        Fixture fixture = new Fixture();
        long requirement = fixture.requirement();

        ActivityView view = activities.forRequirement(fixture.project, fixture.owner, requirement);

        assertThat(view.activity()).isEqualTo(RequirementActivity.NO_PR);
        assertThat(view.counts().keySet()).containsExactlyInAnyOrder(PullRequestActivity.values());
        assertThat(view.counts().values()).containsOnly(0);
        // No join over pull_request can produce a row for it, so it has to be added
        // from the requirement side or it would be missing from the list page.
        assertThat(activities.forProject(fixture.project, fixture.owner))
                .containsKey(requirement);
        assertThat(activities.forProject(fixture.project, fixture.owner).get(requirement).activity())
                .isEqualTo(RequirementActivity.NO_PR);
    }

    @Test
    void everyRowOfTheAggregationMatrixHolds() {
        Fixture fixture = new Fixture();

        for (AggregateCase testCase : AGGREGATION) {
            long requirement = fixture.requirement();
            long revision = fixture.currentRevision(requirement);
            testCase.subs().forEach(sub -> fixture.pullRequestWith(requirement, revision, sub));

            ActivityView view = activities.forRequirement(fixture.project, fixture.owner, requirement);

            assertThat(view.activity()).as("%s", testCase.subs()).isEqualTo(testCase.expected());
            assertThat(view.counts().keySet()).as("%s", testCase.subs())
                    .containsExactlyInAnyOrder(PullRequestActivity.values());
            assertThat(view.counts()).as("%s", testCase.subs())
                    .containsAllEntriesOf(testCase.expectedCounts());
            // With the listed entries pinned above and the totals equal, every state
            // that is not listed must be zero. Stated as a total rather than as a
            // filtered "the rest are zero", which would assert nothing at all on a
            // case that happened to list all six.
            assertThat(view.counts().values().stream().mapToInt(Integer::intValue).sum())
                    .as("nothing beyond the listed states may be counted for %s", testCase.subs())
                    .isEqualTo(testCase.subs().size());
        }
    }

    @Test
    void anUnassociatedPullRequestEntersNoRequirementsAggregate() {
        Fixture fixture = new Fixture();
        long requirement = fixture.requirement();
        long revision = fixture.currentRevision(requirement);
        fixture.pullRequestWith(requirement, revision, PullRequestActivity.APPROVED);
        fixture.pullRequestWith(requirement, revision, PullRequestActivity.APPROVED);

        // A third pull request in the same project, associated with nothing. It has
        // an activity of its own but belongs to no requirement.
        long loose = fixture.pullRequest(null, HEAD, FINGERPRINT);
        fixture.review(loose, HEAD, FINGERPRINT, null, ReviewStatus.PENDING, ReviewDecision.PENDING);

        ActivityView view = activities.forRequirement(fixture.project, fixture.owner, requirement);

        assertThat(view.activity()).isEqualTo(RequirementActivity.APPROVED);
        assertThat(view.counts()).containsEntry(PullRequestActivity.APPROVED, 2)
                .containsEntry(PullRequestActivity.PENDING, 0);
        assertThat(view.counts().values().stream().mapToInt(Integer::intValue).sum())
                .as("the loose pull request must not be counted")
                .isEqualTo(2);
    }

    // ---------------------------------------------------------- the two levels

    @Test
    void noPrAndMixedExistOnlyAtTheRequirementLevel() {
        assertThat(PullRequestActivity.values()).extracting(Enum::name)
                .containsExactly("REVIEW_REQUIRED", "FAILED", "CHANGES_REQUESTED", "REVIEWING",
                        "PENDING", "APPROVED");

        Set<String> perPullRequest = Arrays.stream(PullRequestActivity.values())
                .map(Enum::name).collect(Collectors.toSet());
        assertThat(Arrays.stream(RequirementActivity.values()).map(Enum::name)
                .filter(name -> !perPullRequest.contains(name)).toList())
                .as("the eight-value list is the union of two levels, not one level's domain")
                .containsExactlyInAnyOrder("NO_PR", "MIXED");

        // The counts are keyed by the six-value type, so the two aggregate-only
        // values cannot appear in a per-state count either. Proven on a real MIXED
        // response rather than on the type alone.
        Fixture fixture = new Fixture();
        long requirement = fixture.requirement();
        long revision = fixture.currentRevision(requirement);
        fixture.pullRequestWith(requirement, revision, PullRequestActivity.PENDING);
        fixture.pullRequestWith(requirement, revision, PullRequestActivity.APPROVED);

        ActivityView view = activities.forRequirement(fixture.project, fixture.owner, requirement);

        assertThat(view.activity()).isEqualTo(RequirementActivity.MIXED);
        assertThat(view.counts().keySet()).extracting(Enum::name)
                .doesNotContain("NO_PR", "MIXED").hasSize(6);
    }

    // -------------------------------------------------------------- isolation

    @Test
    void anotherProjectsActivityIsIndistinguishableFromSomethingThatDoesNotExist() {
        Fixture mine = new Fixture();
        Fixture theirs = new Fixture();
        long myRequirement = mine.requirement();
        long theirRequirement = theirs.requirement();
        long theirRevision = theirs.currentRevision(theirRequirement);
        theirs.pullRequestWith(theirRequirement, theirRevision, PullRequestActivity.APPROVED);
        theirs.pullRequestWith(theirRequirement, theirRevision, PullRequestActivity.APPROVED);
        theirs.pullRequestWith(theirRequirement, theirRevision, PullRequestActivity.FAILED);

        // Their id under my project, and their id under their project as a
        // non-member, answer the same way as an id that was never issued.
        assertNotFound(() -> activities.forRequirement(mine.project, mine.owner, theirRequirement));
        assertNotFound(() -> activities.forRequirement(theirs.project, mine.owner, theirRequirement));
        assertNotFound(() -> activities.forProject(theirs.project, mine.owner));
        assertNotFound(() -> activities.forRequirement(mine.project, mine.owner, theirRequirement + 9_000));

        // Their three pull requests change nothing on my side.
        assertThat(activities.forProject(mine.project, mine.owner)).containsOnlyKeys(myRequirement);
        assertThat(activities.forRequirement(mine.project, mine.owner, myRequirement).activity())
                .isEqualTo(RequirementActivity.NO_PR);
    }

    // ------------------------------------------------------------------- http

    @Test
    void bothEndpointsAnswerWithTheFrozenShape() throws Exception {
        Fixture fixture = new Fixture();
        long requirement = fixture.requirement();
        long revision = fixture.currentRevision(requirement);
        fixture.pullRequestWith(requirement, revision, PullRequestActivity.PENDING);
        fixture.pullRequestWith(requirement, revision, PullRequestActivity.APPROVED);
        long untouched = fixture.requirement();

        JsonNode single = read(fixture, "/api/projects/" + fixture.project
                + "/requirements/" + requirement + "/review-activity");

        assertThat(single.path("activity").asString()).isEqualTo("MIXED");
        JsonNode counts = single.path("counts");
        for (PullRequestActivity value : PullRequestActivity.values()) {
            assertThat(counts.has(value.name())).as("counts must always carry %s", value).isTrue();
        }
        assertThat(counts.size()).isEqualTo(6);
        assertThat(counts.path("PENDING").asInt()).isEqualTo(1);
        assertThat(counts.path("APPROVED").asInt()).isEqualTo(1);
        assertThat(counts.path("REVIEW_REQUIRED").asInt()).isZero();

        JsonNode all = read(fixture, "/api/projects/" + fixture.project + "/review-activity");

        assertThat(all.path(String.valueOf(requirement)).path("activity").asString()).isEqualTo("MIXED");
        assertThat(all.path(String.valueOf(untouched)).path("activity").asString()).isEqualTo("NO_PR");
        assertThat(all.path(String.valueOf(untouched)).path("counts").size()).isEqualTo(6);

        // A member of another project sees the same answer as for a project that
        // does not exist.
        Fixture outsider = new Fixture();
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/projects/" + fixture.project + "/review-activity")
                        .with(user(outsider.username)))
                .andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders
                        .get("/api/projects/" + fixture.project + "/requirements/" + requirement
                                + "/review-activity")
                        .with(user(outsider.username)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private RequirementActivity activityOf(Fixture fixture, long requirement) {
        return activities.forRequirement(fixture.project, fixture.owner, requirement).activity();
    }

    private CurrentReview unassociatedRow(Fixture fixture) {
        List<CurrentReview> rows = repository.ofProject(fixture.project).stream()
                .filter(row -> row.requirementId() == null)
                .toList();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private int reviewCountOf(long pullRequest) {
        return jdbc.queryForObject("select count(*) from review where pull_request_id = ?",
                Integer.class, pullRequest);
    }

    private void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private JsonNode read(Fixture fixture, String path) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get(path).with(user(fixture.username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private record SingleCase(ReviewStatus status, ReviewDecision decision, PullRequestActivity expected) {
    }

    private record AggregateCase(List<PullRequestActivity> subs, RequirementActivity expected,
            Map<PullRequestActivity, Integer> expectedCounts) {
    }

    /** One project with a LEADER, an SCM repository, and rows written straight to SQL. */
    private final class Fixture {

        private final long owner;
        private final long project;
        private final long repository;
        private final String username;
        private int nextPullRequestNumber = 1;

        private Fixture() {
            int sequence = SEQUENCE.incrementAndGet();
            this.username = "activity-user-" + sequence;
            this.owner = jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'x') returning id",
                    Long.class, username);
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "activity-project-" + sequence, owner);
            jdbc.update("with member as (insert into project_member (project_id, user_id) values (?, ?) returning project_id, user_id) insert into project_member_role (project_id, user_id, role) select project_id, user_id, 'LEADER' from member",
                    project, owner);
            this.repository = jdbc.queryForObject("""
                    insert into scm_repository (project_id, provider, instance_identity, external_id,
                            api_base, encrypted_token, encrypted_secret)
                     values (?, 'GITHUB', ?, ?, 'https://example.invalid', 'token', 'secret') returning id
                    """, Long.class, project, "activity-" + sequence + ".example", "repo-" + sequence);
        }

        private long requirement() {
            long id = jdbc.queryForObject(
                    "insert into requirement (project_id, status) values (?, 'IN_DEVELOPMENT') returning id",
                    Long.class, project);
            publishRevision(id);
            return id;
        }

        /** A new immutable revision, promoted to the requirement's current one. */
        private long publishRevision(long requirement) {
            int seq = jdbc.queryForObject(
                    "select coalesce(max(seq), 0) + 1 from requirement_revision where requirement_id = ?",
                    Integer.class, requirement);
            long revision = jdbc.queryForObject("""
                    insert into requirement_revision (project_id, requirement_id, seq, title, created_by)
                     values (?, ?, ?, ?, ?) returning id
                    """, Long.class, project, requirement, seq, "Requirement " + requirement, owner);
            jdbc.update("update requirement set current_revision_id = ? where id = ?", revision, requirement);
            return revision;
        }

        private long currentRevision(long requirement) {
            return jdbc.queryForObject("select current_revision_id from requirement where id = ?",
                    Long.class, requirement);
        }

        private long pullRequest(Long requirement, String head, String fingerprint) {
            return jdbc.queryForObject("""
                    insert into pull_request (project_id, repository_id, external_number, base_sha,
                            head_sha, review_input_fingerprint, changed_files, requirement_id,
                            author_external_user_id, author_username)
                     values (?, ?, ?, ?, ?, ?, '[]'::jsonb, ?, '4242', 'octocat') returning id
                    """, Long.class, project, repository, nextPullRequestNumber++, BASE_SHA, head,
                    fingerprint, requirement);
        }

        private long review(long pullRequest, String head, String fingerprint, Long revision,
                ReviewStatus status, ReviewDecision decision) {
            Long requirement = revision == null ? null : jdbc.queryForObject(
                    "select requirement_id from requirement_revision where id = ?", Long.class, revision);
            boolean claimed = status == ReviewStatus.RUNNING;
            boolean decided = decision != ReviewDecision.PENDING;
            // ck_review_decision_fields wants an actor and a time on a final
            // decision, and ck_review_running_is_leased wants the fencing pair on a
            // claimed one. Neither is what this test is about; both have to be real.
            Long decisionBy = decided ? Long.valueOf(owner) : null;
            Timestamp decisionAt = decided ? Timestamp.from(DECIDED_AT) : null;
            UUID token = claimed ? UUID.randomUUID() : null;
            Timestamp leaseUntil = claimed ? Timestamp.from(LEASE_UNTIL) : null;
            return jdbc.queryForObject("""
                    insert into review (project_id, pull_request_id, head_sha, review_input_fingerprint,
                            requirement_id, requirement_revision_id, status, decision, decision_by,
                            decision_at, execution_attempt, execution_token, lease_until)
                     values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?) returning id
                    """, Long.class, project, pullRequest, head, fingerprint, requirement, revision,
                    status.name(), decision.name(), decisionBy, decisionAt, token, leaseUntil);
        }

        /**
         * The smallest data that produces each activity, taken from the worked
         * example in {@code research/review-activity-matrix.md} 3.3. The switch is
         * exhaustive on purpose: a seventh single-pull-request value would stop the
         * test compiling rather than silently go untested.
         */
        private void pullRequestWith(long requirement, long revision, PullRequestActivity activity) {
            long pullRequest = pullRequest(requirement, HEAD, FINGERPRINT);
            switch (activity) {
                case REVIEW_REQUIRED -> {
                    // Nothing to write: no Review matches the current inputs.
                }
                case FAILED -> review(pullRequest, HEAD, FINGERPRINT, revision,
                        ReviewStatus.FAILED, ReviewDecision.PENDING);
                case CHANGES_REQUESTED -> review(pullRequest, HEAD, FINGERPRINT, revision,
                        ReviewStatus.COMPLETED, ReviewDecision.REQUEST_CHANGES);
                case REVIEWING -> review(pullRequest, HEAD, FINGERPRINT, revision,
                        ReviewStatus.RUNNING, ReviewDecision.PENDING);
                case PENDING -> review(pullRequest, HEAD, FINGERPRINT, revision,
                        ReviewStatus.PENDING, ReviewDecision.PENDING);
                case APPROVED -> review(pullRequest, HEAD, FINGERPRINT, revision,
                        ReviewStatus.COMPLETED, ReviewDecision.APPROVE);
            }
        }
    }
}
