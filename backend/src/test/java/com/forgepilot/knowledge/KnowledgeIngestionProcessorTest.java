package com.forgepilot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.ai.AiGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeIngestionProcessorTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private KnowledgeService knowledge;

    @Autowired
    private KnowledgeIngestionProcessor processor;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private KnowledgeChunkRepository chunks;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiGateway ai;

    @BeforeEach
    void drainEarlierPendingFixtures() {
        when(ai.embed(anyList(), any(), any())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return texts.stream().map(text -> new float[] {0.1f, 0.2f, 0.3f, 0.4f}).toList();
        });
        processPendingKnowledge(processor, jdbc);
        clearInvocations(ai);
    }

    @Test
    void creationCommitsPendingWithoutWaitingForTheProvider() {
        Fixture fixture = new Fixture();

        long document = knowledge.createProjectKnowledge(
                fixture.project, fixture.leader, "accepted.md", "accepted body");

        assertThat(documents.findByProjectIdAndId(fixture.project, document))
                .get().extracting(KnowledgeDocument::getStatus)
                .isEqualTo(KnowledgeStatus.PENDING);
        assertThat(chunks.findByProjectIdAndDocumentIdOrderBySeqAsc(fixture.project, document)).isEmpty();
        verifyNoInteractions(ai);
    }

    @Test
    void aProviderFailureBecomesDurableAndDoesNotLeakItsMessage() {
        Fixture fixture = new Fixture();
        long document = knowledge.createProjectKnowledge(
                fixture.project, fixture.leader, "failure.md", "body");
        when(ai.embed(anyList(), any(), any()))
                .thenThrow(new IllegalStateException("provider response with secret text"));

        processor.processNext();

        KnowledgeDocument failed = documents.findByProjectIdAndId(fixture.project, document).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(KnowledgeStatus.FAILED);
        assertThat(failed.getFailureReason())
                .isEqualTo("Document processing failed (IllegalStateException).")
                .doesNotContain("secret");
        assertThat(chunks.findByProjectIdAndDocumentIdOrderBySeqAsc(fixture.project, document)).isEmpty();
    }

    @Test
    void aFailedResultWriteRollsBackEveryChunkBeforeMarkingFailed() {
        Fixture fixture = new Fixture();
        long document = knowledge.createProjectKnowledge(
                fixture.project, fixture.leader, "dimension.md", "line\n".repeat(1_000));
        assertThat(KnowledgeService.split("line\n".repeat(1_000))).hasSizeGreaterThan(1);
        when(ai.embed(anyList(), any(), any())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return java.util.stream.IntStream.range(0, texts.size())
                    .mapToObj(index -> index == 0
                            ? new float[] {0.1f, 0.2f, 0.3f, 0.4f}
                            : new float[] {0.1f, 0.2f, 0.3f})
                    .toList();
        });

        processor.processNext();

        assertThat(documents.findByProjectIdAndId(fixture.project, document))
                .get().satisfies(failed -> {
                    assertThat(failed.getStatus()).isEqualTo(KnowledgeStatus.FAILED);
                    assertThat(failed.getFailureReason()).contains("unprocessable");
                });
        assertThat(chunks.findByProjectIdAndDocumentIdOrderBySeqAsc(fixture.project, document))
                .as("the first vector write must roll back with the later dimension failure")
                .isEmpty();
    }

    @Test
    void deletionWhileTheProviderRunsCannotResurrectTheDocument() {
        Fixture fixture = new Fixture();
        long document = knowledge.createProjectKnowledge(
                fixture.project, fixture.leader, "delete.md", "body");
        when(ai.embed(anyList(), any(), any())).thenAnswer(call -> {
            knowledge.deleteProjectKnowledge(fixture.project, fixture.leader, document);
            List<String> texts = call.getArgument(0);
            return texts.stream().map(text -> new float[] {0.1f, 0.2f, 0.3f, 0.4f}).toList();
        });

        processor.processNext();

        assertThat(documents.findByProjectIdAndId(fixture.project, document)).isEmpty();
        assertThat(chunks.findByProjectIdAndDocumentIdOrderBySeqAsc(fixture.project, document)).isEmpty();
    }

    private final class Fixture {
        private final long leader;
        private final long project;

        private Fixture() {
            int sequence = SEQUENCE.incrementAndGet();
            leader = jdbc.queryForObject(
                    "insert into user_account (username, display_name, password_hash) "
                            + "values (?, 'Test User', 'x') returning id",
                    Long.class, "ingestion-user-" + sequence);
            project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "ingestion-project-" + sequence, leader);
            jdbc.update("with member as (insert into project_member (project_id, user_id) "
                            + "values (?, ?) returning project_id, user_id) "
                            + "insert into project_member_role (project_id, user_id, role) "
                            + "select project_id, user_id, 'LEADER' from member",
                    project, leader);
        }
    }
}
