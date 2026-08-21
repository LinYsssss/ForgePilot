package com.forgepilot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * A pure function, so these need neither Spring nor a database.
 *
 * <p>What is asserted is the shape of a credential, never a credential:
 * everything here is invented, which is the same reason the gateway's own tests
 * can run with no key in existence.
 */
class PromptSanitizerTest {

    private static final int NO_BUDGET_PRESSURE = 10_000;

    @Test
    void keyShapedStringsAreMaskedRatherThanRefused() {
        String text = "use sk-" + "0123456789abcdefghij"
                + " or ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789"
                + " or AKIA" + "ABCDEFGHIJKLMNOP"
                + " or Bearer " + "abcdefghijklmnopqrst";

        String sanitized = PromptSanitizer.sanitize(text, NO_BUDGET_PRESSURE);

        // ARCHITECTURE.md 4.3 asks for 脱敏, not rejection: a document keeps its
        // meaning and loses only the secret.
        assertThat(sanitized).isEqualTo("use " + PromptSanitizer.MASK + " or " + PromptSanitizer.MASK
                + " or " + PromptSanitizer.MASK + " or " + PromptSanitizer.MASK);
    }

    @Test
    void ordinaryProseIsLeftExactlyAsItWas() {
        String text = "The login endpoint returns 401 when the password is wrong. 登录失败返回 401。";

        assertThat(PromptSanitizer.sanitize(text, NO_BUDGET_PRESSURE)).isEqualTo(text);
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        // The rocket is one code point stored as two chars, and it starts exactly
        // at the budget's last char.
        String text = "ab🚀cd";

        String sanitized = PromptSanitizer.sanitize(text, 3);

        assertThat(sanitized).isEqualTo("ab");
        // The byte-level proof that nothing was corrupted: a cut between the two
        // halves would survive this comparison as '?', not as an exception.
        assertThat(new String(sanitized.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(sanitized);
    }

    @Test
    void aWholeCharacterIsKeptWhenItFits() {
        assertThat(PromptSanitizer.sanitize("ab🚀cd", 4)).isEqualTo("ab🚀");
    }
}
