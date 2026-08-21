package com.forgepilot.auth;

import java.nio.charset.StandardCharsets;

import com.forgepilot.common.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and password change. Authenticating a session is Spring Security's
 * job and lives in {@link SecurityConfig}; this class only owns what changes
 * {@code user_account}.
 */
@Service
class AuthService {

    /**
     * BCrypt refuses anything longer and would fail the request with a 500. The
     * limit is on bytes, not characters, so it is checked here rather than with a
     * {@code @Size} on the request record.
     */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    AuthService(UserAccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * A duplicate username is rejected by {@code uq_user_account_username} and
     * mapped to 409 by {@code ApiExceptionHandler}; re-checking it here would only
     * add a race the constraint already closes.
     */
    @Transactional
    AccountResponse register(String username, String password) {
        UserAccount account = this.accounts.save(new UserAccount(username, hash(password)));
        return new AccountResponse(account.getId(), account.getUsername());
    }

    /**
     * @return the new {@code session_version}; the caller's own session has to adopt
     *         it to survive {@link SessionVersionFilter}, every other session dies.
     */
    @Transactional
    int changePassword(long userId, String currentPassword, String newPassword) {
        UserAccount account = this.accounts.findById(userId).orElseThrow(ApiException::notFound);
        if (!this.passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw ApiException.unprocessable("The current password is incorrect.");
        }
        account.changePassword(hash(newPassword));
        return account.getSessionVersion();
    }

    private String hash(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.unprocessable("The password is too long.");
        }
        return this.passwordEncoder.encode(password);
    }
}
