package com.forgepilot.scm;

import java.security.Principal;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
class ScmController {

    private final ScmRepositoryService repositories;
    private final PullRequestAssociationService associations;
    private final UserDirectory users;

    ScmController(ScmRepositoryService repositories, PullRequestAssociationService associations,
            UserDirectory users) {
        this.repositories = repositories;
        this.associations = associations;
        this.users = users;
    }

    @PostMapping("/scm/repositories")
    @ResponseStatus(HttpStatus.CREATED)
    ScmRepositoryResponse register(@PathVariable long projectId, @Valid @RequestBody RegisterRequest request,
            Principal principal) {
        return repositories.register(projectId, userIdOf(principal), request.provider(), request.externalId(),
                request.apiBase(), request.token(), request.webhookSecret());
    }

    @PatchMapping("/scm/repositories/{repositoryId}")
    ScmRepositoryResponse update(@PathVariable long projectId, @PathVariable long repositoryId,
            @Valid @RequestBody UpdateRequest request, Principal principal) {
        return repositories.update(projectId, userIdOf(principal), repositoryId, request.provider(),
                request.externalId(), request.apiBase(), request.token(), request.webhookSecret());
    }

    @GetMapping("/pull-requests/{pullRequestId}")
    PullRequestResponse pullRequest(@PathVariable long projectId, @PathVariable long pullRequestId,
            Principal principal) {
        return repositories.pullRequest(projectId, userIdOf(principal), pullRequestId);
    }

    /**
     * 设置或清除 PR 的关联需求（PRD P1）。用子资源上的 PUT 而不是 PR 上的
     * PATCH，因为「清除」是一次合法纠正，而 PATCH 的请求体无法区分
     * 「保持不变」与「设为无」。
     */
    @PutMapping("/pull-requests/{pullRequestId}/requirement")
    PullRequestResponse setRequirement(@PathVariable long projectId, @PathVariable long pullRequestId,
            @Valid @RequestBody AssociationRequest request, Principal principal) {
        return associations.correct(projectId, userIdOf(principal), pullRequestId,
                request.requirementId(), request.reason());
    }

    /**
     * 登录身份在这里——控制器层——通过只读账号 facade 解析为 user id。
     * 业务服务永远看不到 Spring Security
     * （ARCHITECTURE.md 1.3，并按 D013.6 收窄）。
     */
    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    record RegisterRequest(
            @NotNull ScmProvider provider,
            @NotBlank @Size(max = 128) String externalId,
            @NotBlank @Size(max = 512) String apiBase,
            @NotBlank String token,
            @NotBlank String webhookSecret) {
    }

    /** 所有字段都是可选的；某个字段为 null 表示该部分连接配置保持不变。 */
    record UpdateRequest(
            ScmProvider provider,
            @Size(max = 128) String externalId,
            @Size(max = 512) String apiBase,
            String token,
            String webhookSecret) {
    }

    /**
     * {@code requirementId} 可空是有意为之：null 表示「这个 PR 不实现任何需求」，
     * 而这与其他纠正一样会被审计记录下来。{@code reason} 跟随对应的列，
     * ARCHITECTURE.md 2.1 让该列可空——给了就存，没给也绝不凭空编造。
     */
    record AssociationRequest(Long requirementId, @Size(max = 500) String reason) {
    }
}
