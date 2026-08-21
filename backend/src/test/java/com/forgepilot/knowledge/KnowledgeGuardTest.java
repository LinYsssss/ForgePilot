package com.forgepilot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import com.forgepilot.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two defences the database cannot provide: rejecting text it would silently
 * corrupt, and refusing a vector whose dimension disagrees with its project.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeGuardTest extends PostgresTestBase {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private KnowledgeUploadValidator validator;

    @Autowired
    private ChunkSearchRepository chunks;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------- text

    @Test
    void aLoneSurrogateIsRejectedBecauseTheDatabaseNeverSeesIt() {
        String corrupt = "a" + (char) 0xD800 + "b";

        // Proof this check is load-bearing: the driver would have turned the
        // surrogate into '?' and PostgreSQL would have stored it without complaint.
        assertThat(new String(corrupt.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .as("the encoder itself already destroys the character")
                .isNotEqualTo(corrupt);

        assertThatThrownBy(() -> validator.validate("doc.md", corrupt))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("unpaired surrogate");
    }

    @Test
    void aNulByteIsRejectedBeforeAnythingIsEmbedded() {
        // Written as an expression on purpose: a literal NUL in the source makes
        // grep and every other text tool treat this file as binary.
        String withNul = "abc" + (char) 0 + "def";

        assertThatThrownBy(() -> validator.validate("doc.md", withNul))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("NUL");
    }

    @Test
    void oversizedTextIsRejected() {
        String tooBig = "a".repeat(KnowledgeUploadValidator.MAX_TEXT_BYTES + 1);

        assertThatThrownBy(() -> validator.validate("doc.md", tooBig))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void ordinaryTextIncludingAstralCharactersIsAccepted() {
        validator.validate("设计说明.md", "包含星光面字符 🚀 的正文");
    }

    // -------------------------------------------------------------- dimensions

    @Test
    void aVectorWhoseDimensionDisagreesWithTheProjectIsRefused() {
        Fixture fixture = new Fixture();
        long first = fixture.chunk(1);
        long second = fixture.chunk(2);

        assertThat(chunks.establishedDimension(fixture.project)).isNull();
        chunks.writeEmbedding(fixture.project, first, new float[] {0.1f, 0.2f, 0.3f, 0.4f});
        assertThat(chunks.establishedDimension(fixture.project)).isEqualTo(4);

        // Without this guard the row would land, and every later search in this
        // project would fail with 22000 instead.
        assertThatThrownBy(() -> chunks.writeEmbedding(fixture.project, second, new float[] {1f, 2f, 3f}))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("4-dimension");

        chunks.writeEmbedding(fixture.project, second, new float[] {0.2f, 0.3f, 0.4f, 0.5f});
        assertThat(chunks.search(fixture.project, new float[] {0.1f, 0.2f, 0.3f, 0.4f}, 5))
                .extracting(ChunkSearchRepository.ChunkMatch::id)
                .containsExactly(first, second);
    }

    @Test
    void searchNeverReachesAnotherProject() {
        Fixture mine = new Fixture();
        Fixture theirs = new Fixture();
        chunks.writeEmbedding(mine.project, mine.chunk(1), new float[] {1f, 0f});
        chunks.writeEmbedding(theirs.project, theirs.chunk(1), new float[] {1f, 0f});

        assertThat(chunks.search(mine.project, new float[] {1f, 0f}, 10)).hasSize(1);
    }

    // ---------------------------------------------------------------- fixture

    private final class Fixture {

        private final long project;
        private final long document;

        private Fixture() {
            Long owner = jdbc.queryForObject(
                    "insert into user_account (username, password_hash) values (?, 'x') returning id",
                    Long.class, "k-" + SEQUENCE.incrementAndGet());
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "kp-" + SEQUENCE.incrementAndGet(), owner);
            this.document = jdbc.queryForObject(
                    "insert into knowledge_document (project_id, source_type, title, text, status) "
                            + "values (?, 'PROJECT_KNOWLEDGE', 'doc.md', 'body', 'READY') returning id",
                    Long.class, project);
        }

        private long chunk(int seq) {
            return jdbc.queryForObject(
                    "insert into knowledge_chunk (project_id, document_id, seq, content) "
                            + "values (?, ?, ?, 'chunk') returning id",
                    Long.class, project, document, seq);
        }
    }
}
