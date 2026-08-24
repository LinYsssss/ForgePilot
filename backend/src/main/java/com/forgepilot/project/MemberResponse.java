package com.forgepilot.project;

import java.util.Set;

import com.forgepilot.auth.AccountView;

/** API 对外呈现的成员目录形态；SCM 身份由独立绑定响应提供。 */
public record MemberResponse(long userId, String username, String displayName, Set<ProjectRole> roles) {

    static MemberResponse of(ProjectMember member, AccountView account) {
        return new MemberResponse(member.getUserId(), account.username(), account.displayName(), member.getRoles());
    }
}
