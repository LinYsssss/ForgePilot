package com.forgepilot.project;

import com.forgepilot.auth.AccountView;

public record MemberCandidateResponse(long userId, String username, String displayName,
        boolean enabled, boolean alreadyMember) {

    static MemberCandidateResponse of(AccountView account, boolean alreadyMember) {
        return new MemberCandidateResponse(account.id(), account.username(), account.displayName(),
                account.enabled(), alreadyMember);
    }
}
