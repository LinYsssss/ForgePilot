package com.forgepilot.auth;

/** What other features are allowed to see of an account (D013.6). Never carries the password hash. */
public record AccountView(long id, String username, boolean enabled) {

    static AccountView of(UserAccount account) {
        return new AccountView(account.getId(), account.getUsername(), account.isEnabled());
    }
}
