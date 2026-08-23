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
     * 针对需求当前修订的一次性实现建议。用 POST 而非 GET，因为它会花掉一次
     * provider 调用：既非安全方法也不可缓存，而且它的结果完全不落库、无法回读。
     */
    @PostMapping("/{requirementId}/guidance")
    ImplementationGuidance generateGuidance(@PathVariable long projectId,
            @PathVariable long requirementId, Principal principal) {
        return guidance.generate(projectId, userIdOf(principal), requirementId);
    }

    /**
     * 需求质量检查：确定性规则加一次结构化 AI 调用（api-contract 4）。
     * 用 POST，因为它会花掉一次 provider 调用并把结果写到当前修订上。
     * 这个答案是建议——本端点从不改动需求状态（PRD 5）。
     */
    @PostMapping("/{requirementId}/quality")
    QualityReport checkQuality(@PathVariable long projectId, @PathVariable long requirementId,
            Principal principal) {
        return quality.check(projectId, userIdOf(principal), requirementId);
    }

    /**
     * 登录身份在这里——控制器层——通过只读账号 facade 解析为 user id。
     * 业务服务永远看不到 Spring Security，本功能模块也不依赖会话是如何建立的
     * （ARCHITECTURE.md 1.3，并按 D013.6 收窄）。
     */
    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    /** api-contract 3 的线上形态：{@code EditDraft} 再加上必填的变更原因。 */
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
