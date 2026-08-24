package com.forgepilot.scm;

import java.security.Principal;
import java.util.List;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/scm")
class ScmBindingController {
    private final ScmBindingService bindings;
    private final UserDirectory users;

    ScmBindingController(ScmBindingService bindings, UserDirectory users) {
        this.bindings = bindings;
        this.users = users;
    }

    @GetMapping("/binding-options")
    List<ScmIdentityResponse> options(@PathVariable long projectId, Principal principal) {
        return bindings.options(projectId, userIdOf(principal));
    }

    @GetMapping("/bindings")
    List<ScmBindingResponse> list(@PathVariable long projectId, Principal principal) {
        return bindings.list(projectId, userIdOf(principal));
    }

    @PostMapping("/bindings")
    @ResponseStatus(HttpStatus.CREATED)
    ScmBindingResponse bind(@PathVariable long projectId, @Valid @RequestBody BindRequest request,
            Principal principal) {
        return bindings.bind(projectId, userIdOf(principal), request.identityId(), request.oneTimeToken());
    }

    @PostMapping("/bindings/{bindingId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(@PathVariable long projectId, @PathVariable long bindingId, Principal principal) {
        bindings.decide(projectId, userIdOf(principal), bindingId, true);
    }

    @PostMapping("/bindings/{bindingId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@PathVariable long projectId, @PathVariable long bindingId, Principal principal) {
        bindings.decide(projectId, userIdOf(principal), bindingId, false);
    }

    @PostMapping("/bindings/{bindingId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable long projectId, @PathVariable long bindingId, Principal principal) {
        bindings.revoke(projectId, userIdOf(principal), bindingId);
    }

    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    record BindRequest(long identityId, @NotBlank String oneTimeToken) {
        @Override public String toString() {
            return "BindRequest[identityId=" + identityId + ", oneTimeToken=[REDACTED]]";
        }
    }
}
