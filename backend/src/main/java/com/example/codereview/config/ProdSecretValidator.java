package com.example.codereview.config;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProdSecretValidator {

    private static final int MIN_LENGTH = 16;
    private static final Set<String> FORBIDDEN = Set.of(
            "dev-secret-change-me",
            "dev-token-encryption-key-change-me",
            "change-me",
            "change-me-to-a-long-random-string",
            "change-me-to-another-long-random-string",
            "guest",
            "replace-with-your-mimo-key"
    );

    private final String tokenSecret;
    private final String tokenEncryptKey;
    private final String dbPassword;
    private final String rabbitPassword;
    private final String llmApiKey;

    public ProdSecretValidator(
            @Value("${app.security.token-secret}") String tokenSecret,
            @Value("${app.security.token-encrypt-key}") String tokenEncryptKey,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${spring.rabbitmq.password:}") String rabbitPassword,
            @Value("${app.ai.api-key:}") String llmApiKey
    ) {
        this.tokenSecret = tokenSecret;
        this.tokenEncryptKey = tokenEncryptKey;
        this.dbPassword = dbPassword;
        this.rabbitPassword = rabbitPassword;
        this.llmApiKey = llmApiKey;
    }

    @PostConstruct
    void validate() {
        check("JWT_SECRET (app.security.token-secret)", tokenSecret);
        check("TOKEN_ENCRYPT_KEY (app.security.token-encrypt-key)", tokenEncryptKey);
        check("DB_PASSWORD (spring.datasource.password)", dbPassword);
        check("RABBITMQ_PASSWORD (spring.rabbitmq.password)", rabbitPassword);
        checkOptional("LLM_API_KEY (app.ai.api-key)", llmApiKey);
    }

    private void check(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("生产环境必须配置 " + name + "，当前为空");
        }
        if (value.trim().length() < MIN_LENGTH) {
            throw new IllegalStateException("生产环境 " + name + " 太短，至少需要 " + MIN_LENGTH + " 个字符");
        }
        if (FORBIDDEN.contains(value.trim())) {
            throw new IllegalStateException("生产环境 " + name + " 仍是默认占位值，请改成强随机值");
        }
    }

    private void checkOptional(String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (FORBIDDEN.contains(value.trim())) {
            throw new IllegalStateException("生产环境 " + name + " 仍是默认占位值，请改成真实密钥");
        }
    }
}
