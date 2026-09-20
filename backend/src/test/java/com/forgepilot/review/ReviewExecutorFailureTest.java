package com.forgepilot.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.review.ChangedFileBatcher.Coverage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "forgepilot.review.reconciliation-interval-ms=3600000")
class ReviewExecutorFailureTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final ReviewPipeline.Report EMPTY_REPORT = new ReviewPipeline.Report(
            new ReviewOutput(List.of(), List.of(), List.of()),
            new Coverage(false, List.of(), List.of()), List.of());

    @Autowired
    private ReviewExecutor executor;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ReviewPipeline pipeline;

    @Test
    void anUnexpectedAnalysisFailureReachesTheFencedFailedState() {
        Fixture fixture = new Fixture();
        when(pipeline.analyse(any(), any())).thenThrow(new IllegalStateException("unexpected"));

        executor.run(fixture.project, fixture.review);

        assertThat(fixture.status()).isEqualTo("FAILED");
        assertThat(fixture.attempt()).isEqualTo(1);
    }

    @Test
    void aResultCommitFailureRollsBackBeforeTheFreshFailureTransition() {
        Fixture fixture = new Fixture();
        when(pipeline.analyse(any(), any())).thenReturn(Optional.of(EMPTY_REPORT));
        doAnswer(call -> {
            ReviewExecutor.Claim claim = call.getArgument(0);
            jdbc.update("update review set engine = 'must-roll-back' where id = ?", claim.reviewId());
            throw new IllegalStateException("store failed");
        }).when(pipeline).store(any(), any());

        executor.run(fixture.project, fixture.review);

        assertThat(fixture.status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select engine from review where id = ?", String.class,
                fixture.review)).isNull();
    }

    @Test
    void leaseLossStopsWithoutFailingTheReplacementAttempt() {
        Fixture fixture = new Fixture();
        when(pipeline.analyse(any(), any())).thenAnswer(call -> {
            ReviewExecutor.Claim old = call.getArgument(0);
            Runnable beforeAttempt = call.getArgument(1);
            jdbc.update("update review set lease_until = now() - interval '1 hour' where id = ?",
                    old.reviewId());
            ReviewExecutor.Claim replacement = executor.claim(old.projectId(), old.reviewId()).orElseThrow();
            assertThat(replacement.attempt()).isEqualTo(old.attempt() + 1);
            beforeAttempt.run();
            return Optional.empty();
        });

        executor.run(fixture.project, fixture.review);

        assertThat(fixture.status()).isEqualTo("RUNNING");
        assertThat(fixture.attempt()).isEqualTo(2);
    }

    private final class Fixture {
        private final long project;
        private final long review;

        private Fixture() {
            int sequence = SEQUENCE.incrementAndGet();
            long owner = jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) "
                            + "values (?, 'Test User', 'x') returning id",
                    Long.class, "executor-user-" + sequence);
            project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "executor-project-" + sequence, owner);
            jdbc.update("with member as (insert into project_member (project_id, user_id) "
                            + "values (?, ?) returning project_id, user_id) "
                            + "insert into project_member_role (project_id, user_id, role) "
                            + "select project_id, user_id, 'LEADER' from member",
                    project, owner);
            long repository = jdbc.queryForObject(
                    "insert into scm_repository (project_id, provider, instance_identity, external_id, "
                            + "api_base, encrypted_token, encrypted_secret) "
                            + "values (?, 'GITHUB', ?, ?, 'http://127.0.0.1', 'x', 'y') returning id",
                    Long.class, project, "executor-host-" + sequence, "executor-repo-" + sequence);
            long pullRequest = jdbc.queryForObject(
                    "insert into pull_request (project_id, repository_id, external_number, base_sha, "
                            + "head_sha, review_input_fingerprint, changed_files, author_external_user_id, "
                            + "author_username) values (?, ?, 1, ?, ?, ?, '[]'::jsonb, '424242', 'octocat') "
                            + "returning id",
                    Long.class, project, repository, "base-" + sequence, "head-" + sequence,
                    "fingerprint-" + sequence);
            review = jdbc.queryForObject(
                    "insert into review (project_id, pull_request_id, head_sha, review_input_fingerprint, "
                            + "status) values (?, ?, ?, ?, 'PENDING') returning id",
                    Long.class, project, pullRequest, "head-" + sequence, "fingerprint-" + sequence);
        }

        private String status() {
            return jdbc.queryForObject("select status from review where id = ?", String.class, review);
        }

        private int attempt() {
            return jdbc.queryForObject("select execution_attempt from review where id = ?", Integer.class,
                    review);
        }
    }
}
