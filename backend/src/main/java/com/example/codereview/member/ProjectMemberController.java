package com.example.codereview.member;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.member.ProjectMemberDtos.AddMemberRequest;
import com.example.codereview.member.ProjectMemberDtos.MemberResponse;
import com.example.codereview.member.ProjectMemberDtos.TransferOwnerRequest;
import com.example.codereview.member.ProjectMemberDtos.UpdateMemberRoleRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/members")
public class ProjectMemberController {

    private final ProjectMemberService memberService;
    private final CurrentUserProvider currentUserProvider;

    public ProjectMemberController(ProjectMemberService memberService, CurrentUserProvider currentUserProvider) {
        this.memberService = memberService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public ApiResponse<List<MemberResponse>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(memberService.list(projectId, currentUserProvider.getRequired().userId()));
    }

    @PostMapping
    public ApiResponse<MemberResponse> add(@PathVariable Long projectId, @Valid @RequestBody AddMemberRequest request) {
        return ApiResponse.ok(memberService.add(projectId, currentUserProvider.getRequired().userId(), request));
    }

    @PutMapping("/{memberUserId}")
    public ApiResponse<MemberResponse> updateRole(@PathVariable Long projectId, @PathVariable Long memberUserId,
                                                  @Valid @RequestBody UpdateMemberRoleRequest request) {
        return ApiResponse.ok(memberService.updateRole(
                projectId, currentUserProvider.getRequired().userId(), memberUserId, request));
    }

    @DeleteMapping("/{memberUserId}")
    public ApiResponse<Void> remove(@PathVariable Long projectId, @PathVariable Long memberUserId) {
        memberService.remove(projectId, currentUserProvider.getRequired().userId(), memberUserId);
        return ApiResponse.ok();
    }

    @PostMapping("/transfer")
    public ApiResponse<Void> transfer(@PathVariable Long projectId, @Valid @RequestBody TransferOwnerRequest request) {
        memberService.transferOwner(projectId, currentUserProvider.getRequired().userId(), request);
        return ApiResponse.ok();
    }
}
