package com.forgepilot.auth;

import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.forgepilot.common.ApiException;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 其他功能模块读取账号数据的**唯一**途径。它是查询 facade 而非
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

    /**
     * 把登录身份解析为 user id。各功能模块的控制器在自己那一层调用它，再把 id 作为
     * 参数传给业务服务：业务服务因此永远看不到 Spring Security，功能模块也不依赖
     * 会话是如何建立的（ARCHITECTURE.md 1.3）。
     */
    public long idOf(Principal principal) {
        return byUsername(principal.getName()).map(AccountView::id).orElseThrow(ApiException::notFound);
    }

    /** 供成员列表与需求列表批量读取，使它们不必循环调用 {@link #byId}。 */
    public List<AccountView> byIds(Collection<Long> userIds) {
        return accounts.findAllById(userIds).stream().map(AccountView::of).toList();
    }

    public List<AccountView> search(String query, int page, int size) {
        Long exactId = query.chars().allMatch(Character::isDigit) ? parseId(query) : null;
        return accounts.search(query, exactId, PageRequest.of(page, size)).stream()
                .map(AccountView::of).toList();
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
