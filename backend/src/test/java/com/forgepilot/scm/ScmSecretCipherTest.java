package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * The cipher's two contracts: a round trip restores the plaintext, and anything
 * that is not one of its own envelopes fails as the same controlled
 * {@link IllegalStateException}. A truncated or non-Base64 stored value used to
 * surface as {@code ArrayIndexOutOfBoundsException} or
 * {@code IllegalArgumentException}, which the exception handler can only report as
 * a bare 500 while the message that names the failure never gets written.
 */
class ScmSecretCipherTest {

    private final ScmSecretCipher cipher = new ScmSecretCipher(ScmTestBase.SECRET_KEY);

    @Test
    void aRoundTripRestoresThePlaintextUnderAFreshIvEachTime() {
        String first = cipher.encrypt("ghp_token");
        String second = cipher.encrypt("ghp_token");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("ghp_token");
        assertThat(cipher.decrypt(second)).isEqualTo("ghp_token");
    }

    @Test
    void aStoredValueThatIsNotAnEnvelopeFailsAsTheSameControlledError() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[8]);
        String tampered = cipher.encrypt("secret").substring(4);
        String underAnotherKey = new ScmSecretCipher("another-test-only-key").encrypt("secret");

        assertThatThrownBy(() -> cipher.decrypt(tooShort)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("not base64!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(underAnotherKey)).isInstanceOf(IllegalStateException.class);
    }
}
