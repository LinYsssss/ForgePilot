package com.example.codereview.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.common.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TokenServiceTest {

    @Test
    void issueAndParseToken() {
        TokenService tokenService = new TokenService("unit-test-secret", 3600);
        UserAccount user = new UserAccount("alice", "hash", "Alice", "DEVELOPER");
        ReflectionTestUtils.setField(user, "id", 42L);

        String token = tokenService.issue(user);
        CurrentUser parsed = tokenService.parse(token);

        assertThat(parsed).isNotNull();
        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.username()).isEqualTo("alice");
        assertThat(parsed.role()).isEqualTo("DEVELOPER");
    }

    @Test
    void rejectTamperedToken() {
        TokenService tokenService = new TokenService("unit-test-secret", 3600);
        UserAccount user = new UserAccount("bob", "hash", "Bob", "DEVELOPER");
        ReflectionTestUtils.setField(user, "id", 7L);

        String token = tokenService.issue(user);

        assertThat(tokenService.parse(token + "x")).isNull();
    }
}
