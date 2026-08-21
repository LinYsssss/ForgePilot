package com.forgepilot.auth;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

/**
 * The authenticated account. It carries the account id and the {@code session_version}
 * read while authenticating, so neither has to be looked up by username afterwards.
 *
 * <p>No authorities are granted: project roles are a project-scoped concept and stay
 * out of the global authority system, which only tells authenticated from anonymous
 * (design.md 5).
 */
final class AccountPrincipal extends User {

    private final long userId;
    private final int sessionVersion;

    AccountPrincipal(UserAccount account) {
        super(account.getUsername(), account.getPasswordHash(), account.isEnabled(),
                true, true, true, AuthorityUtils.NO_AUTHORITIES);
        this.userId = account.getId();
        this.sessionVersion = account.getSessionVersion();
    }

    long getUserId() {
        return this.userId;
    }

    int getSessionVersion() {
        return this.sessionVersion;
    }
}
