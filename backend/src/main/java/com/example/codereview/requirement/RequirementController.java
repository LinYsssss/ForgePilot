package com.example.codereview.requirement;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.api.PageResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.requirement.RequirementCheckDtos.CheckReportResponse;
import com.example.codereview.requirement.RequirementDtos.AssignRequest;
import com.example.codereview.requirement.RequirementLinkService.LinkResponse;
import com.example.codereview.requirement.RequirementLinkService.LookupResponse;
import com.example.codereview.requirement.RequirementDtos.RequirementDetail;
import com.example.codereview.requirement.RequirementDtos.RequirementSummary;
import com.example.codereview.requirement.RequirementDtos.SaveRequirementRequest;
import com.example.codereview.requirement.RequirementDtos.StatusRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final RequirementCheckService requirementCheckService;
    private final RequirementLinkService requirementLinkService;
    private final CurrentUserProvider currentUserProvider;

    public RequirementController(RequirementService requirementService,
                                 RequirementCheckService requirementCheckService,
                                 RequirementLinkService requirementLinkService,
                                 CurrentUserProvider currentUserProvider) {
        this.requirementService = requirementService;
        this.requirementCheckService = requirementCheckService;
        this.requirementLinkService = requirementLinkService;
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

    @PostMapping("/{requirementId}/check")
    public ApiResponse<CheckReportResponse> check(@PathVariable Long projectId, @PathVariable Long requirementId) {
        return ApiResponse.ok(requirementCheckService.check(
                projectId, currentUserProvider.getRequired().userId(), requirementId));
    }

    @GetMapping("/{requirementId}/check-reports")
    public ApiResponse<List<CheckReportResponse>> checkReports(@PathVariable Long projectId,
                                                               @PathVariable Long requirementId) {
        return ApiResponse.ok(requirementCheckService.listReports(
                projectId, currentUserProvider.getRequired().userId(), requirementId));
    }

    public record AddLinkRequest(String type, String ref) {
    }

    @GetMapping("/{requirementId}/links")
    public ApiResponse<List<LinkResponse>> links(@PathVariable Long projectId, @PathVariable Long requirementId) {
        return ApiResponse.ok(requirementLinkService.list(
                projectId, currentUserProvider.getRequired().userId(), requirementId));
    }

    @PostMapping("/{requirementId}/links")
    public ApiResponse<LinkResponse> addLink(@PathVariable Long projectId, @PathVariable Long requirementId,
                                             @RequestBody AddLinkRequest request) {
        return ApiResponse.ok(requirementLinkService.addManual(
                projectId, currentUserProvider.getRequired().userId(), requirementId,
                request.type(), request.ref()));
    }

    @DeleteMapping("/{requirementId}/links/{linkId}")
    public ApiResponse<Void> removeLink(@PathVariable Long projectId, @PathVariable Long requirementId,
                                        @PathVariable Long linkId) {
        requirementLinkService.remove(
                projectId, currentUserProvider.getRequired().userId(), requirementId, linkId);
        return ApiResponse.ok();
    }

    /** 反查(四问入口):这个分支/commit/PR 属于哪些需求。 */
    @GetMapping("/links/lookup")
    public ApiResponse<List<LookupResponse>> lookup(@PathVariable Long projectId,
                                                    @RequestParam String type,
                                                    @RequestParam String ref) {
        return ApiResponse.ok(requirementLinkService.lookup(
                projectId, currentUserProvider.getRequired().userId(), type, ref));
    }
}
