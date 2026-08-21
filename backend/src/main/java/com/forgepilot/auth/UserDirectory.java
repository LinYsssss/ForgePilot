package com.forgepilot.auth;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way another feature may read account data (D013.6). It is a query
 * facade, not a repository: features stay free of {@code UserAccountRepository}
 * and of everything about how a session is established.
 */
@Service
@Transactional(readOnly = true)
public class UserDirectory {

    private final UserAccountRepository accounts;

    UserDirectory(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    public Optional<AccountView> byId(long userId) {
        return accounts.findById(userId).map(AccountView::of);
    }

    public Optional<AccountView> byUsername(String username) {
        return accounts.findByUsername(username).map(AccountView::of);
    }

    /** Batch read for member and requirement lists, so they never loop over {@link #byId}. */
    public List<AccountView> byIds(Collection<Long> userIds) {
        return accounts.findAllById(userIds).stream().map(AccountView::of).toList();
    }
}
