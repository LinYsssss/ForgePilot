package com.example.codereview.rag;

import com.example.codereview.knowledge.KnowledgeChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.rag", name = "mode", havingValue = "pgvector")
public class PgVectorIndexService implements VectorIndexService {

    private final JdbcTemplate jdbcTemplate;

    public PgVectorIndexService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void index(KnowledgeChunk chunk) {
        jdbcTemplate.update("""
                create extension if not exists vector
                """);
        jdbcTemplate.update("""
                create table if not exists knowledge_chunk_vector (
                    chunk_id bigint primary key,
                    project_id bigint not null,
                    embedding vector not null
                )
                """);
        jdbcTemplate.update("""
                insert into knowledge_chunk_vector(chunk_id, project_id, embedding)
                values (?, ?, cast(? as vector))
                on conflict (chunk_id)
                do update set project_id = excluded.project_id, embedding = excluded.embedding
                """, chunk.getId(), chunk.getProjectId(), chunk.getEmbeddingJson());
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        jdbcTemplate.update("""
                delete from knowledge_chunk_vector
                where chunk_id in (
                    select id from knowledge_chunk where document_id = ?
                )
                """, documentId);
    }
}
