package com.forgepilot.requirement;

import java.security.Principal;
import java.util.List;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/requirements")
class RequirementController {

    private final RequirementService requirements;
    private final ImplementationGuidanceService guidance;
    private final RequirementQualityService quality;
    private final UserDirectory users;

    RequirementController(RequirementService requirements, ImplementationGuidanceService guidance,
            RequirementQualityService quality, UserDirectory users) {
        this.requirements = requirements;
        this.guidance = guidance;
        this.quality = quality;
        this.users = users;
    }

    @GetMapping
    List<RequirementSummary> list(@PathVariable long projectId, Principal principal) {
        return requirements.list(projectId, userIdOf(principal));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RequirementDetail create(@PathVariable long projectId, @Valid @RequestBody RequirementContent request,
            Principal principal) {
        return requirements.create(projectId, userIdOf(principal), request);
    }

    @GetMapping("/{requirementId}")
    RequirementDetail get(@PathVariable long projectId, @PathVariable long requirementId, Principal principal) {
        return requirements.get(projectId, userIdOf(principal), requirementId);
    }

    @PatchMapping("/{requirementId}")
    RequirementDetail editDraft(@PathVariable long projectId, @PathVariable long requirementId,
            @Valid @RequestBody RequirementContent request, Principal principal) {
        return requirements.editDraft(projectId, userIdOf(principal), requirementId, request);
    }

    @GetMapping("/{requirementId}/revisions")
    List<RevisionView> revisions(@PathVariable long projectId, @PathVariable long requirementId,
            Principal principal) {
        return requirements.listRevisions(projectId, userIdOf(principal), requirementId);
    }

    @PostMapping("/{requirementId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    RequirementDetail publishRevision(@PathVariable long projectId, @PathVariable long requirementId,
            @Valid @RequestBody PublishRevisionRequest request, Principal principal) {
        return requirements.publishRevision(projectId, userIdOf(principal), requirementId,
                request.content(), request.changeReason());
    }

    @PostMapping("/{requirementId}/status")
    RequirementDetail changeStatus(@PathVariable long projectId, @PathVariable long requirementId,
            @Valid @RequestBody StatusRequest request, Principal principal) {
        return requirements.changeStatus(projectId, userIdOf(principal), requirementId, request.status());
    }

    @PostMapping("/{requirementId}/assignee")
    RequirementDetail assign(@PathVariable long projectId, @PathVariable long requirementId,
            @Valid @RequestBody AssigneeRequest request, Principal principal) {
        return requirements.assign(projectId, userIdOf(principal), requirementId, request.userId());
    }

    /**
     * One-shot implementation guidance for the requirement's current revision.
     * POST rather than GET because it spends a provider call: it is neither safe
     * nor cacheable, and nothing about it is stored to read back later.
     */
    @PostMapping("/{requirementId}/guidance")
    ImplementationGuidance generateGuidance(@PathVariable long projectId,
            @PathVariable long requirementId, Principal principal) {
        return guidance.generate(projectId, userIdOf(principal), requirementId);
    }

    /**
     * Requirement Quality: deterministic rules plus one structured AI call
     * (api-contract 4). POST because it spends a provider call and writes the
     * result onto the current revision. The answer is advice — this endpoint
     * never moves the requirement's status (PRD 5).
     */
    @PostMapping("/{requirementId}/quality")
    QualityReport checkQuality(@PathVariable long projectId, @PathVariable long requirementId,
            Principal principal) {
        return quality.check(projectId, userIdOf(principal), requirementId);
    }

    /**
     * The login identity is resolved to a user id here, in the controller, through
     * the read-only account facade. Business services never see Spring Security
     * and this feature never depends on how a session is established
     * (ARCHITECTURE.md 1.3 as narrowed by D013.6).
     */
    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    /** The wire shape of api-contract 3: {@code EditDraft} plus the mandatory reason. */
    record PublishRevisionRequest(
            @NotBlank @Size(max = 200) String title,
            String background,
            String description,
            @NotEmpty @Valid List<CriterionInput> acceptanceCriteria,
            String changeReason) {

        RequirementContent content() {
            return new RequirementContent(title, background, description, acceptanceCriteria);
        }
    }

    record StatusRequest(@NotNull RequirementStatus status) {
    }

    record AssigneeRequest(@NotNull Long userId) {
    }
}
