package com.forgepilot.scm;

import com.forgepilot.PostgresTestBase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The two properties the SCM slice needs, contributed the same way
 * {@link PostgresTestBase} contributes the database.
 *
 * <p>{@code forgepilot.scm.secret-key} has no default anywhere, so a context that
 * does not supply it fails to start — that is the point of it. Compose and CI pass
 * {@code FORGEPILOT_SCM_SECRET_KEY} for the same reason, and this is the test
 * equivalent of that.
 *
 * <p>{@code forgepilot.scm.allowed-hosts} is the narrow, explicit exception that
 * makes a loopback stub provider reachable while {@link OutboundUrlPolicy} stays
 * switched on for everything else. The alternative — turning the policy off in
 * tests — would ship a policy that had never once executed. That "empty by
 * default" is the production behaviour is held by {@code OutboundUrlPolicyTest},
 * which builds the policy with no allowlist at all.
 */
public abstract class ScmTestBase extends PostgresTestBase {

    /** Deliberately fake, and deliberately not a 32 byte key: the cipher derives one. */
    static final String SECRET_KEY = "forgepilot-scm-test-only-not-a-real-key";

    @DynamicPropertySource
    static void scmProperties(DynamicPropertyRegistry registry) {
        registry.add("forgepilot.scm.secret-key", () -> SECRET_KEY);
        registry.add("forgepilot.scm.allowed-hosts", () -> "127.0.0.1");
    }
}
