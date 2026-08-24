package com.forgepilot.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProjectAuthorizationTest extends PostgresTestBase {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    @Autowired ProjectService projects;
    @Autowired ProjectMemberService members;
    @Autowired ProjectMemberRepository memberRepository;
    @Autowired ProjectAccessService access;
    @Autowired JdbcTemplate jdbc;

    @Test
    void creatorBecomesTheOnlyLeader() {
        long creator = account();
        ProjectResponse project = projects.create("Alpha", creator);
        assertThat(project.myRoles()).containsExactly(ProjectRole.LEADER);
        assertThat(memberRepository.findByProjectIdAndUserId(project.id(), creator).orElseThrow().getRoles())
                .containsExactly(ProjectRole.LEADER);
        assertThat(leaderCount(project.id())).isEqualTo(1);
    }

    @Test
    void batchAddIsAtomicAndRoleCapabilitiesAreCombined() {
        long leader = account();
        long developerReviewer = account();
        long project = projects.create("Roles", leader).id();
        members.addBatch(project, leader, List.of(new ProjectMemberService.BatchMember(
                developerReviewer, Set.of(ProjectRole.DEVELOPER, ProjectRole.REVIEWER))));
        assertThat(access.requireRole(project, developerReviewer, ProjectRole.DEVELOPER).getRoles())
                .containsExactlyInAnyOrder(ProjectRole.DEVELOPER, ProjectRole.REVIEWER);
        assertThat(access.requireRole(project, developerReviewer, ProjectRole.REVIEWER)).isNotNull();

        assertThat(statusOf(() -> members.addBatch(project, leader, List.of(
                new ProjectMemberService.BatchMember(account(), Set.of(ProjectRole.DEVELOPER)),
                new ProjectMemberService.BatchMember(99_999_999L, Set.of(ProjectRole.REVIEWER))))))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(members.list(project, leader)).hasSize(2);
    }

    @Test
    void leaderTransferPreservesOtherRolesAndKeepsOneLeader() {
        long leader = account();
        long successor = account();
        long project = projects.create("Transfer", leader).id();
        members.addBatch(project, leader, List.of(new ProjectMemberService.BatchMember(
                successor, Set.of(ProjectRole.DEVELOPER, ProjectRole.REVIEWER))));
        members.transferLeader(project, leader, successor);
        assertThat(memberRepository.findByProjectIdAndUserId(project, successor).orElseThrow().getRoles())
                .containsExactlyInAnyOrder(ProjectRole.LEADER, ProjectRole.DEVELOPER, ProjectRole.REVIEWER);
        assertThat(memberRepository.findByProjectIdAndUserId(project, leader).orElseThrow().getRoles())
                .containsExactly(ProjectRole.DEVELOPER);
        assertThat(leaderCount(project)).isEqualTo(1);
    }

    @Test
    void outsidersCannotProbeProjectsOrManageMembers() {
        long leader = account();
        long outsider = account();
        long project = projects.create("Private", leader).id();
        assertThat(statusOf(() -> projects.get(project, outsider))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(() -> members.search(project, outsider, "user", 0, 20)))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private long account() {
        String username = "member-" + SEQUENCE.incrementAndGet();
        return jdbc.queryForObject("""
                insert into user_account (username, display_name, password_hash)
                values (?, ?, 'bcrypt-placeholder') returning id
                """, Long.class, username, "Member " + SEQUENCE.get());
    }

    private int leaderCount(long projectId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from project_member_role
                where project_id = ? and role = 'LEADER'
                """, Integer.class, projectId);
        return count == null ? 0 : count;
    }

    private static HttpStatus statusOf(Runnable action) {
        try { action.run(); return null; }
        catch (ApiException exception) { return exception.getStatus(); }
    }
}
