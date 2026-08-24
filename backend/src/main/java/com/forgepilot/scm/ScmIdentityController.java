package com.forgepilot.scm;

import java.security.Principal;
import java.util.List;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scm/identities")
class ScmIdentityController {
    private final ScmIdentityService identities;
    private final UserDirectory users;

    ScmIdentityController(ScmIdentityService identities, UserDirectory users) {
        this.identities = identities;
        this.users = users;
    }

    @GetMapping
    List<ScmIdentityResponse> list(Principal principal) {
        return identities.list(userIdOf(principal));
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.CREATED)
    ScmIdentityResponse verify(@Valid @RequestBody VerifyRequest request, Principal principal) {
        return identities.verify(userIdOf(principal), request.provider(), request.apiBase(),
                request.oneTimeToken(), request.label(), request.usageType());
    }

    @PatchMapping("/{identityId}")
    ScmIdentityResponse update(@PathVariable long identityId, @Valid @RequestBody UpdateRequest request,
            Principal principal) {
        return identities.update(userIdOf(principal), identityId, request.label(), request.usageType());
    }

    @DeleteMapping("/{identityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable long identityId, Principal principal) {
        identities.revoke(userIdOf(principal), identityId);
    }

    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    record VerifyRequest(@NotNull ScmProvider provider, @NotBlank @Size(max = 512) String apiBase,
            @NotBlank String oneTimeToken, @NotBlank @Size(max = 120) String label,
            @NotNull ScmIdentityUsage usageType) {
        @Override public String toString() {
            return "VerifyRequest[provider=" + provider + ", apiBase=" + apiBase
                    + ", oneTimeToken=[REDACTED], label=" + label + ", usageType=" + usageType + "]";
        }
    }

    record UpdateRequest(@NotBlank @Size(max = 120) String label,
            @NotNull ScmIdentityUsage usageType) {
    }
}
