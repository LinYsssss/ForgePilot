package com.example.codereview.finding;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.api.PageResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.finding.AgentFindingDtos.AgentFindingResponse;
import com.example.codereview.finding.AgentFindingDtos.AssignRequest;
import com.example.codereview.finding.AgentFindingDtos.LifecycleRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/findings")
public class ProjectFindingController {

    private final FindingLifecycleService service;
    private final CurrentUserProvider currentUserProvider;

    public ProjectFindingController(FindingLifecycleService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public ApiResponse<PageResponse<AgentFindingResponse>> list(
            @PathVariable Long projectId,
            @RequestParam(required = false) String lifecycle,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(service.listByProject(
                projectId, currentUserProvider.getRequired().userId(), lifecycle, page, size));
    }

    @PostMapping("/{findingId}/lifecycle")
    public ApiResponse<AgentFindingResponse> transition(
            @PathVariable Long projectId,
            @PathVariable Long findingId,
            @Valid @RequestBody LifecycleRequest request) {
        return ApiResponse.ok(service.transition(
                projectId, currentUserProvider.getRequired().userId(), findingId,
                request.action(), request.fixCommitSha()));
    }

    @PostMapping("/{findingId}/assign")
    public ApiResponse<AgentFindingResponse> assign(
            @PathVariable Long projectId,
            @PathVariable Long findingId,
            @Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(service.assign(
                projectId, currentUserProvider.getRequired().userId(), findingId, request.userId()));
    }
}
