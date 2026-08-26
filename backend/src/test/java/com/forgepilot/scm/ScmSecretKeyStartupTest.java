package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * "A deployment without {@code forgepilot.scm.secret-key} fails at startup" —
 * asserted, at last. This was long "a property that was declared and never
 * measured": {@link ScmSecretCipher}'s javadoc claims it, {@code application.yml}
 * was believed to carry no default, and nothing checked either half.
 *
 * <p>The context is built with {@link ConfigDataApplicationContextInitializer}, so
 * the real {@code application.yml} is loaded exactly as a running deployment loads
 * it. That is what makes {@link #withoutTheKeyTheContextDoesNotStart()} a statement
 * about this project's configuration and not only about the constructor: the day
 * someone adds {@code forgepilot.scm.secret-key: ${FORGEPILOT_SCM_SECRET_KEY:dev}}
 * to that file, this test goes red — which is the point, because a built-in default
 * is a weak key that silently works everywhere.
 *
 * <p>A runner rather than {@code @SpringBootTest} because the assertion is that a
 * context <em>fails</em>, and a {@code @SpringBootTest} that cannot start reports
 * an initialization error instead of a result. Only {@link ScmSecretCipher} is
 * registered: no database, no web layer, nothing that could fail first and answer
 * the question by accident.
 */
class ScmSecretKeyStartupTest {

    private static final String KEY = "forgepilot.scm.secret-key";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(ScmSecretCipher.class);

    @Test
    void withoutTheKeyTheContextDoesNotStart() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                // Named in the failure, so this cannot pass because some unrelated
                // bean happened to break first.
                .hasStackTraceContaining(KEY));
    }

    @Test
    void anEmptyOrBlankKeyIsRefusedJustAsHard() {
        for (String supplied : new String[] {"", "   ", "\t"}) {
            runner.withPropertyValues(KEY + "=" + supplied)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .hasStackTraceContaining("must not be empty"));
        }
    }

    /**
     * The control, without which every assertion above would also hold for a
     * cipher that could never be built at all. It doubles as the direct round-trip
     * assertion that was previously only indirect: {@code decrypt(encrypt(x)) == x}
     * used to be proven by the webhook tests failing if it broke, never stated.
     */
    @Test
    void withAKeyTheContextStartsAndTheCipherRoundTrips() {
        runner.withPropertyValues(KEY + "=deliberately-fake-test-only-key").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ScmSecretCipher.class);

            ScmSecretCipher cipher = context.getBean(ScmSecretCipher.class);
            String secret = "ghp-a-token-with-é-and-😀";
            String stored = cipher.encrypt(secret);

            assertThat(cipher.decrypt(stored)).isEqualTo(secret);
            assertThat(stored).doesNotContain(secret);
            // A fresh IV every time, or two identical tokens would be visibly
            // identical in the column.
            assertThat(cipher.encrypt(secret)).isNotEqualTo(stored);
        });
    }

    /** A different key must not silently decrypt: rotation is unimplemented, not accidental. */
    @Test
    void whatOneKeyEncryptedAnotherKeyCannotRead() {
        runner.withPropertyValues(KEY + "=first-key").run(first -> {
            String stored = first.getBean(ScmSecretCipher.class).encrypt("hook-secret");
            runner.withPropertyValues(KEY + "=second-key").run(second -> assertThat(
                    catchThrowable(() -> second.getBean(ScmSecretCipher.class).decrypt(stored)))
                    .isInstanceOf(IllegalStateException.class)
                    // Never the key, the plaintext or the ciphertext.
                    .hasMessage("An SCM credential could not be processed."));
        });
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }
}
