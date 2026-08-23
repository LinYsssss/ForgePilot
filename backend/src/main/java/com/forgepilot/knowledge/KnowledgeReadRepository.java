package com.forgepilot.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 知识页面的聚合读取。dimension 与 embedding 有意不映射到实体，故这里使用一次
 * 分组 SQL 产出状态与向量档案，而不是暴露另一套存储模型。
 */
@Repository
class KnowledgeReadRepository {

    private static final String SELECT = """
            select d.id, d.project_id, d.source_type, d.source_requirement_id, d.title, d.status,
                   d.failure_reason, d.created_at, d.updated_at,
                   count(c.id) as chunk_count,
                   count(c.embedding) as embedded_chunk_count,
                   max(c.dimension) as embedding_dimension,
                   max(c.provider) as embedding_provider,
                   max(c.model) as embedding_model,
                   max(c.version) as embedding_version
              from knowledge_document d
              left join knowledge_chunk c on c.project_id = d.project_id and c.document_id = d.id
            """;

    private static final String GROUP_BY = """
             group by d.id, d.project_id, d.source_type, d.source_requirement_id, d.title, d.status,
                      d.failure_reason, d.created_at, d.updated_at
             order by d.updated_at desc, d.id desc
            """;

    private final JdbcTemplate jdbc;

    KnowledgeReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<KnowledgeDocumentView> findProjectKnowledge(long projectId) {
        return jdbc.query(SELECT + "where d.project_id = ? and d.source_type = 'PROJECT_KNOWLEDGE'"
                + GROUP_BY, this::map, projectId);
    }

    KnowledgeDocumentView findByProjectIdAndId(long projectId, long documentId) {
        return jdbc.query(SELECT + "where d.project_id = ? and d.id = ?" + GROUP_BY, this::map,
                projectId, documentId).stream().findFirst().orElse(null);
    }

    List<KnowledgeDocumentView> findByProjectIdAndIds(long projectId, Collection<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        Object[] parameters = new Object[documentIds.size() + 1];
        parameters[0] = projectId;
        int index = 1;
        for (Long documentId : documentIds) {
            parameters[index++] = documentId;
        }
        return jdbc.query(SELECT + "where d.project_id = ? and d.id in (" + placeholders + ")"
                + GROUP_BY, this::map, parameters);
    }

    private KnowledgeDocumentView map(ResultSet rs, int row) throws SQLException {
        return new KnowledgeDocumentView(rs.getLong("id"), rs.getLong("project_id"),
                KnowledgeSourceType.valueOf(rs.getString("source_type")),
                rs.getObject("source_requirement_id", Long.class), rs.getString("title"),
                KnowledgeStatus.valueOf(rs.getString("status")), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("chunk_count"), rs.getLong("embedded_chunk_count"),
                rs.getObject("embedding_dimension", Integer.class), rs.getString("embedding_provider"),
                rs.getString("embedding_model"), rs.getString("embedding_version"));
    }
}
