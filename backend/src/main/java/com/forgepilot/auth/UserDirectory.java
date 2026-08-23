package com.forgepilot.auth;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 其他功能模块读取账号数据的**唯一**途径（D013.6）。它是查询 facade 而非
 * 仓库：各功能模块因此既接触不到 {@code UserAccountRepository}，也接触不到
 * 会话如何建立的任何细节。
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

    /** 供成员列表与需求列表批量读取，使它们不必循环调用 {@link #byId}。 */
    public List<AccountView> byIds(Collection<Long> userIds) {
        return accounts.findAllById(userIds).stream().map(AccountView::of).toList();
    }
}
