package com.forgepilot.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectMemberService;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The requirement lifecycle as the services own it: the three-step create, the
 * DRAFT window, the frozen revision after it, acceptance criterion identity,
 * the state machine, project isolation and the role matrix. Everything the
 * database already proves is asserted in {@code DatabaseConstraintTest}
 * instead, one layer below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RequirementLifecycleTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private RequirementService requirements;

    @Autowired
    private RequirementRevisionRepository revisionRepository;

    @Autowired
    private AcceptanceCriterionRepository criterionRepository;

    @Autowired
    private ProjectService projects;

    @Autowired
    private ProjectMemberService members;

    @Autowired
    private JdbcTemplate jdbc;

    // -------------------------------------------------------------------- create

    @Test
    void createWritesRevisionOneWithItsCriteriaAndBackfillsTheCurrentRevision() {
        Team team = team();

        RequirementDetail created = create(team, "Login with a local account",
                "A wrong password is rejected", "The session expires");

        assertThat(created.status()).isEqualTo(RequirementStatus.DRAFT);
        assertThat(created.assigneeId()).isNull();
        assertThat(created.currentRevision().seq()).isEqualTo(1);
        assertThat(created.currentRevision().changeReason()).isNull();
        assertThat(created.currentRevision().createdByUsername()).isEqualTo(usernameOf(team.leader()));
        assertThat(created.currentRevision().acceptanceCriteria())
                .extracting(AcceptanceCriterionView::acKey, AcceptanceCriterionView::sortOrder,
                        AcceptanceCriterionView::text)
                .containsExactly(tuple("AC-1", 1, "A wrong password is rejected"),
                        tuple("AC-2", 2, "The session expires"));
        // The pointer is not just assembled into the response: the backfill committed.
        assertThat(currentRevisionIdOf(created.id())).isEqualTo(created.currentRevision().id());
    }

    // --------------------------------------------------------------- draft window

    @Test
    void editingADraftRewritesRevisionOneAndClearsItsQualityResult() {
        Team team = team();
        RequirementDetail created = create(team, "Login", "A wrong password is rejected");
        long revisionId = created.currentRevision().id();
        seedQualityResult(revisionId);

        RequirementDetail edited = requirements.editDraft(team.projectId(), team.leader(), created.id(),
                new RequirementContent("Login with a local account", "Users have no account yet", "Details",
                        List.of(new CriterionInput("AC-1", "A wrong password is rejected twice"),
                                new CriterionInput(null, "The session expires"))));

        assertThat(edited.currentRevision().id()).isEqualTo(revisionId);
        assertThat(edited.currentRevision().seq()).isEqualTo(1);
        assertThat(edited.currentRevision().title()).isEqualTo("Login with a local account");
        assertThat(edited.currentRevision().acceptanceCriteria())
                .extracting(AcceptanceCriterionView::acKey, AcceptanceCriterionView::text)
                .containsExactly(tuple("AC-1", "A wrong password is rejected twice"),
                        tuple("AC-2", "The session expires"));
        assertThat(qualityColumnsOf(revisionId)).containsOnlyNulls();
        assertThat(revisionRepository.findByProjectIdAndRequirementIdOrderBySeqAsc(
                team.projectId(), created.id())).hasSize(1);
    }

    // ------------------------------------------------------------ frozen revision

    @Test
    void onceTheRequirementLeavesDraftTheRevisionIsFrozen() {
        Team team = team();
        RequirementDetail created = create(team, "Login", "A wrong password is rejected");
        requirements.changeStatus(team.projectId(), team.leader(), created.id(), RequirementStatus.READY);

        assertThat(statusOf(() -> requirements.editDraft(team.projectId(), team.leader(), created.id(),
                content("Renamed behind the freeze", "Rewritten")))).isEqualTo(HttpStatus.CONFLICT);

        RevisionView current = requirements.get(team.projectId(), team.leader(), created.id()).currentRevision();
        assertThat(current.id()).isEqualTo(created.currentRevision().id());
        assertThat(current.title()).isEqualTo("Login");
        assertThat(current.acceptanceCriteria()).extracting(AcceptanceCriterionView::text)
                .containsExactly("A wrong password is rejected");
    }

    @Test
    void publishingARevisionRequiresAChangeReason() {
        Team team = team();
        long requirementId = readyRequirement(team, "Login", "A wrong password is rejected");

        assertThat(statusOf(() -> requirements.publishRevision(team.projectId(), team.leader(), requirementId,
                content("Login", "Reworded"), null))).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf(() -> requirements.publishRevision(team.projectId(), team.leader(), requirementId,
                content("Login", "Reworded"), "  "))).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(revisionRepository.findByProjectIdAndRequirementIdOrderBySeqAsc(
                team.projectId(), requirementId)).hasSize(1);
    }

    @Test
    void publishingLeavesTheSupersededRevisionAndItsCriteriaReadable() {
        Team team = team();
        long requirementId = readyRequirement(team, "Login", "A wrong password is rejected");
        long firstRevision = currentRevisionIdOf(requirementId);

        RequirementDetail published = requirements.publishRevision(team.projectId(), team.leader(),
                requirementId, new RequirementContent("Login with a local account", null, null,
                        List.of(new CriterionInput("AC-1", "A wrong password is rejected twice"))),
                "The reviewer asked for a sharper wording");

        assertThat(published.currentRevision().seq()).isEqualTo(2);
        assertThat(published.currentRevision().id()).isNotEqualTo(firstRevision);
        assertThat(currentRevisionIdOf(requirementId)).isEqualTo(published.currentRevision().id());

        List<RevisionView> history = requirements.listRevisions(team.projectId(), team.leader(), requirementId);
        assertThat(history).extracting(RevisionView::seq, RevisionView::title, RevisionView::changeReason)
                .containsExactly(tuple(1, "Login", null),
                        tuple(2, "Login with a local account", "The reviewer asked for a sharper wording"));
        assertThat(history.get(0).acceptanceCriteria())
                .extracting(AcceptanceCriterionView::acKey, AcceptanceCriterionView::text)
                .containsExactly(tuple("AC-1", "A wrong password is rejected"));
    }

    // ------------------------------------------------------ acceptance criteria

    @Test
    void acceptanceCriterionKeysSurviveNewRevisionsReorderingAndRetirement() {
        Team team = team();
        long requirementId = readyRequirement(team, "Login", "First", "Second");

        // Reordered, one edited, one added: the keys follow the criteria, not the order.
        RequirementDetail second = requirements.publishRevision(team.projectId(), team.leader(), requirementId,
                new RequirementContent("Login", null, null, List.of(
                        new CriterionInput("AC-2", "Second"),
                        new CriterionInput("AC-1", "First, reworded"),
                        new CriterionInput(null, "Third"))),
                "Reordered and extended");
        assertThat(second.currentRevision().acceptanceCriteria())
                .extracting(AcceptanceCriterionView::acKey, AcceptanceCriterionView::sortOrder,
                        AcceptanceCriterionView::text)
                .containsExactly(tuple("AC-2", 1, "Second"), tuple("AC-1", 2, "First, reworded"),
                        tuple("AC-3", 3, "Third"));

        // AC-3 is retired here; its number must never come back.
        RequirementDetail third = requirements.publishRevision(team.projectId(), team.leader(), requirementId,
                new RequirementContent("Login", null, null, List.of(
                        new CriterionInput("AC-1", "First, reworded"),
                        new CriterionInput(null, "Fourth"))),
                "Dropped the third criterion");
        assertThat(third.currentRevision().acceptanceCriteria())
                .extracting(AcceptanceCriterionView::acKey)
                .containsExactly("AC-1", "AC-4");

        assertThat(statusOf(() -> requirements.publishRevision(team.projectId(), team.leader(), requirementId,
                new RequirementContent("Login", null, null, List.of(new CriterionInput("AC-99", "Foreign"))),
                "Key from nowhere"))).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ------------------------------------------------------- assignment and state

    @Test
    void theFirstAssignmentMovesAReadyRequirementIntoDevelopmentAndAReassignmentDoesNot() {
        Team team = team();
        long requirementId = readyRequirement(team, "Login", "A wrong password is rejected");

        RequirementDetail assigned = requirements.assign(team.projectId(), team.leader(), requirementId,
                team.developer());
        assertThat(assigned.status()).isEqualTo(RequirementStatus.IN_DEVELOPMENT);
        assertThat(assigned.assigneeId()).isEqualTo(team.developer());
        assertThat(assigned.assigneeUsername()).isEqualTo(usernameOf(team.developer()));

        RequirementDetail reassigned = requirements.assign(team.projectId(), team.leader(), requirementId,
                team.reviewer());
        assertThat(reassigned.status()).isEqualTo(RequirementStatus.IN_DEVELOPMENT);
        assertThat(reassigned.assigneeId()).isEqualTo(team.reviewer());
    }

    @Test
    void everyIllegalStatusTransitionIsRefused() {
        Team team = team();
        long draft = create(team, "Draft", "A criterion").id();
        long ready = readyRequirement(team, "Ready", "A criterion");
        long inDevelopment = inDevelopmentRequirement(team);
        long done = inDevelopmentRequirement(team);
        requirements.changeStatus(team.projectId(), team.leader(), done, RequirementStatus.DONE);
        long canceled = create(team, "Canceled", "A criterion").id();
        requirements.changeStatus(team.projectId(), team.leader(), canceled, RequirementStatus.CANCELED);

        refuse(team, draft, RequirementStatus.DRAFT, RequirementStatus.IN_DEVELOPMENT, RequirementStatus.DONE);
        refuse(team, ready, RequirementStatus.DRAFT, RequirementStatus.READY,
                RequirementStatus.IN_DEVELOPMENT, RequirementStatus.DONE);
        refuse(team, inDevelopment, RequirementStatus.DRAFT, RequirementStatus.READY,
                RequirementStatus.IN_DEVELOPMENT);
        refuse(team, done, RequirementStatus.values());
        refuse(team, canceled, RequirementStatus.values());
    }

    @Test
    void cancelIsReachableFromEveryNonTerminalStateAndIsFinal() {
        Team team = team();
        long fromDraft = create(team, "From draft", "A criterion").id();
        long fromReady = readyRequirement(team, "From ready", "A criterion");
        long fromDevelopment = inDevelopmentRequirement(team);

        for (long requirementId : List.of(fromDraft, fromReady, fromDevelopment)) {
            assertThat(requirements.changeStatus(team.projectId(), team.leader(), requirementId,
                    RequirementStatus.CANCELED).status())
                    .as("cancel %s", requirementId).isEqualTo(RequirementStatus.CANCELED);
            assertThat(statusOf(() -> requirements.publishRevision(team.projectId(), team.leader(),
                    requirementId, content("Revived", "A criterion"), "Trying to revive")))
                    .as("publish onto canceled %s", requirementId).isEqualTo(HttpStatus.CONFLICT);
            assertThat(statusOf(() -> requirements.assign(team.projectId(), team.leader(), requirementId,
                    team.developer())))
                    .as("assign canceled %s", requirementId).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ----------------------------------------------------------------- isolation

    @Test
    void anotherProjectsRequirementIsIndistinguishableFromOneThatDoesNotExist() {
        Team mine = team();
        Team theirs = team();
        long foreign = create(theirs, "Hidden", "A criterion").id();
        long foreignRevision = currentRevisionIdOf(foreign);
        long missing = foreign + 100_000L;

        for (long requirementId : List.of(foreign, missing)) {
            assertThat(statusOf(() -> requirements.get(mine.projectId(), mine.leader(), requirementId)))
                    .as("get %s", requirementId).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(statusOf(() -> requirements.listRevisions(mine.projectId(), mine.leader(), requirementId)))
                    .as("revisions of %s", requirementId).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(statusOf(() -> requirements.editDraft(mine.projectId(), mine.leader(), requirementId,
                    content("Stolen", "A criterion"))))
                    .as("edit %s", requirementId).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(statusOf(() -> requirements.changeStatus(mine.projectId(), mine.leader(), requirementId,
                    RequirementStatus.READY)))
                    .as("status of %s", requirementId).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(statusOf(() -> requirements.assign(mine.projectId(), mine.leader(), requirementId,
                    mine.developer())))
                    .as("assign %s", requirementId).isEqualTo(HttpStatus.NOT_FOUND);
        }

        // The revision and its criteria are unreachable through the other project too:
        // every read is scoped by projectId rather than checked after the fact.
        assertThat(revisionRepository.findByProjectIdAndRequirementIdOrderBySeqAsc(mine.projectId(), foreign))
                .isEmpty();
        assertThat(criterionRepository.findByProjectIdAndRequirementRevisionIdOrderBySortOrderAsc(
                mine.projectId(), foreignRevision)).isEmpty();
        assertThat(requirements.list(mine.projectId(), mine.leader())).isEmpty();
    }

    // ---------------------------------------------------------------- role matrix

    @Test
    void onlyTheLeaderMayCreateEditPublishAdvanceOrAssign() {
        Team team = team();
        long draft = create(team, "Draft", "A criterion").id();
        long ready = readyRequirement(team, "Ready", "A criterion");

        for (long actor : List.of(team.developer(), team.reviewer())) {
            assertThat(statusOf(() -> requirements.create(team.projectId(), actor, content("Theirs", "A criterion"))))
                    .as("create as %s", actor).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(statusOf(() -> requirements.editDraft(team.projectId(), actor, draft,
                    content("Theirs", "A criterion"))))
                    .as("edit as %s", actor).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(statusOf(() -> requirements.changeStatus(team.projectId(), actor, draft,
                    RequirementStatus.READY)))
                    .as("set READY as %s", actor).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(statusOf(() -> requirements.publishRevision(team.projectId(), actor, ready,
                    content("Theirs", "A criterion"), "Because I can")))
                    .as("publish as %s", actor).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(statusOf(() -> requirements.assign(team.projectId(), actor, ready, actor)))
                    .as("assign as %s", actor).isEqualTo(HttpStatus.FORBIDDEN);
        }

        // Reading stays open to every member.
        assertThat(requirements.list(team.projectId(), team.developer())).hasSize(2);
        assertThat(requirements.get(team.projectId(), team.reviewer(), draft).currentRevision().seq())
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------- helpers

    /** A project with one LEADER, one DEVELOPER and one REVIEWER. */
    private record Team(long projectId, long leader, long developer, long reviewer) {
    }

    private Team team() {
        long leader = account();
        long developer = account();
        long reviewer = account();
        long projectId = projects.create("Requirements " + SEQUENCE.incrementAndGet(), leader).id();
        members.add(projectId, leader, usernameOf(developer), ProjectRole.DEVELOPER);
        members.add(projectId, leader, usernameOf(reviewer), ProjectRole.REVIEWER);
        return new Team(projectId, leader, developer, reviewer);
    }

    private RequirementDetail create(Team team, String title, String... criteria) {
        return requirements.create(team.projectId(), team.leader(), content(title, criteria));
    }

    private long readyRequirement(Team team, String title, String... criteria) {
        long requirementId = create(team, title, criteria).id();
        requirements.changeStatus(team.projectId(), team.leader(), requirementId, RequirementStatus.READY);
        return requirementId;
    }

    private long inDevelopmentRequirement(Team team) {
        long requirementId = readyRequirement(team, "In development", "A criterion");
        requirements.assign(team.projectId(), team.leader(), requirementId, team.developer());
        return requirementId;
    }

    private static RequirementContent content(String title, String... criteria) {
        return new RequirementContent(title, null, null,
                Arrays.stream(criteria).map(text -> new CriterionInput(null, text)).toList());
    }

    /** Asserts one refusal per transition, so a failure names the exact pair. */
    private void refuse(Team team, long requirementId, RequirementStatus... targets) {
        RequirementStatus from = storedStatusOf(requirementId);
        for (RequirementStatus target : targets) {
            assertThat(statusOf(() -> requirements.changeStatus(team.projectId(), team.leader(), requirementId,
                    target))).as("%s -> %s", from, target).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
        assertThat(storedStatusOf(requirementId)).as("%s survived the refusals", from).isEqualTo(from);
    }

    private void seedQualityResult(long revisionId) {
        jdbc.update("update requirement_revision set quality_json = '{\"score\": 42}'::jsonb, "
                + "quality_version = 'q1', quality_checked_at = now() where id = ?", revisionId);
        // Without a real seed the clearing assertion would pass on its own.
        assertThat(qualityColumnsOf(revisionId)).doesNotContainNull();
    }

    private List<Object> qualityColumnsOf(long revisionId) {
        return jdbc.queryForObject("select quality_json, quality_version, quality_checked_at "
                        + "from requirement_revision where id = ?",
                (rs, row) -> Arrays.asList(rs.getObject(1), rs.getObject(2), rs.getObject(3)), revisionId);
    }

    private long currentRevisionIdOf(long requirementId) {
        Long value = jdbc.queryForObject("select current_revision_id from requirement where id = ?",
                Long.class, requirementId);
        assertThat(value).isNotNull();
        return value;
    }

    private RequirementStatus storedStatusOf(long requirementId) {
        return RequirementStatus.valueOf(jdbc.queryForObject(
                "select status from requirement where id = ?", String.class, requirementId));
    }

    private long account() {
        String username = "requirement-user-" + SEQUENCE.incrementAndGet();
        Long id = jdbc.queryForObject(
                "insert into user_account (username, password_hash) values (?, 'bcrypt-placeholder') "
                        + "returning id", Long.class, username);
        assertThat(id).isNotNull();
        return id;
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject("select username from user_account where id = ?", String.class, userId);
    }

    /** The status the API would return, or null when the call was allowed to succeed. */
    private static HttpStatus statusOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException exception) {
            return exception.getStatus();
        }
    }
}
