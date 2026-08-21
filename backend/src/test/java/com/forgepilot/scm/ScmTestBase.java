package com.forgepilot.scm;

import com.forgepilot.PostgresTestBase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The two properties the SCM slice needs, contributed the same way
 * {@link PostgresTestBase} contributes the database.
 *
 * <p>{@code forgepilot.scm.allowed-hosts} is the narrow, explicit exception that
 * makes a loopback stub provider reachable while {@link OutboundUrlPolicy} stays
 * switched on for everything else. The alternative — turning the policy off in
 * tests — would ship a policy that had never once executed. That "empty by
 * default" is the production behaviour is held by {@code OutboundUrlPolicyTest},
 * which builds the policy with no allowlist at all.
 */
public abstract class ScmTestBase extends PostgresTestBase {

    /** Base64 of the 32 ASCII bytes {@code forgepilot-scm-test-key-32-bytes}. Test only, and not a credential. */
    static final String SECRET_KEY = "Zm9yZ2VwaWxvdC1zY20tdGVzdC1rZXktMzItYnl0ZXM=";

    @DynamicPropertySource
    static void scmProperties(DynamicPropertyRegistry registry) {
        registry.add("forgepilot.scm.secret-key", () -> SECRET_KEY);
        registry.add("forgepilot.scm.allowed-hosts", () -> "127.0.0.1");
    }
}
