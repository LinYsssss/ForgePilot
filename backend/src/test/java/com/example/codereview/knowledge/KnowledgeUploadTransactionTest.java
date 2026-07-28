package com.example.codereview.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.codereview.project.ProjectEntity;
import com.example.codereview.project.ProjectRepository;
import com.example.codereview.rag.EmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Regression coverage for P1-05.
 *
 * <p>Upload used to be one transaction that marked the document FAILED and then rethrew. The
 * rollback discarded the status change <em>and</em> the document row, so a failed upload left
 * nothing behind — the caller saw a 500 and the document list stayed empty, with no record of what
 * had been attempted or why.
 *
 * <p>The assertion that matters is on the database state after the failure, not on the response
 * code: asserting the endpoint returns 500 would have passed against the buggy version too.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false",
        "app.rag.mode=memory",
        "app.rag.full-context=false"
})
@ActiveProfiles("dev")
class KnowledgeUploadTransactionTest {

    private static final Long USER_ID = 501L;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private KnowledgeChunkRepository chunks;

    @Autowired
    private ProjectRepository projects;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    private Long projectId;

    @BeforeEach
    void setUp() {
        chunks.deleteAll();
        documents.deleteAll();
        projects.deleteAll();
        ProjectEntity project = projects.saveAndFlush(
                new ProjectEntity(USER_ID, "知识库项目", "upload transaction test", "main"));
        projectId = project.getId();
    }

    @Test
    void documentSurvivesAsFailedWhenEmbeddingThrows() {
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("provider exploded"));

        assertThatThrownBy(() -> knowledgeService.upload(projectId, USER_ID, "SPEC", markdown()))
                .isInstanceOf(RuntimeException.class);

        assertThat(documents.findAll())
                .as("a failed upload must leave a record behind, not vanish with the rollback")
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.getStatus()).isEqualTo("FAILED");
                    assertThat(document.getIndexError()).isNotBlank();
                    // The stored reason must not carry the provider's raw message.
                    assertThat(document.getIndexError()).doesNotContain("provider exploded");
                });
    }

    @Test
    void successfulUploadIsMarkedIndexedAndKeepsNoError() {
        when(embeddingClient.embed(anyString())).thenReturn(embedding());

        knowledgeService.upload(projectId, USER_ID, "SPEC", markdown());

        assertThat(documents.findAll()).singleElement().satisfies(document -> {
            assertThat(document.getStatus()).isEqualTo("INDEXED");
            assertThat(document.getIndexError()).isNull();
        });
    }

    @Test
    void rejectedInputCreatesNoDocumentAtAll() {
        // Validation happens before the row is created, so a bad upload should not leave a PENDING
        // document lying around for the watchdog to puzzle over.
        assertThatThrownBy(() -> knowledgeService.upload(
                projectId, USER_ID, "SPEC",
                new MockMultipartFile("file", "evil.md", "text/markdown", new byte[]{0x00, 0x01})))
                .isInstanceOf(RuntimeException.class);

        assertThat(documents.count()).isZero();
    }

    @Test
    void storedFileNameIsSanitised() {
        when(embeddingClient.embed(anyString())).thenReturn(embedding());

        knowledgeService.upload(projectId, USER_ID, "SPEC", new MockMultipartFile(
                "file", "../../../etc/order-flow.md", "text/markdown",
                "# 订单流程\n\n下单后进入待支付".getBytes(StandardCharsets.UTF_8)));

        assertThat(documents.findAll()).singleElement()
                .extracting(KnowledgeDocument::getFileName)
                .isEqualTo("order-flow.md");
    }

    private EmbeddingClient.EmbeddingResult embedding() {
        return new EmbeddingClient.EmbeddingResult("mock", "mock-embedding", "v1", 2, List.of(0.1d, 0.2d));
    }

    private MockMultipartFile markdown() {
        return new MockMultipartFile("file", "spec.md", "text/markdown",
                "# 规范\n\n这是一段用于索引的正文内容".getBytes(StandardCharsets.UTF_8));
    }
}
