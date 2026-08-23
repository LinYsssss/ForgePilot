package com.forgepilot.requirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.ai.AiGateway;
import com.forgepilot.common.ApiException;
import com.forgepilot.knowledge.ChunkSearchRepository.ChunkMatch;
import com.forgepilot.knowledge.KnowledgeDocumentView;
import com.forgepilot.knowledge.KnowledgeService;
import com.forgepilot.knowledge.KnowledgeSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The product attachment flow: one transaction writes the document and ownership row, promotion
 * copies it, and retrieval only sees public knowledge plus the current requirement's attachments.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RequirementAttachmentServiceTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private RequirementAttachmentService attachments;

    @Autowired
    private KnowledgeService knowledge;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiGateway ai;

    @BeforeEach
    void embeddingsShareTheProjectProfile() {
        when(ai.embed(anyList(), any(), any())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return texts.stream().map(text -> new float[] {1f, 0f}).toList();
        });
    }

    @Test
    void attachmentsPersistTheirOwnershipPromoteByCopyAndStayRequirementScopedInRetrieval() {
        Fixture fixture = new Fixture();
        assertThatThrownBy(() -> attachments.create(fixture.project, fixture.leader,
                fixture.firstRequirement, "brief.pdf", "unsupported"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        long publicDocument = knowledge.createProjectKnowledge(fixture.project, fixture.leader,
                "public.md", "public project rule");
        KnowledgeDocumentView first = attachments.create(fixture.project, fixture.leader,
                fixture.firstRequirement, "first.md", "first requirement-only rule");
        KnowledgeDocumentView second = attachments.create(fixture.project, fixture.leader,
                fixture.secondRequirement, "second.md", "second requirement-only rule");

        assertThat(jdbc.queryForObject("select count(*) from requirement_attachment "
                + "where project_id = ? and requirement_id = ? and document_id = ?", Integer.class,
                fixture.project, fixture.firstRequirement, first.id())).isOne();
        assertThat(attachments.list(fixture.project, fixture.leader, fixture.firstRequirement))
                .extracting(KnowledgeDocumentView::id).containsExactly(first.id());
        assertThat(attachments.content(fixture.project, fixture.developer,
                fixture.firstRequirement, first.id()))
                .satisfies(content -> {
                    assertThat(content.fileName()).isEqualTo("first.md");
                    assertThat(content.mediaType()).isEqualTo("text/markdown");
                    assertThat(content.text()).isEqualTo("first requirement-only rule");
                });
        assertThatThrownBy(() -> attachments.content(fixture.project, fixture.developer,
                fixture.secondRequirement, first.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        KnowledgeDocumentView promoted = attachments.promote(fixture.project, fixture.leader,
                fixture.firstRequirement, first.id());
        assertThat(promoted.id()).isNotEqualTo(first.id());
        assertThat(promoted.sourceType()).isEqualTo(KnowledgeSourceType.PROJECT_KNOWLEDGE);
        assertThat(jdbc.queryForObject("select count(*) from requirement_attachment "
                + "where project_id = ? and document_id = ?", Integer.class, fixture.project, first.id()))
                .isOne();

        List<Long> firstRecall = knowledge.search(fixture.project, fixture.leader, fixture.firstRequirement,
                new float[] {1f, 0f}, 10).stream().map(ChunkMatch::documentId).toList();
        List<Long> secondRecall = knowledge.search(fixture.project, fixture.leader, fixture.secondRequirement,
                new float[] {1f, 0f}, 10).stream().map(ChunkMatch::documentId).toList();
        List<Long> publicRecall = knowledge.search(fixture.project, fixture.leader, null,
                new float[] {1f, 0f}, 10).stream().map(ChunkMatch::documentId).toList();

        assertThat(firstRecall).contains(publicDocument, promoted.id(), first.id()).doesNotContain(second.id());
        assertThat(secondRecall).contains(publicDocument, promoted.id(), second.id()).doesNotContain(first.id());
        assertThat(publicRecall).contains(publicDocument, promoted.id())
                .doesNotContain(first.id(), second.id());
    }

    private final class Fixture {

        private final long leader;
        private final long developer;
        private final long project;
        private final long firstRequirement;
        private final long secondRequirement;

        private Fixture() {
            leader = jdbc.queryForObject("insert into user_account (username, password_hash) values (?, 'x') "
                    + "returning id", Long.class, "attachment-user-" + SEQUENCE.incrementAndGet());
            project = jdbc.queryForObject("insert into project (name, created_by, status) "
                    + "values (?, ?, 'ACTIVE') returning id", Long.class,
                    "attachment-project-" + SEQUENCE.incrementAndGet(), leader);
            jdbc.update("insert into project_member (project_id, user_id, role) values (?, ?, 'LEADER')",
                    project, leader);
            developer = jdbc.queryForObject("insert into user_account (username, password_hash) values (?, 'x') "
                    + "returning id", Long.class, "attachment-developer-" + SEQUENCE.incrementAndGet());
            jdbc.update("insert into project_member (project_id, user_id, role) values (?, ?, 'DEVELOPER')",
                    project, developer);
            firstRequirement = requirement();
            secondRequirement = requirement();
        }

        private long requirement() {
            return jdbc.queryForObject("insert into requirement (project_id, status) values (?, 'DRAFT') "
                    + "returning id", Long.class, project);
        }
    }
}
