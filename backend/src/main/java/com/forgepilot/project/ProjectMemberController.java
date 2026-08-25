package com.forgepilot.project;

import java.security.Principal;
import java.util.List;
import java.util.Set;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/candidates")
    List<MemberCandidateResponse> candidates(@PathVariable long projectId,
            @RequestParam String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Principal principal) {
        return members.search(projectId, userIdOf(principal), q, page, size);
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    List<MemberResponse> addBatch(@PathVariable long projectId,
            @Valid @RequestBody BatchRequest request, Principal principal) {
        return members.addBatch(projectId, userIdOf(principal), request.members().stream()
                .map(row -> new ProjectMemberService.BatchMember(row.userId(), row.roles())).toList());
    }

    @PatchMapping("/{userId}/roles")
    MemberResponse updateRoles(@PathVariable long projectId, @PathVariable long userId,
            @Valid @RequestBody RolesRequest request, Principal principal) {
        return members.updateRoles(projectId, userIdOf(principal), userId, request.roles());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable long projectId, @PathVariable long userId, Principal principal) {
        members.remove(projectId, userIdOf(principal), userId);
    }

    @PostMapping("/leader-transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void transferLeader(@PathVariable long projectId, @Valid @RequestBody LeaderTransferRequest request,
            Principal principal) {
        members.transferLeader(projectId, userIdOf(principal), request.targetUserId());
    }

    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    record BatchRequest(@NotEmpty @Size(max = 50) List<@Valid BatchRow> members) {
    }

    record BatchRow(long userId, @NotEmpty Set<@NotNull ProjectRole> roles) {
    }

    record RolesRequest(@NotEmpty Set<@NotNull ProjectRole> roles) {
    }

    record LeaderTransferRequest(long targetUserId, @AssertTrue boolean confirmed) {
    }
}
