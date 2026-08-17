package com.example.codereview.member;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ProjectMemberDtos {

    private ProjectMemberDtos() {
    }

    public record AddMemberRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 32) String role
    ) {
    }

    public record UpdateMemberRoleRequest(
            @NotBlank @Size(max = 32) String role
    ) {
    }

    public record TransferOwnerRequest(
            @NotNull Long userId
    ) {
    }

    public record MemberResponse(
            Long userId,
            String username,
            String nickname,
            String role,
            boolean owner,
            Instant joinedAt
    ) {
    }
}
