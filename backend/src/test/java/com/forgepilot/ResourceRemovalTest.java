package com.forgepilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.DeletedResourceType;
import com.forgepilot.project.ProjectDeletionLog;
import com.forgepilot.project.ProjectMemberService;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.requirement.RequirementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 成员移除与作废需求软删（T-006 / T-007）。
 *
 * <p>放在根测试包而不是某个 feature 下，是因为它证明的正是跨模块那一段：成员移除
 * 靠 {@code ProjectMemberRemoving} 反转依赖方向，由 {@code requirement} /
 * {@code review} / {@code scm} 三个监听器各自撤销自己那部分引用。任何一个监听器
 * 缺失或顺序颠倒，都在这里炸。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ResourceRemovalTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private ProjectMemberService members;
    @Autowired private RequirementService requirements;
    @Autowired private ProjectDeletionLog deletions;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 一次移除必须同时做到两件相反的事：**活权限全部失效**，而**既成事实与审计
     * 一字不动**。这条测试把两边都钉住，因为放过任一边都是一个静默的正确性缺陷
     * ——前者留下一个已离职者仍能被指派的项目，后者销毁作者身份或审计。
     */
    @Test
    void removingAMemberRevokesLivePermissionsAndKeepsEveryAccomplishedFact() {
        Fixture fixture = new Fixture();

        members.remove(fixture.project, fixture.leader, fixture.developer);

        assertThat(count("select count(*) from project_member where project_id = ? and user_id = ?",
                fixture.project, fixture.developer)).isZero();
        assertThat(count("select count(*) from project_member_role where project_id = ? and user_id = ?",
                fixture.project, fixture.developer))
                .as("project_member_role 是 @ElementCollection，随实体一起消失")
                .isZero();

        // 活权限：指派与项目绑定。
        assertThat(jdbc.queryForObject("select assignee_id from requirement where id = ?",
                Long.class, fixture.requirement)).isNull();
        assertThat(jdbc.queryForObject("select assignee_id from finding where id = ?",
                Long.class, fixture.finding)).isNull();
        assertThat(count("select count(*) from project_member_scm_binding where project_id = ? and user_id = ?",
                fixture.project, fixture.developer)).isZero();

        // 既成事实：两列作者快照 NOT NULL，移除后完整可读；只有可重算映射被置空。
        assertThat(jdbc.queryForMap("select author_external_user_id, author_username, author_user_id "
                + "from pull_request where id = ?", fixture.pullRequest))
                .containsEntry("author_external_user_id", "gh-dev")
                .containsEntry("author_username", "dev-login")
                .containsEntry("author_user_id", null);

        // 审计：Finding 血缘、人工决定与 AI 调用记录一条都不少。
        assertThat(count("select count(*) from finding where id = ?", fixture.finding)).isEqualTo(1);
        assertThat(count("select count(*) from finding_event where finding_id = ?", fixture.finding))
                .isEqualTo(1);
        assertThat(count("select count(*) from ai_call_log where project_id = ?", fixture.project))
                .isEqualTo(1);

        // 用户自有身份与平台账号不受影响：身份归用户本人，不是项目财产。
        assertThat(count("select count(*) from scm_identity where user_id = ?", fixture.developer))
                .isEqualTo(1);
        assertThat(count("select count(*) from user_account where id = ?", fixture.developer))
                .isEqualTo(1);

        // 留痕落在被删对象之外，并记下每一类撤销了多少条。
        assertThat(deletions.forProject(fixture.project))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.getResourceType()).isEqualTo(DeletedResourceType.PROJECT_MEMBER);
                    assertThat(record.getResourceId()).isEqualTo(fixture.developer);
                    assertThat(record.getActorUserId()).isEqualTo(fixture.leader);
                    assertThat(record.getDetail()).isEqualTo(
                            "roles: 1; requirement assignments: 1; finding assignments: 1; scm bindings: 1");
                });

        // 重复移除得到 404——硬删之下这就是明确结果（AC14）。
        assertThat(statusOf(() -> members.remove(fixture.project, fixture.leader, fixture.developer)))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * {@code UNIQUE(project_id) WHERE role='LEADER'} 保证的是**至多**一个；
     * 「至少一个」历来是服务端职责。删掉唯一 LEADER 不违反任何约束，
     * 只会让项目失去负责人，所以没有测试就没有任何东西挡着它。
     */
    @Test
    void theOnlyLeaderCannotBeRemovedAndOutsidersCannotProbe() {
        Fixture fixture = new Fixture();
        Fixture other = new Fixture();

        assertThat(statusOf(() -> members.remove(fixture.project, fixture.leader, fixture.leader)))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("select count(*) from project_member where project_id = ?", fixture.project))
                .isEqualTo(2);

        // 非 LEADER 不可移除；跨项目与不存在同答（AC12 / AC13）。
        assertThat(statusOf(() -> members.remove(fixture.project, fixture.developer, fixture.leader)))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(() -> members.remove(other.project, other.leader, fixture.developer)))
                .isEqualTo(HttpStatus.NOT_FOUND);

        // 转移之后原负责人就可以被移除了，这正是 409 指出的那条路。
        members.transferLeader(fixture.project, fixture.leader, fixture.developer);
        members.remove(fixture.project, fixture.developer, fixture.leader);
        assertThat(count("select count(*) from project_member where project_id = ?", fixture.project))
                .isEqualTo(1);
    }

    /**
     * 需求走软删，因为 {@code ai_call_log} 与 {@code pull_request_requirement_event}
     * 挂在它上面——硬删就是销毁审计、抹掉一件已经发生的关联。所以这条测试的重点不是
     * 「删掉了」，而是「从产品面消失**且**审计仍在」。
     */
    @Test
    void onlyACanceledRequirementIsDeletedAndItsAuditSurvives() {
        Fixture fixture = new Fixture();

        // 非作废状态一律拒绝。
        assertThat(statusOf(() -> requirements.delete(
                fixture.project, fixture.leader, fixture.requirement)))
                .isEqualTo(HttpStatus.CONFLICT);

        jdbc.update("update requirement set status = 'CANCELED' where id = ?", fixture.requirement);
        requirements.delete(fixture.project, fixture.leader, fixture.requirement);

        // 从产品面消失：列表与详情都看不到它了。
        assertThat(requirements.list(fixture.project, fixture.leader)).isEmpty();
        assertThat(statusOf(() -> requirements.get(
                fixture.project, fixture.leader, fixture.requirement)))
                .isEqualTo(HttpStatus.NOT_FOUND);

        // 而行本身还在，两列打标同空或同非空，审计与既成事实一条不少。
        assertThat(jdbc.queryForMap(
                "select deleted_by, (deleted_at is not null) as marked from requirement where id = ?",
                fixture.requirement))
                .containsEntry("deleted_by", fixture.leader)
                .containsEntry("marked", true);
        assertThat(count("select count(*) from ai_call_log where requirement_id = ?", fixture.requirement))
                .as("AC11: 物理销毁需求就是销毁调用审计，软删正是为了避免它")
                .isEqualTo(1);
        assertThat(count("select count(*) from requirement_revision where requirement_id = ?",
                fixture.requirement)).isEqualTo(1);

        // 重复删除得到 404：它已经离开产品面，答案与不存在一致（AC14）。
        assertThat(statusOf(() -> requirements.delete(
                fixture.project, fixture.leader, fixture.requirement)))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * {@code ck_project_deletion_record_resource_type} 与 {@link DeletedResourceType}
     * 是同一份词表的两个副本，而走到那条 CHECK 的越界值中止的是整条插入。所以这里
     * 走完**整个** enum，任何一边多加一个值而另一边忘了，这条就会失败。
     */
    @Test
    void everyDeletedResourceTypeSurvivesTheCheckVocabulary() {
        Fixture fixture = new Fixture();

        for (DeletedResourceType type : DeletedResourceType.values()) {
            jdbc.update("""
                    insert into project_deletion_record
                        (project_id, resource_type, resource_id, actor_user_id, detail)
                    values (?, ?, ?, ?, 'vocabulary walk')
                    """, fixture.project, type.name(), fixture.requirement, fixture.leader);
        }

        assertThat(jdbc.queryForList(
                "select resource_type from project_deletion_record where project_id = ?",
                String.class, fixture.project))
                .containsExactlyInAnyOrderElementsOf(
                        List.of(DeletedResourceType.values()).stream().map(Enum::name).toList());
    }

    // ---------------------------------------------------------------- fixture

    /**
     * 一个项目，一个 LEADER，一个同时持有需求指派、Finding 认领、SCM 绑定与 PR
     * 作者映射的 DEVELOPER。这四样正是成员移除必须区别对待的四种引用。
     */
    private final class Fixture {

        private final long leader;
        private final long developer;
        private final long project;
        private final long requirement;
        private final long revision;
        private final long pullRequest;
        private final long finding;

        private Fixture() {
            int seq = SEQUENCE.incrementAndGet();
            this.leader = account("lead-" + seq);
            this.developer = account("dev-" + seq);
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "removal-" + seq, leader);
            member(leader, ProjectRole.LEADER);
            member(developer, ProjectRole.DEVELOPER);

            this.requirement = jdbc.queryForObject(
                    "insert into requirement (project_id, status, assignee_id) "
                            + "values (?, 'IN_DEVELOPMENT', ?) returning id",
                    Long.class, project, developer);
            this.revision = jdbc.queryForObject(
                    "insert into requirement_revision (project_id, requirement_id, seq, title, created_by) "
                            + "values (?, ?, 1, '登录闭环', ?) returning id",
                    Long.class, project, requirement, leader);
            jdbc.update("update requirement set current_revision_id = ? where id = ?", revision, requirement);

            long repository = jdbc.queryForObject("""
                    insert into scm_repository (project_id, provider, instance_identity, external_id,
                        api_base, encrypted_token, encrypted_secret)
                    values (?, 'GITHUB', 'github.com', ?, 'https://api.github.com', 'token', 'secret')
                    returning id
                    """, Long.class, project, "repo-" + seq);
            long identity = jdbc.queryForObject("""
                    insert into scm_identity (user_id, provider, instance_identity, external_user_id,
                        external_username, label, usage_type, verification_status, verification_method,
                        verified_at, last_synced_at)
                    values (?, 'GITHUB', 'github.com', ?, 'dev-login', '公司 GitHub', 'WORK',
                        'VERIFIED', 'ONE_TIME_TOKEN', now(), now())
                    returning id
                    """, Long.class, developer, "gh-dev-" + seq);
            jdbc.update("""
                    insert into project_member_scm_binding (project_id, user_id, scm_identity_id,
                        repository_id, status, access_level, access_checked_at, requested_by, activated_at)
                    values (?, ?, ?, ?, 'ACTIVE', 'WRITE', now(), ?, now())
                    """, project, developer, identity, repository, developer);

            this.pullRequest = jdbc.queryForObject("""
                    insert into pull_request (project_id, repository_id, external_number, base_sha,
                        head_sha, review_input_fingerprint, changed_files, requirement_id,
                        author_external_user_id, author_username, author_user_id)
                    values (?, ?, ?, 'base-sha', 'head-sha', ?, '[]'::jsonb, ?, 'gh-dev', 'dev-login', ?)
                    returning id
                    """, Long.class, project, repository, seq, "fingerprint-" + seq, requirement, developer);
            long review = jdbc.queryForObject("""
                    insert into review (project_id, pull_request_id, head_sha, review_input_fingerprint,
                        requirement_id, requirement_revision_id, status, execution_attempt)
                    values (?, ?, 'head-sha', ?, ?, ?, 'COMPLETED', 0)
                    returning id
                    """, Long.class, project, pullRequest, "fingerprint-" + seq, requirement, revision);
            this.finding = jdbc.queryForObject("""
                    insert into finding (project_id, review_id, review_attempt, requirement_id,
                        requirement_revision_id, finding_type, path, line, evidence, status,
                        assignee_id, finding_key, evidence_hash, basis_hash, continuity)
                    values (?, ?, 0, ?, ?, 'CODE_QUALITY', 'src/A.java', 3, 'class A {}', 'IN_PROGRESS',
                        ?, ?, 'evidence-hash', 'basis-hash', 'NEW')
                    returning id
                    """, Long.class, project, review, requirement, revision, developer, "key-" + seq);
            jdbc.update("""
                    insert into finding_event (project_id, finding_id, actor_id, action,
                        from_status, to_status)
                    values (?, ?, ?, 'CLAIM', 'CONFIRMED', 'IN_PROGRESS')
                    """, project, finding, developer);
            jdbc.update("""
                    insert into ai_call_log (project_id, review_id, requirement_id,
                        requirement_revision_id, use_case, model, latency_ms, status)
                    values (?, ?, ?, ?, 'REVIEW', 'test-model', 12, 'SUCCESS')
                    """, project, review, requirement, revision);
        }

        private long account(String username) {
            return jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) "
                            + "values (?, ?, 'x') returning id",
                    Long.class, username, "User " + username);
        }

        private void member(long userId, ProjectRole role) {
            jdbc.update("insert into project_member (project_id, user_id) values (?, ?)", project, userId);
            jdbc.update("insert into project_member_role (project_id, user_id, role) values (?, ?, ?)",
                    project, userId, role.name());
        }
    }

    private int count(String query, Object... arguments) {
        Integer count = jdbc.queryForObject(query, Integer.class, arguments);
        return count == null ? 0 : count;
    }

    private static HttpStatus statusOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (ApiException exception) {
            return exception.getStatus();
        }
    }
}
