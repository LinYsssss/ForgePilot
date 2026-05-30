package com.example.codereview.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AuthSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthSeedRunner.class);
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final AuthService authService;
    private final UserAccountRepository users;
    private final String seedUsername;
    private final String seedPassword;
    private final String seedNickname;
    private final String seedRole;

    public AuthSeedRunner(
            AuthService authService,
            UserAccountRepository users,
            @Value("${app.security.seed-admin.username:}") String seedUsername,
            @Value("${app.security.seed-admin.password:}") String seedPassword,
            @Value("${app.security.seed-admin.nickname:管理员}") String seedNickname,
            @Value("${app.security.seed-admin.role:ADMIN}") String seedRole
    ) {
        this.authService = authService;
        this.users = users;
        this.seedUsername = seedUsername;
        this.seedPassword = seedPassword;
        this.seedNickname = seedNickname;
        this.seedRole = seedRole;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedUsername == null || seedUsername.isBlank() || seedPassword == null || seedPassword.isBlank()) {
            return;
        }
        if (seedPassword.length() < MIN_PASSWORD_LENGTH) {
            log.warn("跳过种子账号创建：SEED_ADMIN_PASSWORD 长度不足 {} 位", MIN_PASSWORD_LENGTH);
            return;
        }
        if (users.existsByUsername(seedUsername)) {
            log.info("种子账号 '{}' 已存在，跳过创建（不覆盖现有密码）", seedUsername);
            return;
        }
        authService.createUser(seedUsername, seedPassword, seedNickname, seedRole);
        log.info("已创建种子账号 '{}'（角色 {}）", seedUsername, seedRole);
    }
}
