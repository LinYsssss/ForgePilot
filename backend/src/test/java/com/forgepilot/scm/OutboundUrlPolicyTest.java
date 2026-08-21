package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forgepilot.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The policy with the allowlist production actually runs: empty. Every other test
 * in this slice adds {@code 127.0.0.1} so a stub provider is reachable, which is
 * exactly why "deny by default" has to be pinned here, without that seam.
 */
class OutboundUrlPolicyTest {

    private final OutboundUrlPolicy production = new OutboundUrlPolicy("");

    @Test
    void loopbackPrivateAndLinkLocalAddressesAreDenied() {
        // 169.254.169.254 is the one that matters most: it is the cloud metadata
        // endpoint, and api_base is configured by a project LEADER.
        assertDenied("http://169.254.169.254/latest/meta-data/");
        assertDenied("http://127.0.0.1:8080/repositories/1");
        assertDenied("https://localhost/api/v3");
        assertDenied("http://10.0.0.1/");
        assertDenied("http://172.16.0.1/");
        assertDenied("http://172.31.255.255/");
        assertDenied("http://192.168.1.1/");
        assertDenied("http://[::1]/");
        assertDenied("http://0.0.0.0/");
        assertDenied("http://[fd00::1]/");
    }

    @Test
    void anythingThatIsNotHttpOrHttpsIsDenied() {
        assertDenied("file:///etc/passwd");
        assertDenied("ftp://198.51.100.7/");
        assertDenied("gopher://198.51.100.7:70/");
        assertDenied("jar:http://198.51.100.7!/x");
        assertDenied("http:/no-authority");
        assertDenied("not a url at all");
        // Measured: java.net.URI parses a U-label authority as a registry name and
        // reports no host for it. There is nothing to judge, so it is refused; an
        // internationalized instance has to be given in its A-label form.
        assertDenied("https://bücher.example/api/v3");
    }

    /**
     * The counter-proof: the policy is a filter, not a blanket refusal. 203.0.113.0/24
     * is TEST-NET-3, so this asserts the allow path without resolving a real name.
     */
    @Test
    void aPublicAddressPassesWithoutBeingListed() {
        assertThat(production.requireAllowed("https://203.0.113.7/api/v3").getHost())
                .isEqualTo("203.0.113.7");
    }

    @Test
    void onlyAnExplicitAllowlistEntryReopensADeniedHost() {
        OutboundUrlPolicy underTest = new OutboundUrlPolicy(" 127.0.0.1 ");

        assertThat(underTest.requireAllowed("http://127.0.0.1:34567/repositories/9").getPort())
                .isEqualTo(34567);

        // The exception is that host and nothing else: neighbouring private
        // addresses and non-http schemes stay denied.
        assertThatThrownBy(() -> underTest.requireAllowed("http://127.0.0.2/"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> underTest.requireAllowed("file://127.0.0.1/etc/passwd"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> underTest.requireAllowed("http://169.254.169.254/"))
                .isInstanceOf(ApiException.class);
    }

    private void assertDenied(String url) {
        assertThatThrownBy(() -> production.requireAllowed(url))
                .as(url)
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException failure = (ApiException) thrown;
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    // One answer for every reason, so a rejection cannot be used to
                    // map which internal addresses exist.
                    assertThat(failure.getMessage())
                            .isEqualTo("That address is not reachable from this deployment.");
                });
    }
}
