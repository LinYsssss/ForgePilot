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
     * Sets or clears the pull request's requirement (PRD P1). PUT on the
     * sub-resource rather than PATCH on the pull request, because clearing is a
     * legal correction and a PATCH body cannot tell "leave it alone" apart from
     * "set it to nothing".
     */
    @PutMapping("/pull-requests/{pullRequestId}/requirement")
    PullRequestResponse setRequirement(@PathVariable long projectId, @PathVariable long pullRequestId,
            @Valid @RequestBody AssociationRequest request, Principal principal) {
        return associations.correct(projectId, userIdOf(principal), pullRequestId,
                request.requirementId(), request.reason());
    }

    /**
     * The login identity is resolved to a user id here, in the controller, through
     * the read-only account facade. Business services never see Spring Security
     * (ARCHITECTURE.md 1.3 as narrowed by D013.6).
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

    /** Every field is optional; a null one leaves that part of the connection alone. */
    record UpdateRequest(
            ScmProvider provider,
            @Size(max = 128) String externalId,
            @Size(max = 512) String apiBase,
            String token,
            String webhookSecret) {
    }

    /**
     * {@code requirementId} is nullable on purpose: null means "this pull request
     * implements no requirement", which is a correction the audit records like any
     * other. {@code reason} follows the column, which ARCHITECTURE.md 2.1 leaves
     * nullable — it is stored when given and never invented when not.
     */
    record AssociationRequest(Long requirementId, @Size(max = 500) String reason) {
    }
}
