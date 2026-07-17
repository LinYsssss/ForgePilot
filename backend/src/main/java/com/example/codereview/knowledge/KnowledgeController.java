package com.example.codereview.knowledge;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.knowledge.KnowledgeDtos.DocumentResponse;
import com.example.codereview.knowledge.KnowledgeDtos.ReindexResponse;
import com.example.codereview.knowledge.KnowledgeDtos.SearchRequest;
import com.example.codereview.knowledge.KnowledgeDtos.SearchResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/projects/{projectId}/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final CurrentUserProvider currentUserProvider;

    public KnowledgeController(KnowledgeService knowledgeService, CurrentUserProvider currentUserProvider) {
        this.knowledgeService = knowledgeService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/documents")
    public ApiResponse<DocumentResponse> upload(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "README") String docType,
            @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.ok(knowledgeService.upload(projectId, currentUserProvider.getRequired().userId(), docType, file));
    }

    @GetMapping("/documents")
    public ApiResponse<List<DocumentResponse>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(knowledgeService.list(projectId, currentUserProvider.getRequired().userId()));
    }

    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId, @PathVariable Long documentId) {
        knowledgeService.delete(projectId, currentUserProvider.getRequired().userId(), documentId);
        return ApiResponse.ok();
    }

    @PostMapping("/search")
    public ApiResponse<SearchResponse> search(@PathVariable Long projectId, @Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(knowledgeService.search(
                projectId,
                currentUserProvider.getRequired().userId(),
                request.query(),
                request.topK()
        ));
    }

    @PostMapping("/reindex")
    public ApiResponse<ReindexResponse> reindex(@PathVariable Long projectId) {
        return ApiResponse.ok(knowledgeService.reindex(
                projectId,
                currentUserProvider.getRequired().userId()
        ));
    }
}
