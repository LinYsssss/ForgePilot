package com.forgepilot.auth;

import java.nio.charset.StandardCharsets;

import com.forgepilot.common.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 只负责注册与改密。给会话做身份认证是 Spring Security 的职责，落在
 * {@link SecurityConfig}；本类只拥有那些会改动 {@code user_account} 的操作。
 */
@Service
class AuthService {

    /**
     * BCrypt 拒绝超过该长度的输入，否则请求会以 500 失败。这个上限是按**字节**
     * 而非字符计的，因此在这里检查，而不是在请求记录上加 {@code @Size}。
     */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    AuthService(UserAccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 用户名重复由 {@code uq_user_account_username} 拒绝，并经
     * {@code ApiExceptionHandler} 映射为 409；在这里再查一次只会多出一个
     * 该约束本已封死的竞态窗口。
     */
    @Transactional
    AccountResponse register(String username, String password) {
        UserAccount account = this.accounts.save(new UserAccount(username, hash(password)));
        return new AccountResponse(account.getId(), account.getUsername());
    }

    /**
     * @return 新的 {@code session_version}；调用方自己的会话必须采纳它才能在
     *         {@link SessionVersionFilter} 下存活，其余所有会话就此失效。
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
