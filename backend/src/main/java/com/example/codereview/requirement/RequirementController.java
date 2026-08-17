package com.example.codereview.requirement;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.api.PageResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.requirement.RequirementDtos.AssignRequest;
import com.example.codereview.requirement.RequirementDtos.RequirementDetail;
import com.example.codereview.requirement.RequirementDtos.RequirementSummary;
import com.example.codereview.requirement.RequirementDtos.SaveRequirementRequest;
import com.example.codereview.requirement.RequirementDtos.StatusRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/requirements")
public class RequirementController {

    private final RequirementService requirementService;
    private final CurrentUserProvider currentUserProvider;

    public RequirementController(RequirementService requirementService, CurrentUserProvider currentUserProvider) {
        this.requirementService = requirementService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ApiResponse<RequirementDetail> create(@PathVariable Long projectId,
                                                 @Valid @RequestBody SaveRequirementRequest request) {
        return ApiResponse.ok(requirementService.create(
                projectId, currentUserProvider.getRequired().userId(), request));
    }

    @GetMapping
    public ApiResponse<PageResponse<RequirementSummary>> list(@PathVariable Long projectId,
                                                              @RequestParam(required = false) String status,
                                                              @RequestParam(required = false) Integer page,
                                                              @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(requirementService.list(
                projectId, currentUserProvider.getRequired().userId(), status, page, size));
    }

    @GetMapping("/{requirementId}")
    public ApiResponse<RequirementDetail> detail(@PathVariable Long projectId, @PathVariable Long requirementId) {
        return ApiResponse.ok(requirementService.detail(
                projectId, currentUserProvider.getRequired().userId(), requirementId));
    }

    @PutMapping("/{requirementId}")
    public ApiResponse<RequirementDetail> update(@PathVariable Long projectId, @PathVariable Long requirementId,
                                                 @Valid @RequestBody SaveRequirementRequest request) {
        return ApiResponse.ok(requirementService.update(
                projectId, currentUserProvider.getRequired().userId(), requirementId, request));
    }

    @PostMapping("/{requirementId}/assign")
    public ApiResponse<RequirementDetail> assign(@PathVariable Long projectId, @PathVariable Long requirementId,
                                                 @Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(requirementService.assign(
                projectId, currentUserProvider.getRequired().userId(), requirementId, request));
    }

    @PostMapping("/{requirementId}/status")
    public ApiResponse<RequirementDetail> transition(@PathVariable Long projectId, @PathVariable Long requirementId,
                                                     @Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(requirementService.transition(
                projectId, currentUserProvider.getRequired().userId(), requirementId, request));
    }
}
