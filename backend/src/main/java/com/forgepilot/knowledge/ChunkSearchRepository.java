package com.forgepilot.knowledge;

import java.util.List;
import java.util.StringJoiner;

import com.forgepilot.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The only place in the codebase allowed to name {@code ::vector} or the
 * {@code <=>} operator (D015.4). The vector column is not mapped by
 * {@link KnowledgeChunk}, so every read and write of it passes through here.
 *
 * <p>Cosine distance is the chosen operator (design.md 2.1). Whenever an
 * expression index is finally added it must use {@code vector_cosine_ops} and the
 * query's left-hand side must match the index definition exactly, or the index is
 * dead weight.
 */
@Repository
public class ChunkSearchRepository {

    private final JdbcTemplate jdbc;

    ChunkSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attaches a vector to a chunk, refusing any dimension that disagrees with the
     * rest of the project.
     *
     * <p>This check is the project's only real defence, not a nicety. Measured: a
     * dimensionless column accepts every dimension silently, and a single
     * mismatched row makes <em>every</em> similarity query in that project fail
     * with 22000 — one bad write poisons all retrieval until an index exists to
     * enforce dimensions, which D015.3 forbids in this batch. The database CHECK
     * only proves a row is self-consistent; it cannot see the other rows.
     */
    public void writeEmbedding(long projectId, long chunkId, float[] vector) {
        if (vector.length == 0) {
            throw ApiException.unprocessable("An embedding cannot be empty.");
        }
        Integer established = establishedDimension(projectId);
        if (established != null && established != vector.length) {
            throw ApiException.unprocessable("This project already stores " + established
                    + "-dimension embeddings; refusing to write a " + vector.length + "-dimension one.");
        }
        int updated = jdbc.update(
                "update knowledge_chunk set embedding = ?::vector, dimension = ? "
                        + "where project_id = ? and id = ?",
                literal(vector), vector.length, projectId, chunkId);
        if (updated == 0) {
            throw ApiException.notFound();
        }
    }

    /** The dimension this project has settled on, or null while it has none. */
    public Integer establishedDimension(long projectId) {
        return jdbc.query(
                "select dimension from knowledge_chunk "
                        + "where project_id = ? and embedding is not null limit 1",
                rs -> rs.next() ? rs.getInt(1) : null, projectId);
    }

    /**
     * Nearest chunks within one project. {@code projectId} is a hard filter rather
     * than a post-filter: measured, it is evaluated before the ordering expression,
     * so another project's rows cannot affect — or poison — this result.
     */
    public List<ChunkMatch> search(long projectId, float[] query, int limit) {
        return jdbc.query(
                "select id, document_id, seq, content, embedding <=> ?::vector as distance "
                        + "from knowledge_chunk "
                        + "where project_id = ? and embedding is not null "
                        + "order by embedding <=> ?::vector limit ?",
                (rs, row) -> new ChunkMatch(rs.getLong("id"), rs.getLong("document_id"),
                        rs.getInt("seq"), rs.getString("content"), rs.getDouble("distance")),
                literal(query), projectId, literal(query), limit);
    }

    /** pgvector's text form: {@code [1.0,2.0,3.0]}. */
    private static String literal(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }

    public record ChunkMatch(long id, long documentId, int seq, String content, double distance) {
    }
}
