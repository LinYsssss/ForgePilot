package com.forgepilot.project;

import java.time.Instant;

/**
 * A member as the API shows it. {@code scmUsername} is display only: no
 * authorization decision anywhere may read it (D010).
 */
public record MemberResponse(long userId, String username, ProjectRole role,
        String scmExternalUserId, String scmUsername, Instant scmIdentityVerifiedAt) {

    static MemberResponse of(ProjectMember member, String username) {
        return new MemberResponse(member.getUserId(), username, member.getRole(),
                member.getScmExternalUserId(), member.getScmUsername(),
                member.getScmIdentityVerifiedAt());
    }
}
