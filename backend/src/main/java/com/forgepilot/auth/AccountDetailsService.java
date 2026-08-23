package com.forgepilot.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 把表单登录的用户名解析到 {@code user_account}。
 *
 * <p>下面那句消息永远不会传到调用方：{@code DaoAuthenticationProvider} 会把
 * {@link UsernameNotFoundException} 藏在 {@code BadCredentialsException} 之后，
 * 这正是「用户名不存在」与「口令错误」不可区分的原因（design.md 7）。
 */
@Service
class AccountDetailsService implements UserDetailsService {

    private final UserAccountRepository accounts;

    AccountDetailsService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return this.accounts.findByUsername(username)
                .map(AccountPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("No such account."));
    }
}
