package com.example.codereview.rag;

import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.knowledge.KnowledgeChunk;
import com.example.codereview.knowledge.KnowledgeChunkRepository;
import com.example.codereview.knowledge.KnowledgeDtos.SearchMatch;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RagService {

    private final KnowledgeChunkRepository chunks;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingJson embeddingJson;
    private final JdbcTemplate jdbcTemplate;
    private final AiCallLogService aiCallLogService;
    private final String mode;
    private final int defaultTopK;
    private final boolean fullContext;
    private final int maxContextChars;

    public RagService(
            KnowledgeChunkRepository chunks,
            EmbeddingClient embeddingClient,
            EmbeddingJson embeddingJson,
            ObjectProvider<JdbcTemplate> jdbcTemplate,
            AiCallLogService aiCallLogService,
            @Value("${app.rag.mode}") String mode,
            @Value("${app.rag.top-k}") int defaultTopK,
            @Value("${app.rag.full-context}") boolean fullContext,
            @Value("${app.rag.max-context-chars}") int maxContextChars
    ) {
        this.chunks = chunks;
        this.embeddingClient = embeddingClient;
        this.embeddingJson = embeddingJson;
        this.jdbcTemplate = jdbcTemplate.getIfAvailable();
        this.aiCallLogService = aiCallLogService;
        this.mode = mode;
        this.defaultTopK = defaultTopK;
        this.fullContext = fullContext;
        this.maxContextChars = maxContextChars;
    }

    public List<SearchMatch> search(Long projectId, String query, Integer topK) {
        int limit = topK == null || topK <= 0 ? defaultTopK : topK;
        return scopedSearch(projectId, query, limit, null);
    }

    public List<SearchMatch> search(Long projectId, String query, Integer topK, Collection<Long> documentIds) {
        int limit = topK == null || topK <= 0 ? defaultTopK : topK;
        return scopedSearch(projectId, query, limit, documentIds);
    }

    public String buildContext(Long projectId, String query) {
        return buildContext(projectId, query, null);
    }

    public String buildContext(Long projectId, String query, Collection<Long> documentIds) {
        if (fullContext) {
            return buildFullContext(projectId, documentIds);
        }
        return scopedSearch(projectId, query, defaultTopK, documentIds)
                .stream()
                .map(match -> "[来源: " + match.sourceName() + "#" + match.chunkIndex() + ", score=" + match.score() + "]\n" + match.content())
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private List<KnowledgeChunk> chunksFor(Long projectId, Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return chunks.findByProjectId(projectId);
        }
        return chunks.findByProjectIdAndDocumentIdIn(projectId, documentIds);
    }

    private String buildFullContext(Long projectId, Collection<Long> documentIds) {
        String context = chunksFor(projectId, documentIds)
                .stream()
                .sorted(Comparator.comparing(KnowledgeChunk::getSourceName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(KnowledgeChunk::getChunkIndex))
                .map(chunk -> "[来源: " + chunk.getSourceName() + "#" + chunk.getChunkIndex() + "]\n" + chunk.getContent())
                .collect(Collectors.joining("\n\n---\n\n"));
        if (maxContextChars > 0 && context.length() > maxContextChars) {
            return context.substring(0, maxContextChars) + "\n\n[项目上下文已截断，超过最大长度 " + maxContextChars + "]";
        }
        return context;
    }

    private List<SearchMatch> scopedSearch(Long projectId, String query, int limit, Collection<Long> documentIds) {
        if (fullContext) {
            return chunksFor(projectId, documentIds)
                    .stream()
                    .sorted(Comparator.comparing(KnowledgeChunk::getSourceName, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparingInt(KnowledgeChunk::getChunkIndex))
                    .limit(limit)
                    .map(this::toMatch)
                    .toList();
        }
        List<Double> queryEmbedding = embedForSearch(projectId, query);
        if ("pgvector".equalsIgnoreCase(mode) && jdbcTemplate != null && (documentIds == null || documentIds.isEmpty())) {
            return searchPgVector(projectId, queryEmbedding, limit);
        }
        return chunksFor(projectId, documentIds)
                .stream()
                .map(chunk -> new ScoredChunk(chunk, cosine(queryEmbedding, embeddingJson.read(chunk.getEmbeddingJson()))))
                .filter(scored -> scored.score > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(limit)
                .map(scored -> toMatch(scored.chunk, scored.score))
                .toList();
    }

    private SearchMatch toMatch(KnowledgeChunk chunk) {
        return toMatch(chunk, 1.0);
    }

    private SearchMatch toMatch(KnowledgeChunk chunk, double score) {
        return new SearchMatch(
                chunk.getId(),
                chunk.getSourceName(),
                chunk.getDocType(),
                chunk.getChunkIndex(),
                score,
                chunk.getContent()
        );
    }

    private List<SearchMatch> searchPgVector(Long projectId, List<Double> queryEmbedding, int limit) {
        String vector = embeddingJson.toPgVector(queryEmbedding);
        return jdbcTemplate.query("""
                        select kc.id, kc.source_name, kc.doc_type, kc.chunk_index, kc.content,
                               1 - (kv.embedding <=> cast(? as vector)) as score
                        from knowledge_chunk kc
                        join knowledge_chunk_vector kv on kv.chunk_id = kc.id
                        where kc.project_id = ?
                        order by kv.embedding <=> cast(? as vector)
                        limit ?
                        """,
                (rs, rowNum) -> new SearchMatch(
                        rs.getLong("id"),
                        rs.getString("source_name"),
                        rs.getString("doc_type"),
                        rs.getInt("chunk_index"),
                        rs.getDouble("score"),
                        rs.getString("content")
                ),
                vector,
                projectId,
                vector,
                limit
        );
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private List<Double> embedForSearch(Long projectId, String query) {
        long start = System.nanoTime();
        try {
            List<Double> embedding = embeddingClient.embed(query);
            aiCallLogService.embeddingSuccess(
                    projectId,
                    AiCallLogService.EMBEDDING_SEARCH,
                    query == null ? 0 : query.length(),
                    embedding.size(),
                    elapsedMs(start)
            );
            return embedding;
        } catch (RuntimeException ex) {
            aiCallLogService.embeddingFailed(
                    projectId,
                    AiCallLogService.EMBEDDING_SEARCH,
                    query == null ? 0 : query.length(),
                    elapsedMs(start),
                    ex.getMessage()
            );
            throw ex;
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record ScoredChunk(KnowledgeChunk chunk, double score) {
    }
}
