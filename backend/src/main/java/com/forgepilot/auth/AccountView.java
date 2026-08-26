package com.forgepilot.auth;

/** 其他功能模块被允许看到的账号信息。绝不携带口令哈希。 */
public record AccountView(long id, String username, String displayName, boolean enabled) {

    static AccountView of(UserAccount account) {
        return new AccountView(account.getId(), account.getUsername(), account.getDisplayName(), account.isEnabled());
    }
}
