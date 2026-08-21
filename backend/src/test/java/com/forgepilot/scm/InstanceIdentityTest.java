package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * The rule that decides which instance a repository belongs to. It is frozen once
 * a pull request exists and cannot be corrected afterwards without recreating the
 * project, so it is pinned here rather than left to whatever a URL happens to
 * look like.
 */
class InstanceIdentityTest {

    @Test
    void theSaasApiHostAndSiteHostAreOneInstance() {
        assertThat(identityOf("https://api.github.com")).isEqualTo("github.com");
        assertThat(identityOf("https://api.github.com/")).isEqualTo("github.com");
        assertThat(identityOf("https://API.GitHub.COM:443")).isEqualTo("github.com");
        assertThat(identityOf("https://github.com")).isEqualTo("github.com");
    }

    @Test
    void aSelfHostedInstanceIsItsHostWithoutTheApiPath() {
        assertThat(identityOf("https://ghe.corp.example/api/v3")).isEqualTo("ghe.corp.example");
        assertThat(identityOf("https://ghe.corp.example/api/v3/")).isEqualTo("ghe.corp.example");
        assertThat(identityOf("http://ghe.corp.example:80/api/v3")).isEqualTo("ghe.corp.example");
    }

    @Test
    void aNonDefaultPortIsPartOfTheIdentity() {
        // Two instances can differ by nothing else, so dropping the port here would
        // let one of them be registered as the other.
        assertThat(identityOf("https://ghe.corp.example:8443/api/v3")).isEqualTo("ghe.corp.example:8443");
        assertThat(identityOf("http://127.0.0.1:34567")).isEqualTo("127.0.0.1:34567");
        assertThat(identityOf("http://127.0.0.1:34567/api/v3"))
                .isEqualTo(identityOf("http://127.0.0.1:34567/"));
    }

    /**
     * Measured, not assumed: {@code java.net.URI} reports no host at all for a
     * U-label authority ({@code https://bücher.example} parses with
     * {@code getHost() == null}), so the A-label form is the only one that can
     * reach here — {@link OutboundUrlPolicy} refuses the other.
     */
    @Test
    void anInternationalizedHostIsCarriedInItsPunycodeForm() {
        assertThat(identityOf("https://xn--bcher-kva.example/api/v3")).isEqualTo("xn--bcher-kva.example");
        assertThat(identityOf("https://XN--R8JZ45G.jp/api/v3")).isEqualTo("xn--r8jz45g.jp");
    }

    private static String identityOf(String apiBase) {
        return InstanceIdentity.of(URI.create(apiBase));
    }
}
