package com.example.codereview.knowledge;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.ai.AiCallLogService;
import com.example.codereview.knowledge.KnowledgeDtos.DocumentResponse;
import com.example.codereview.knowledge.KnowledgeDtos.ReindexResponse;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate reindexTransactions;

    public KnowledgeService(
            ProjectService projectService,
            KnowledgeDocumentRepository documents,
            KnowledgeChunkRepository chunks,
            RagService ragService,
            EmbeddingClient embeddingClient,
            EmbeddingJson embeddingJson,
            VectorIndexService vectorIndexService,
            AiCallLogService aiCallLogService,
            PlatformTransactionManager transactionManager,
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
        this.reindexTransactions = new TransactionTemplate(transactionManager);
        this.reindexTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    public ReindexResponse reindex(Long projectId, Long userId) {
        projectService.getRequired(projectId, userId);
        List<KnowledgeDocument> projectDocuments = documents.findByProjectIdOrderByCreatedAtDesc(projectId);
        if (fullContext) {
            return new ReindexResponse(projectDocuments.size(), 0, projectDocuments.size(), 0);
        }
        EmbeddingClient.EmbeddingDescriptor descriptor = embeddingClient.descriptor();
        int indexed = 0;
        int skipped = 0;
        int failed = 0;
        for (KnowledgeDocument document : projectDocuments) {
            if (isCurrent(document, descriptor)) {
                skipped++;
                continue;
            }
            try {
                reindexTransactions.executeWithoutResult(status -> {
                    vectorIndexService.deleteByDocumentId(document.getId());
                    chunks.deleteByDocumentId(document.getId());
                    index(document);
                    document.markIndexed();
                    documents.save(document);
                });
                indexed++;
            } catch (RuntimeException ex) {
                failed++;
                reindexTransactions.executeWithoutResult(status -> {
                    document.markFailed();
                    documents.save(document);
                });
            }
        }
        return new ReindexResponse(projectDocuments.size(), indexed, skipped, failed);
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
            EmbeddingClient.EmbeddingResult embedding = fullContext
                    ? null : embedForIndex(document.getProjectId(), part);
            KnowledgeChunk chunk = chunks.save(new KnowledgeChunk(
                    document.getId(),
                    document.getProjectId(),
                    document.getDocType(),
                    document.getFileName(),
                    i,
                    part,
                    embedding == null ? null : embeddingJson.write(embedding.vector()),
                    embedding == null ? null : embedding.provider(),
                    embedding == null ? null : embedding.model(),
                    embedding == null ? null : embedding.version(),
                    embedding == null ? null : embedding.dimension()
            ));
            if (!fullContext) {
                vectorIndexService.index(chunk);
            }
        }
    }

    private EmbeddingClient.EmbeddingResult embedForIndex(Long projectId, String text) {
        long start = System.nanoTime();
        try {
            EmbeddingClient.EmbeddingResult embedding = embeddingClient.embed(text);
            aiCallLogService.embeddingSuccess(
                    projectId,
                    AiCallLogService.EMBEDDING_INDEX,
                    text == null ? 0 : text.length(),
                    embedding.dimension(),
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

    private boolean isCurrent(
            KnowledgeDocument document,
            EmbeddingClient.EmbeddingDescriptor descriptor
    ) {
        List<KnowledgeChunk> documentChunks = chunks.findByProjectIdAndDocumentIdIn(
                document.getProjectId(),
                List.of(document.getId())
        );
        return !documentChunks.isEmpty() && documentChunks.stream().allMatch(chunk -> descriptor.matches(
                chunk.getEmbeddingProvider(),
                chunk.getEmbeddingModel(),
                chunk.getEmbeddingVersion(),
                chunk.getEmbeddingDimension()
        ));
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
