package com.forgepilot.project;

import java.time.Instant;

/**
 * API 对外呈现的成员形态。{@code scmUsername} 仅供展示：
 * 任何地方的授权判断都不得读取它（D010）。
 */
public record MemberResponse(long userId, String username, ProjectRole role,
        String scmExternalUserId, String scmUsername, Instant scmIdentityVerifiedAt) {

    static MemberResponse of(ProjectMember member, String username) {
        return new MemberResponse(member.getUserId(), username, member.getRole(),
                member.getScmExternalUserId(), member.getScmUsername(),
                member.getScmIdentityVerifiedAt());
    }
}
