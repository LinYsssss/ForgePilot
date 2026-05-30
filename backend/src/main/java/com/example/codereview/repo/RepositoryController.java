package com.example.codereview.repo;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.repo.RepositoryDtos.BindRepositoryRequest;
import com.example.codereview.repo.RepositoryDtos.CommitDiffResponse;
import com.example.codereview.repo.RepositoryDtos.CommitResponse;
import com.example.codereview.repo.RepositoryDtos.RepositoryResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/repository")
public class RepositoryController {

    private final RepositoryService repositoryService;
    private final CurrentUserProvider currentUserProvider;

    public RepositoryController(RepositoryService repositoryService, CurrentUserProvider currentUserProvider) {
        this.repositoryService = repositoryService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ApiResponse<RepositoryResponse> bind(@PathVariable Long projectId, @Valid @RequestBody BindRepositoryRequest request) {
        return ApiResponse.ok(repositoryService.bind(projectId, currentUserProvider.getRequired().userId(), request));
    }

    @GetMapping
    public ApiResponse<RepositoryResponse> detail(@PathVariable Long projectId) {
        return ApiResponse.ok(repositoryService.detail(projectId, currentUserProvider.getRequired().userId()));
    }

    @DeleteMapping
    public ApiResponse<Void> unbind(@PathVariable Long projectId) {
        repositoryService.unbind(projectId, currentUserProvider.getRequired().userId());
        return ApiResponse.ok();
    }

    @GetMapping("/commits")
    public ApiResponse<List<CommitResponse>> commits(@PathVariable Long projectId, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(repositoryService.commits(projectId, currentUserProvider.getRequired().userId(), limit));
    }

    @GetMapping("/commits/{commitId}/diff")
    public ApiResponse<CommitDiffResponse> diff(
            @PathVariable Long projectId,
            @PathVariable String commitId,
            @RequestParam(required = false) String baseCommitId
    ) {
        return ApiResponse.ok(repositoryService.diff(projectId, currentUserProvider.getRequired().userId(), commitId, baseCommitId));
    }
}
