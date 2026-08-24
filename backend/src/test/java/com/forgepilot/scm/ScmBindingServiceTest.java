package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScmBindingServiceTest extends PostgresTestBase {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired ScmIdentityService identities;
    @Autowired ScmBindingService bindings;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ScmIdentityVerifier verifier;

    @Test
    void defaultBindingActivatesImmediatelyAndMapsExistingPullRequests() {
        Fixture fixture = new Fixture(false);
        stubVerification(fixture);

        long identityId = identities.verify(fixture.developer, ScmProvider.GITHUB,
                "https://api.github.com", "one-time", "Work", ScmIdentityUsage.WORK).id();
        ScmBindingResponse binding = bindings.bind(
                fixture.project, fixture.developer, identityId, "one-time");

        assertThat(binding.status()).isEqualTo(ProjectMemberScmBinding.Status.ACTIVE);
        assertThat(authorUserId(fixture.pullRequest)).isEqualTo(fixture.developer);
    }

    @Test
    void strictBindingWaitsForLeaderApprovalBeforeMappingPullRequests() {
        Fixture fixture = new Fixture(true);
        stubVerification(fixture);

        long identityId = identities.verify(fixture.developer, ScmProvider.GITHUB,
                "https://api.github.com", "one-time", "Client", ScmIdentityUsage.CLIENT).id();
        ScmBindingResponse binding = bindings.bind(
                fixture.project, fixture.developer, identityId, "one-time");

        assertThat(binding.status()).isEqualTo(ProjectMemberScmBinding.Status.PENDING_APPROVAL);
        assertThat(authorUserId(fixture.pullRequest)).isNull();

        bindings.decide(fixture.project, fixture.leader, binding.id(), true);

        assertThat(authorUserId(fixture.pullRequest)).isEqualTo(fixture.developer);
        assertThat(bindings.list(fixture.project, fixture.leader)).singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(ProjectMemberScmBinding.Status.ACTIVE);
                    assertThat(row.approvedBy()).isEqualTo(fixture.leader);
                });
    }

    @Test
    void requestDiagnosticsRedactOneTimeTokens() {
        assertThat(new ScmIdentityController.VerifyRequest(ScmProvider.GITHUB,
                "https://api.github.com", "secret-1", "Work", ScmIdentityUsage.WORK).toString())
                .doesNotContain("secret-1").contains("[REDACTED]");
        assertThat(new ScmBindingController.BindRequest(7, "secret-2").toString())
                .doesNotContain("secret-2").contains("[REDACTED]");
    }

    private void stubVerification(Fixture fixture) {
        VerifiedScmUser user = new VerifiedScmUser(
                ScmProvider.GITHUB, "github.com", fixture.externalUserId, "octocat");
        when(verifier.currentUser(any(), anyString(), anyString())).thenReturn(user);
        when(verifier.repositoryAccess(any(), anyString()))
                .thenReturn(ProjectMemberScmBinding.AccessLevel.WRITE);
    }

    private Long authorUserId(long pullRequestId) {
        return jdbc.queryForObject("select author_user_id from pull_request where id = ?",
                Long.class, pullRequestId);
    }

    private final class Fixture {
        private final long leader;
        private final long developer;
        private final long project;
        private final long pullRequest;
        private final String externalUserId;

        private Fixture(boolean approvalRequired) {
            int sequence = SEQUENCE.incrementAndGet();
            externalUserId = "scm-user-" + sequence;
            leader = account("leader-" + sequence);
            developer = account("developer-" + sequence);
            project = jdbc.queryForObject("insert into project (name, created_by, status) "
                            + "values (?, ?, 'ACTIVE') returning id",
                    Long.class, "binding-project-" + sequence, leader);
            member(leader, "LEADER");
            member(developer, "DEVELOPER");
            long repository = jdbc.queryForObject("insert into scm_repository (project_id, provider, "
                            + "instance_identity, external_id, api_base, encrypted_token, encrypted_secret, "
                            + "identity_approval_required) values (?, 'GITHUB', 'github.com', ?, "
                            + "'https://api.github.com', 'x', 'y', ?) returning id",
                    Long.class, project, "binding-repository-" + sequence, approvalRequired);
            pullRequest = jdbc.queryForObject("insert into pull_request (project_id, repository_id, "
                            + "external_number, base_sha, head_sha, review_input_fingerprint, changed_files, "
                            + "author_external_user_id, author_username) values (?, ?, 1, 'base', 'head', "
                            + "'fingerprint', '[]'::jsonb, ?, 'octocat') returning id",
                    Long.class, project, repository, externalUserId);
        }

        private long account(String username) {
            return jdbc.queryForObject("insert into user_account (username, display_name, password_hash) "
                            + "values (?, 'Test User', 'x') returning id",
                    Long.class, username);
        }

        private void member(long userId, String role) {
            jdbc.update("with member as (insert into project_member (project_id, user_id) "
                            + "values (?, ?) returning project_id, user_id) "
                            + "insert into project_member_role (project_id, user_id, role) "
                            + "select project_id, user_id, ? from member",
                    project, userId, role);
        }
    }
}
