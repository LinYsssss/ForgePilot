package com.forgepilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.requirement.Requirement;
import com.forgepilot.requirement.RequirementRevision;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 证明批次 1 的那些约束是由 PostgreSQL 自己强制的，而不是由某个未来调用方
 * 可以绕开的服务层检查强制的。这里的每一次拒绝，都是直接通过 JdbcTemplate
 * 写入、绕到所有应用代码之下产生的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DatabaseConstraintTest extends PostgresTestBase {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @PersistenceContext
    private EntityManager entityManager;

    // ---------------------------------------------------------------- 成员

    @Test
    void atMostOneLeaderPerProject() {
        long owner = insertUser();
        long other = insertUser();
        long project = insertProject(owner);
        insertMember(project, owner, "LEADER");

        assertThat(sqlStateOf(() -> insertMember(project, other, "LEADER")))
                .isEqualTo(UNIQUE_VIOLATION);

        insertMember(project, other, "DEVELOPER");
        assertThat(leaderCount(project)).isEqualTo(1);
    }

    @Test
    void everyProjectMayHaveItsOwnLeader() {
        long owner = insertUser();
        long first = insertProject(owner);
        long second = insertProject(owner);

        insertMember(first, owner, "LEADER");
        insertMember(second, owner, "LEADER");

        assertThat(leaderCount(first)).isEqualTo(1);
        assertThat(leaderCount(second)).isEqualTo(1);
    }

    @Test
    void leaderTransferMustDemoteBeforeItPromotes() {
        long owner = insertUser();
        long successor = insertUser();
        long project = insertProject(owner);
        insertMember(project, owner, "LEADER");
        insertMember(project, successor, "DEVELOPER");

        // 先升级会与仍然在位的 LEADER 撞车：这正是 D013.8 禁止用单条 CASE 语句、
        // 并要求「降级 -> flush -> 升级」的原因。
        assertThat(sqlStateOf(() -> setRole(project, successor, "LEADER")))
                .isEqualTo(UNIQUE_VIOLATION);

        setRole(project, owner, "DEVELOPER");
        setRole(project, successor, "LEADER");

        assertThat(leaderCount(project)).isEqualTo(1);
        assertThat(roleOf(project, successor)).isEqualTo("LEADER");
    }

    @Test
    void oneMembershipPerUserPerProject() {
        long user = insertUser();
        long project = insertProject(user);
        insertMember(project, user, "LEADER");

        assertThat(sqlStateOf(() -> insertMember(project, user, "DEVELOPER")))
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    void unknownRoleIsRejected() {
        long user = insertUser();
        long project = insertProject(user);

        assertThat(sqlStateOf(() -> insertMember(project, user, "ADMIN")))
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void verifiedScmIdentityBelongsToOneUserAndCanBeReusedAcrossTheirProjects() {
        long owner = insertUser();
        long developer = insertUser();
        long project = insertProject(owner);
        long otherProject = insertProject(owner);
        insertMember(project, owner, "LEADER");
        insertMember(project, developer, "DEVELOPER");
        insertMember(otherProject, owner, "LEADER");

        long identity = insertScmIdentity(owner, "gh-1");
        bindScmIdentity(project, owner, identity);
        assertThat(sqlStateOf(() -> insertScmIdentity(developer, "gh-1")))
                .isEqualTo(UNIQUE_VIOLATION);

        bindScmIdentity(otherProject, owner, identity);

        // 没有活动绑定的成员仍然是合法项目成员。
        assertThat(unconfiguredIdentityCount(project)).isEqualTo(1);
    }

    // ----------------------------------------------------------- 需求

    @Test
    void requirementIsCreatedWithoutARevisionAndBackfilledAfterwards() {
        long author = insertUser();
        long project = insertProject(author);
        insertMember(project, author, "LEADER");

        // 第 1 步：current_revision_id 为 NULL，因此 MATCH SIMPLE 会完全跳过
        // 那个复合外键——正是这一点让这个循环无需 DEFERRABLE 就能解开。
        long requirement = insertRequirement(project);
        assertThat(currentRevisionOf(requirement)).isNull();

        long revision = insertRevision(project, requirement, 1, author);
        backfillCurrentRevision(requirement, revision);

        assertThat(currentRevisionOf(requirement)).isEqualTo(revision);
    }

    @Test
    void currentRevisionMustBelongToTheSameRequirementAndProject() {
        long author = insertUser();
        long project = insertProject(author);
        long otherProject = insertProject(author);
        insertMember(project, author, "LEADER");
        insertMember(otherProject, author, "LEADER");

        long requirement = insertRequirement(project);
        long sibling = insertRequirement(project);
        long foreign = insertRequirement(otherProject);

        insertRevision(project, requirement, 1, author);
        long siblingRevision = insertRevision(project, sibling, 1, author);
        long foreignRevision = insertRevision(otherProject, foreign, 1, author);

        assertThat(sqlStateOf(() -> backfillCurrentRevision(requirement, siblingRevision)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);
        assertThat(sqlStateOf(() -> backfillCurrentRevision(requirement, foreignRevision)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    void revisionCannotBeParentedByAnotherProjectsRequirement() {
        long author = insertUser();
        long project = insertProject(author);
        long otherProject = insertProject(author);
        long requirement = insertRequirement(project);

        assertThat(sqlStateOf(() -> insertRevision(otherProject, requirement, 1, author)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    void revisionSequenceIsUniquePerRequirement() {
        long author = insertUser();
        long project = insertProject(author);
        long requirement = insertRequirement(project);
        insertRevision(project, requirement, 1, author);

        assertThat(sqlStateOf(() -> insertRevision(project, requirement, 1, author)))
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    void assigneeMustBeAMemberOfTheSameProject() {
        long author = insertUser();
        long stranger = insertUser();
        long project = insertProject(author);
        long otherProject = insertProject(author);
        insertMember(project, author, "LEADER");
        insertMember(otherProject, stranger, "LEADER");

        long requirement = insertRequirement(project);

        assertThat(sqlStateOf(() -> setAssignee(requirement, stranger)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);

        setAssignee(requirement, author);
        assertThat(assigneeOf(requirement)).isEqualTo(author);
    }

    @Test
    void unknownRequirementStatusIsRejected() {
        long author = insertUser();
        long project = insertProject(author);
        long requirement = insertRequirement(project);

        assertThat(sqlStateOf(() -> jdbc.update(
                "update requirement set status = 'ARCHIVED' where id = ?", requirement)))
                .isEqualTo(CHECK_VIOLATION);
    }

    // ------------------------------------------------------ 验收条件

    @Test
    void acceptanceCriterionKeyIsUniqueWithinARevisionAndStableAcrossThem() {
        long author = insertUser();
        long project = insertProject(author);
        long requirement = insertRequirement(project);
        long first = insertRevision(project, requirement, 1, author);
        long second = insertRevision(project, requirement, 2, author);

        insertAcceptanceCriterion(project, first, "AC-1", 1);
        assertThat(sqlStateOf(() -> insertAcceptanceCriterion(project, first, "AC-1", 2)))
                .isEqualTo(UNIQUE_VIOLATION);

        // 同一个业务 key 被带进下一个修订之后，是另一行记录。
        insertAcceptanceCriterion(project, second, "AC-1", 1);
        assertThat(acKeysOf(second)).containsExactly("AC-1");
    }

    @Test
    void acceptanceCriterionCannotPointAtAnotherProjectsRevision() {
        long author = insertUser();
        long project = insertProject(author);
        long otherProject = insertProject(author);
        long requirement = insertRequirement(project);
        long revision = insertRevision(project, requirement, 1, author);

        assertThat(sqlStateOf(() -> insertAcceptanceCriterion(otherProject, revision, "AC-1", 1)))
                .isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    // ----------------------------------------------------------- 实体映射

    @Test
    void hibernateWritesTheThreeStepBackfillAndReadsTheRevisionBack() {
        long author = insertUser();
        long project = insertProject(author);
        insertMember(project, author, "LEADER");

        Long requirementId = transactions.execute(status -> {
            Requirement requirement = new Requirement(project);
            entityManager.persist(requirement);

            RequirementRevision revision = new RequirementRevision(
                    project, requirement.getId(), 1, "Login with a local account",
                    "background", "description", author, null);
            entityManager.persist(revision);

            requirement.setCurrentRevisionId(revision.getId());
            return requirement.getId();
        });

        // 那个只读关联（D013.1 方案 A）解析了三列外键，
        // 却从不通过它写入 project_id 或 id。
        String title = transactions.execute(status ->
                entityManager.find(Requirement.class, requirementId).getCurrentRevision().getTitle());

        assertThat(title).isEqualTo("Login with a local account");
    }

    @Test
    void editingADraftRevisionClearsItsQualityResult() {
        long author = insertUser();
        long project = insertProject(author);
        long requirement = insertRequirement(project);
        long revision = insertRevision(project, requirement, 1, author);
        jdbc.update("update requirement_revision set quality_json = '{\"score\":42}'::jsonb, "
                + "quality_version = 'v1', quality_checked_at = now() where id = ?", revision);

        transactions.executeWithoutResult(status ->
                entityManager.find(RequirementRevision.class, revision)
                        .editProse("new title", "new background", "new description"));

        assertThat(jdbc.queryForMap(
                "select quality_json, quality_version, quality_checked_at "
                        + "from requirement_revision where id = ?", revision))
                .containsOnlyKeys("quality_json", "quality_version", "quality_checked_at")
                .allSatisfy((column, value) -> assertThat(value).isNull());
    }

    // ------------------------------------------------------------------ 辅助方法

    private long insertUser() {
        String username = "user-" + SEQUENCE.incrementAndGet();
        return requireId(jdbc.queryForObject(
                "insert into user_account (username, display_name, password_hash) values (?, 'Test User', 'bcrypt-placeholder') "
                        + "returning id", Long.class, username));
    }

    private long insertProject(long createdBy) {
        return requireId(jdbc.queryForObject(
                "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                Long.class, "project-" + SEQUENCE.incrementAndGet(), createdBy));
    }

    private long insertMember(long projectId, long userId, String role) {
        return requireId(jdbc.queryForObject(
                "with member as (insert into project_member (project_id, user_id) values (?, ?) "
                        + "returning id, project_id, user_id), assigned as (insert into project_member_role "
                        + "(project_id, user_id, role) select project_id, user_id, ? from member) "
                        + "select id from member",
                Long.class, projectId, userId, role));
    }

    private long insertRequirement(long projectId) {
        return requireId(jdbc.queryForObject(
                "insert into requirement (project_id, status) values (?, 'DRAFT') returning id",
                Long.class, projectId));
    }

    private long insertRevision(long projectId, long requirementId, int seq, long createdBy) {
        return requireId(jdbc.queryForObject(
                "insert into requirement_revision (project_id, requirement_id, seq, title, created_by) "
                        + "values (?, ?, ?, 'title', ?) returning id",
                Long.class, projectId, requirementId, seq, createdBy));
    }

    private long insertAcceptanceCriterion(long projectId, long revisionId, String acKey, int sortOrder) {
        return requireId(jdbc.queryForObject(
                "insert into acceptance_criterion (project_id, requirement_revision_id, ac_key, sort_order, text) "
                        + "values (?, ?, ?, ?, 'given ... when ... then ...') returning id",
                Long.class, projectId, revisionId, acKey, sortOrder));
    }

    private void setRole(long projectId, long userId, String role) {
        jdbc.update("update project_member_role set role = ? where project_id = ? and user_id = ?",
                role, projectId, userId);
    }

    private long insertScmIdentity(long userId, String externalUserId) {
        return requireId(jdbc.queryForObject("insert into scm_identity (user_id, provider, "
                        + "instance_identity, external_user_id, external_username, label, usage_type, "
                        + "verification_status, verification_method, verified_at, last_synced_at) "
                        + "values (?, 'GITHUB', 'github.com', ?, ?, 'Work', 'WORK', 'VERIFIED', "
                        + "'ONE_TIME_TOKEN', now(), now()) returning id",
                Long.class, userId, externalUserId, externalUserId));
    }

    private void bindScmIdentity(long projectId, long userId, long identityId) {
        jdbc.update("insert into project_member_scm_binding (project_id, user_id, scm_identity_id, "
                        + "status, requested_by, approved_by, decided_at, activated_at) "
                        + "values (?, ?, ?, 'ACTIVE', ?, ?, now(), now())",
                projectId, userId, identityId, userId, userId);
    }

    private void backfillCurrentRevision(long requirementId, long revisionId) {
        jdbc.update("update requirement set current_revision_id = ? where id = ?", revisionId, requirementId);
    }

    private void setAssignee(long requirementId, long userId) {
        jdbc.update("update requirement set assignee_id = ? where id = ?", userId, requirementId);
    }

    private int leaderCount(long projectId) {
        return count("select count(*) from project_member_role where project_id = ? and role = 'LEADER'", projectId);
    }

    private int unconfiguredIdentityCount(long projectId) {
        return count("select count(*) from project_member pm left join project_member_scm_binding b "
                + "on b.project_id = pm.project_id and b.user_id = pm.user_id and b.status = 'ACTIVE' "
                + "where pm.project_id = ? and b.id is null", projectId);
    }

    private String roleOf(long projectId, long userId) {
        return jdbc.queryForObject("select role from project_member_role where project_id = ? and user_id = ?",
                String.class, projectId, userId);
    }

    private Long currentRevisionOf(long requirementId) {
        return jdbc.queryForObject("select current_revision_id from requirement where id = ?",
                Long.class, requirementId);
    }

    private Long assigneeOf(long requirementId) {
        return jdbc.queryForObject("select assignee_id from requirement where id = ?",
                Long.class, requirementId);
    }

    private List<String> acKeysOf(long revisionId) {
        return jdbc.queryForList("select ac_key from acceptance_criterion "
                + "where requirement_revision_id = ? order by sort_order", String.class, revisionId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static long requireId(Long id) {
        assertThat(id).isNotNull();
        return id;
    }

    /** PostgreSQL 拒绝该语句时给出的 SQLState，使测试能点名真正的那条规则。 */
    private static String sqlStateOf(ThrowingCallable action) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).as("statement was expected to be rejected").isNotNull();
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(cause).isInstanceOf(SQLException.class);
        return ((SQLException) cause).getSQLState();
    }
}
