package com.example.codereview.patch;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/agent-runs/{agentRunId}/patches")
public class PatchApprovalController {
    private final PatchApprovalService service; private final CurrentUserProvider users;
    public PatchApprovalController(PatchApprovalService service, CurrentUserProvider users) {
        this.service = service; this.users = users;
    }
    @PostMapping("/{patchId}/approval")
    public ApiResponse<Response> decide(@PathVariable Long projectId, @PathVariable Long agentRunId,
                                        @PathVariable Long patchId, @Valid @RequestBody Request request) {
        PatchApproval value = service.decide(projectId, patchId, agentRunId, users.getRequired().userId(),
                request.currentHeadSha(), request.decision(), request.comment());
        return ApiResponse.ok(Response.from(value));
    }
    @GetMapping
    public ApiResponse<java.util.List<PatchResponse>> list(@PathVariable Long projectId,
                                                            @PathVariable Long agentRunId) {
        return ApiResponse.ok(service.list(projectId, agentRunId, users.getRequired().userId())
                .stream().map(PatchResponse::from).toList());
    }
    public record Request(@NotBlank String currentHeadSha, @NotNull PatchApprovalDecision decision, String comment) {}
    public record Response(Long approvalId, Long patchCandidateId, Long approverId, PatchApprovalDecision decision,
                           String patchHash, String headSha, String comment, java.time.Instant decidedAt) {
        static Response from(PatchApproval a) { return new Response(a.getId(), a.getPatchCandidateId(), a.getApproverId(),
                a.getDecision(), a.getPatchHash(), a.getHeadSha(), a.getComment(), a.getDecidedAt()); }
    }
    public record PatchResponse(Long id, Long agentRunId, String headSha, String patchHash, String patchContent,
                                String status, String applyStatus, String buildStatus, String testStatus,
                                String scanStatus, boolean targetDisappeared, String validationLog,
                                String validationResultJson, java.util.Set<Long> findingIds, boolean stale) {
        static PatchResponse from(PatchCandidate p) { return new PatchResponse(p.getId(), p.getAgentRunId(),
                p.getHeadSha(), p.getPatchHash(), p.getPatchContent(), p.getStatus(), p.getApplyStatus(),
                p.getBuildStatus(), p.getTestStatus(), p.getScanStatus(), p.isTargetDisappeared(),
                p.getValidationLog(), p.getValidationResultJson(), p.getFindingIds(), false); }
    }
}
