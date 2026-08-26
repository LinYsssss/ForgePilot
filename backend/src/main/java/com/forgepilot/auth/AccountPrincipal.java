package com.forgepilot.auth;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

/**
 * 已认证的账号。它携带账号 id 与认证过程中读到的 {@code session_version}，
 * 使二者此后都不必再按用户名去查一次。
 *
 * <p>不授予任何 authority：项目角色是项目内的概念，不进入全局 authority 体系——
 * 后者只区分「已认证」与「匿名」。
 */
final class AccountPrincipal extends User {

    private final long userId;
    private final int sessionVersion;
    private final String displayName;

    AccountPrincipal(UserAccount account) {
        super(account.getUsername(), account.getPasswordHash(), account.isEnabled(),
                true, true, true, AuthorityUtils.NO_AUTHORITIES);
        this.userId = account.getId();
        this.sessionVersion = account.getSessionVersion();
        this.displayName = account.getDisplayName();
    }

    long getUserId() {
        return this.userId;
    }

    int getSessionVersion() {
        return this.sessionVersion;
    }

    String getDisplayName() {
        return this.displayName;
    }
}
