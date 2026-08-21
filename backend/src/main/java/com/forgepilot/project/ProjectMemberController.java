package com.forgepilot.project;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/members")
class ProjectMemberController {

    private final ProjectMemberService members;
    private final UserDirectory users;

    ProjectMemberController(ProjectMemberService members, UserDirectory users) {
        this.members = members;
        this.users = users;
    }

    @GetMapping
    List<MemberResponse> list(@PathVariable long projectId, Principal principal) {
        return members.list(projectId, userIdOf(principal));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    MemberResponse add(@PathVariable long projectId, @Valid @RequestBody AddMemberRequest request,
            Principal principal) {
        return members.add(projectId, userIdOf(principal), request.username(), request.role());
    }

    @PatchMapping("/{userId}")
    MemberResponse update(@PathVariable long projectId, @PathVariable long userId,
            @Valid @RequestBody UpdateMemberRequest request, Principal principal) {
        return members.update(projectId, userIdOf(principal), userId, request.role(),
                request.scmExternalUserId(), request.scmUsername());
    }

    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    record AddMemberRequest(@NotBlank @Size(max = 64) String username, @NotNull ProjectRole role) {
    }

    /** Every field is optional: this endpoint carries both role changes and SCM identity. */
    record UpdateMemberRequest(ProjectRole role,
            @Size(max = 128) String scmExternalUserId,
            @Size(max = 128) String scmUsername) {
    }
}
