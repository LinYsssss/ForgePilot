package com.example.codereview.auth;

import com.example.codereview.auth.AuthDtos.AuthResponse;
import com.example.codereview.auth.AuthDtos.LoginRequest;
import com.example.codereview.auth.AuthDtos.LoginResult;
import com.example.codereview.auth.AuthDtos.MeResponse;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.SecurityAuditLogger;
import com.example.codereview.common.security.SecurityAuditLogger.Outcome;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /**
     * 一个固定的合法 BCrypt 哈希,专用于「用户不存在」分支的等时比对。
     * 内容无意义,只为让两条分支都付出一次哈希开销。
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final LoginAttemptGuard loginAttemptGuard;
    private final SecurityAuditLogger audit;

    public AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, TokenService tokenService,
                       LoginAttemptGuard loginAttemptGuard, SecurityAuditLogger audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.loginAttemptGuard = loginAttemptGuard;
        this.audit = audit;
    }

    @Transactional
    public UserAccount createUser(String username, String rawPassword, String nickname, String role) {
        if (users.existsByUsername(username)) {
            throw new BusinessException(409, "用户名已存在");
        }
        UserAccount user = new UserAccount(
                username,
                passwordEncoder.encode(rawPassword),
                nickname,
                role == null || role.isBlank() ? "DEVELOPER" : role
        );
        return users.save(user);
    }

    public LoginResult login(LoginRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        try {
            loginAttemptGuard.assertNotLocked(username);
        } catch (BusinessException locked) {
            auditUnidentifiedLogin(username, Outcome.DENIED, "LOCKED_OUT");
            throw locked;
        }

        Optional<UserAccount> found = users.findByUsername(username);
        if (found.isEmpty()) {
            // 用户不存在时也跑一次 BCrypt。否则「不存在」几乎立即返回、「存在但口令错」要等一次
            // 哈希计算,响应耗时的差异足以让攻击者枚举出哪些用户名有效。
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            loginAttemptGuard.recordFailure(username);
            // 审计里区分「无此用户」与「口令错误」是有意的:响应对外统一,日志对内要能定位。
            auditUnidentifiedLogin(username, Outcome.FAILURE, "UNKNOWN_USER");
            throw new BusinessException(400, "用户名或密码错误");
        }
        UserAccount user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptGuard.recordFailure(username);
            audit.recordFor(user.getId(), username, "LOGIN", Outcome.FAILURE, "user", username, "BAD_CREDENTIALS");
            throw new BusinessException(400, "用户名或密码错误");
        }
        if (!user.isEnabled()) {
            audit.recordFor(user.getId(), username, "LOGIN", Outcome.DENIED, "user", username, "ACCOUNT_DISABLED");
            throw new BusinessException(403, "账号已被禁用");
        }
        loginAttemptGuard.recordSuccess(username);
        audit.recordFor(user.getId(), username, "LOGIN", Outcome.SUCCESS, "user", username, null);
        return new LoginResult(tokenService.issue(user), toAuthResponse(user));
    }

    public MeResponse me(Long userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        return new MeResponse(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    @Transactional
    public void logout(Long userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        user.bumpSessionVersion();
        audit.recordFor(user.getId(), user.getUsername(), "LOGOUT", Outcome.SUCCESS, "user", user.getUsername(), null);
    }

    /** 尚未确定用户身份的分支:只有请求方提交的用户名可记,actorId 留空。 */
    private void auditUnidentifiedLogin(String username, Outcome outcome, String reason) {
        audit.recordFor(null, username, "LOGIN", outcome, "user", username, reason);
    }

    private AuthResponse toAuthResponse(UserAccount user) {
        return new AuthResponse(user.getId(), user.getUsername(), user.getRole());
    }
}
