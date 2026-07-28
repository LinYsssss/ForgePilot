package com.example.codereview.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class LoginAttemptGuardTest {

    @Test
    void locksAccountAfterConfiguredFailuresAndReportsRetryAfter() {
        LoginAttemptGuard guard = new LoginAttemptGuard(3, 600);

        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> guard.assertNotLocked("victim")).doesNotThrowAnyException();
            guard.recordFailure("victim");
        }

        assertThatThrownBy(() -> guard.assertNotLocked("victim"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(429))
                .hasMessageContaining("秒后重试");
    }

    @Test
    void tracksPerUsernameSoOneAccountDoesNotLockAnother() {
        LoginAttemptGuard guard = new LoginAttemptGuard(2, 600);
        guard.recordFailure("alice");
        guard.recordFailure("alice");

        assertThatThrownBy(() -> guard.assertNotLocked("alice")).isInstanceOf(BusinessException.class);
        // 换 IP 也躲不开;但换账号不受影响
        assertThatCode(() -> guard.assertNotLocked("bob")).doesNotThrowAnyException();
    }

    @Test
    void usernameMatchingIgnoresCaseAndSurroundingSpace() {
        LoginAttemptGuard guard = new LoginAttemptGuard(2, 600);
        guard.recordFailure("Admin");
        guard.recordFailure("  admin  ");

        assertThatThrownBy(() -> guard.assertNotLocked("ADMIN")).isInstanceOf(BusinessException.class);
    }

    @Test
    void successClearsPreviousFailures() {
        LoginAttemptGuard guard = new LoginAttemptGuard(2, 600);
        guard.recordFailure("carol");
        guard.recordSuccess("carol");
        guard.recordFailure("carol");

        // 成功后计数清零,先前的手误不该把人锁在门外
        assertThatCode(() -> guard.assertNotLocked("carol")).doesNotThrowAnyException();
    }

    @Test
    void expiredWindowAutomaticallyUnlocks() throws Exception {
        LoginAttemptGuard guard = new LoginAttemptGuard(1, 1); // 1 秒窗口
        guard.recordFailure("dave");
        assertThatThrownBy(() -> guard.assertNotLocked("dave")).isInstanceOf(BusinessException.class);

        Thread.sleep(1100);
        assertThatCode(() -> guard.assertNotLocked("dave")).doesNotThrowAnyException();
    }

    @Test
    void zeroOrNegativeLimitDisablesTheGuard() {
        LoginAttemptGuard disabled = new LoginAttemptGuard(0, 600);
        for (int i = 0; i < 50; i++) {
            disabled.recordFailure("anyone");
        }
        assertThatCode(() -> disabled.assertNotLocked("anyone")).doesNotThrowAnyException();
    }
}
