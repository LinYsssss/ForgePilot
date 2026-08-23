package com.forgepilot.knowledge;

import java.util.List;
import java.util.StringJoiner;

import com.forgepilot.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 全代码库中**唯一**允许出现 {@code ::vector} 与 {@code <=>} 运算符的地方
 * （D015.4）。向量列没有被 {@link KnowledgeChunk} 映射，因此对它的每一次
 * 读写都必须经过这里。
 *
 * <p>选定的距离度量是余弦距离（design.md 2.1）。将来一旦要加表达式索引，
 * 它必须使用 {@code vector_cosine_ops}，且查询的左侧表达式必须与索引定义
 * 完全一致，否则这个索引就是死重量。
 */
@Repository
public class ChunkSearchRepository {

    private final JdbcTemplate jdbc;

    ChunkSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 给某个分块挂上向量，并拒绝任何与该项目其余部分不一致的维度。
     *
     * <p>这道检查是本项目**唯一真正的防线**，不是锦上添花。实测：无维度约束的
     * 列会静默接受任何维度，而只要有一行维度不匹配，该项目里的<em>每一个</em>
     * 相似度查询都会以 22000 失败——一次坏写入就毒死全部检索，直到有索引来
     * 强制维度为止，而 D015.3 在本批次禁止建那个索引。数据库的 CHECK 只能证明
     * 单行自洽，它看不见其他行。
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

    /** 该项目已确定的向量维度；尚未确定时为 null。 */
    public Integer establishedDimension(long projectId) {
        return jdbc.query(
                "select dimension from knowledge_chunk "
                        + "where project_id = ? and embedding is not null limit 1",
                rs -> rs.next() ? rs.getInt(1) : null, projectId);
    }

    /**
     * 单个项目内的最近邻分块。{@code projectId} 是**硬过滤**而非后过滤：
     * 实测它在排序表达式之前求值，因此别的项目的行既不能影响、也不能毒化
     * 本次结果。
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

    /** pgvector 的文本形式：{@code [1.0,2.0,3.0]}。 */
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
