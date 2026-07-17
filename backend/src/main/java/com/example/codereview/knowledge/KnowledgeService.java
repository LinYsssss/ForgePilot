package com.example.codereview.knowledge;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.knowledge.KnowledgeDtos.DocumentResponse;
import com.example.codereview.knowledge.KnowledgeDtos.SearchResponse;
import com.example.codereview.project.ProjectService;
import com.example.codereview.rag.EmbeddingClient;
import com.example.codereview.rag.EmbeddingJson;
import com.example.codereview.rag.RagService;
import com.example.codereview.rag.VectorIndexService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeService {

    private final ProjectService projectService;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final RagService ragService;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingJson embeddingJson;
    private final VectorIndexService vectorIndexService;
    private final AiCallLogService aiCallLogService;
    private final int chunkSize;
    private final int overlap;
    private final boolean fullContext;

    public KnowledgeService(
            ProjectService projectService,
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks,
            RagService ragService,
            EmbeddingClient embeddingClient,
            EmbeddingJson embeddingJson,
            VectorIndexService vectorIndexService,
            AiCallLogService aiCallLogService,
            @Value("${app.rag.chunk-size}") int chunkSize,
            @Value("${app.rag.overlap}") int overlap,
            @Value("${app.rag.full-context}") boolean fullContext
    ) {
        this.projectService = projectService;
        this.documents = documents;
        this.chunks = chunks;
        this.ragService = ragService;
        this.embeddingClient = embeddingClient;
        this.embeddingJson = embeddingJson;
        this.vectorIndexService = vectorIndexService;
        this.aiCallLogService = aiCallLogService;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.fullContext = fullContext;
    }

    @Transactional
    public DocumentResponse upload(Long projectId, Long userId, String docType, MultipartFile file) {
        projectService.getRequired(projectId, userId);
        String content = readText(file);
        KnowledgeDocument document = new KnowledgeDocument(projectId, userId, docType, file.getOriginalFilename(), content);
        documents.save(document);
        try {
            index(document);
            document.markIndexed();
        } catch (RuntimeException ex) {
            document.markFailed();
            throw ex;
        }
        return DocumentResponse.from(document);
    }

    public List<DocumentResponse> list(Long projectId, Long userId) {
        projectService.getRequired(projectId, userId);
        return documents.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }

    public SearchResponse search(Long projectId, Long userId, String query, Integer topK) {
        projectService.getRequired(projectId, userId);
        return new SearchResponse(ragService.search(projectId, query, topK));
    }

    @Transactional
    public void delete(Long projectId, Long userId, Long documentId) {
        projectService.getRequired(projectId, userId);
        KnowledgeDocument document = documents.findById(documentId)
                .orElseThrow(() -> new BusinessException(404, "文档不存在"));
        if (!document.getProjectId().equals(projectId)) {
            throw new BusinessException(403, "无权删除该文档");
        }
        vectorIndexService.deleteByDocumentId(documentId);
        chunks.deleteByDocumentId(documentId);
        documents.delete(document);
    }

    private void index(KnowledgeDocument document) {
        List<String> parts = splitForIndexing(document.getContentText(), chunkSize, overlap);
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            String embedding = fullContext ? null : embeddingJson.write(embedForIndex(document.getProjectId(), part));
            KnowledgeChunk chunk = chunks.save(new KnowledgeChunk(
                    document.getId(),
                    document.getProjectId(),
                    document.getDocType(),
                    document.getFileName(),
                    i,
                    part,
                    embedding
            ));
            if (!fullContext) {
                vectorIndexService.index(chunk);
            }
        }
    }

    private List<Double> embedForIndex(Long projectId, String text) {
        long start = System.nanoTime();
        try {
            List<Double> embedding = embeddingClient.embed(text);
            aiCallLogService.embeddingSuccess(
                    projectId,
                    AiCallLogService.EMBEDDING_INDEX,
                    text == null ? 0 : text.length(),
                    embedding.size(),
                    elapsedMs(start)
            );
            return embedding;
        } catch (RuntimeException ex) {
            aiCallLogService.embeddingFailed(
                    projectId,
                    AiCallLogService.EMBEDDING_INDEX,
                    text == null ? 0 : text.length(),
                    elapsedMs(start),
                    ex.getMessage()
            );
            throw ex;
        }
    }

    static List<String> splitForIndexing(String text, int chunkSize, int overlap) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (normalized.isBlank()) {
            throw new BusinessException(400, "文档内容为空");
        }
        int safeChunkSize = Math.max(200, chunkSize);
        int safeOverlap = Math.max(0, Math.min(overlap, safeChunkSize / 2));
        List<String> structured = structuredBlocks(normalized, safeChunkSize);
        if (structured.size() > 1) {
            return structured;
        }
        return fixedChunks(normalized, safeChunkSize, safeOverlap);
    }

    private static List<String> structuredBlocks(String text, int chunkSize) {
        List<String> rawBlocks = List.of(text.split("\\n\\s*\\n"));
        if (rawBlocks.size() < 2) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : rawBlocks) {
            String block = raw.strip();
            boolean boundary = block.startsWith("#") || block.startsWith("```");
            if ((boundary || current.length() + block.length() + 2 > chunkSize) && !current.isEmpty()) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (block.length() > chunkSize) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                parts.addAll(fixedChunks(block, chunkSize, 0));
            } else {
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(block);
                if (block.startsWith("```") && block.endsWith("```") && block.length() > 3) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static List<String> fixedChunks(String normalized, int safeChunkSize, int safeOverlap) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + safeChunkSize);
            parts.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(0, end - safeOverlap);
        }
        return parts;
    }

    private String readText(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件为空");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!(filename.endsWith(".md") || filename.endsWith(".txt"))) {
            throw new BusinessException(400, "第一版仅支持 Markdown 和 TXT 文档");
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BusinessException(400, "读取文件失败");
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
