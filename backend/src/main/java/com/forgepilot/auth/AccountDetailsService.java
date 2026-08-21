package com.forgepilot.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Resolves the form-login username against {@code user_account}.
 *
 * <p>The message below never reaches a caller: {@code DaoAuthenticationProvider}
 * hides {@link UsernameNotFoundException} behind {@code BadCredentialsException},
 * which is why an unknown username and a wrong password are indistinguishable
 * (design.md 7).
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
