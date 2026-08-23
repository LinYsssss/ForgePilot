package com.forgepilot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import com.forgepilot.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 为什么这个部署没有向量索引（D019），以及为什么没有索引也是正确的。
 *
 * <p>D001 曾承诺：Embedding Profile 冻结之后，用一条独立 migration 建出与检索
 * cast 完全一致的 HNSW 表达式索引。冻结下来的 Profile 是
 * {@code Qwen/Qwen3-Embedding-8B}，**4096 维**——而 pgvector 0.8.6 建不出来。
 * 下面第一个测试就是那条论据本身：它把三种索引形态各试一次，
 * 逐条钉住数据库给出的拒绝理由。
 *
 * <p>把它写成测试而不是写成一份研究笔记，是因为这条论据会过期：pgvector
 * 一旦放宽维度上限，本测试就会失败，而那次失败正是「D019 的前提变了，
 * 回去重新决策」的信号。一份躺在 markdown 里的实测结论没有这个性质。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeVectorIndexTest extends PostgresTestBase {

    /** `qwen3-embedding-8b-4096-v1`：冻结 Profile 的维度。 */
    private static final int FROZEN_DIMENSION = 4096;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private ChunkSearchRepository chunks;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 三种形态、三条拒绝。它们合起来说明的不是「谁忘了写那条 migration」，
     * 而是「在这个 Profile 下那条 migration 写不出来」。
     */
    @Test
    void noExactVectorIndexCanCoverTheFrozenDimension() {
        String table = "vector_index_probe_" + SEQUENCE.incrementAndGet();
        jdbc.execute("create table " + table + " (id bigint primary key, embedding vector)");
        try {
            // hnsw + vector：上限 2000 维。
            assertThatThrownBy(() -> jdbc.execute("create index on " + table
                    + " using hnsw ((embedding::vector(" + FROZEN_DIMENSION + ")) vector_cosine_ops)"))
                    .hasMessageContaining("2000 dimensions");

            // hnsw + halfvec：上限 4000 维，仍然不够，而且它本来就是有损的半精度。
            assertThatThrownBy(() -> jdbc.execute("create index on " + table
                    + " using hnsw ((embedding::halfvec(" + FROZEN_DIMENSION + ")) halfvec_cosine_ops)"))
                    .hasMessageContaining("4000 dimensions");

            // ivfflat：同样 2000 维上限，因此换索引类型也救不回来。
            assertThatThrownBy(() -> jdbc.execute("create index on " + table
                    + " using ivfflat ((embedding::vector(" + FROZEN_DIMENSION + ")) vector_cosine_ops)"))
                    .hasMessageContaining("2000 dimensions");
        } finally {
            jdbc.execute("drop table " + table);
        }
    }

    /**
     * 唯一能建出来的两种形态各是什么代价：它们都建得成，但都不是精确检索。
     * 保留这条测试是为了让 D019 的「可选项」不必靠记忆——下一个人想加索引时，
     * 这里直接告诉他能加的是什么，以及为什么加了就必须再补一个 rerank 阶段。
     */
    @Test
    void theOnlyBuildableFormsAreLossyPrefiltersThatWouldNeedARerank() {
        String table = "vector_index_probe_" + SEQUENCE.incrementAndGet();
        jdbc.execute("create table " + table + " (id bigint primary key, embedding vector)");
        try {
            // 二值量化：每维压成 1 bit，按 Hamming 距离排序，早已不是余弦序。
            jdbc.execute("create index on " + table
                    + " using hnsw ((binary_quantize(embedding)::bit(" + FROZEN_DIMENSION + "))"
                    + " bit_hamming_ops)");

            // 截断到前 2000 维：直接丢掉一半以上的信号。
            jdbc.execute("create index on " + table + " using hnsw ((subvector(embedding::vector("
                    + FROZEN_DIMENSION + "), 1, 2000)::vector(2000)) vector_cosine_ops)");
        } finally {
            jdbc.execute("drop table " + table);
        }
    }

    /**
     * 没有索引不等于不正确。顺序扫描在冻结维度上给出的就是精确余弦序，
     * 而这正是 D019 判断「MVP 语料规模下不需要索引」时所依赖的另一半事实。
     */
    @Test
    void sequentialScanReturnsExactCosineOrderAtTheFrozenDimension() {
        Fixture fixture = new Fixture();
        long onAxis = fixture.chunk(1);
        long between = fixture.chunk(2);
        long orthogonal = fixture.chunk(3);

        chunks.writeEmbedding(fixture.project, onAxis, axis(0));
        chunks.writeEmbedding(fixture.project, between, diagonal());
        chunks.writeEmbedding(fixture.project, orthogonal, axis(1));

        assertThat(chunks.establishedDimension(fixture.project)).isEqualTo(FROZEN_DIMENSION);
        assertThat(chunks.search(fixture.project, null, axis(0), 10))
                .extracting(ChunkSearchRepository.ChunkMatch::id)
                .containsExactly(onAxis, between, orthogonal);
    }

    private static float[] axis(int index) {
        float[] vector = new float[FROZEN_DIMENSION];
        vector[index] = 1f;
        return vector;
    }

    /** 与两条轴各成 45°，因此余弦距离严格落在它们之间。 */
    private static float[] diagonal() {
        float[] vector = new float[FROZEN_DIMENSION];
        vector[0] = 1f;
        vector[1] = 1f;
        return vector;
    }

    private final class Fixture {

        private final long project;
        private final long document;

        private Fixture() {
            Long owner = jdbc.queryForObject(
                    "insert into user_account (username, password_hash) values (?, 'x') returning id",
                    Long.class, "vi-" + SEQUENCE.incrementAndGet());
            this.project = jdbc.queryForObject(
                    "insert into project (name, created_by, status) values (?, ?, 'ACTIVE') returning id",
                    Long.class, "vip-" + SEQUENCE.incrementAndGet(), owner);
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
