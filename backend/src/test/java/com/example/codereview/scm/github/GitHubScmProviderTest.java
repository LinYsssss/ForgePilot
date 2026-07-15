package com.example.codereview.scm.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.scm.NormalizedPullRequestEvent;
import com.example.codereview.scm.ScmProviderContractTest;
import com.example.codereview.scm.ScmProviderType;
import com.example.codereview.webhook.WebhookSignatures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Unit-level adapter checks: a {@code pull_request} delivery normalizes to the shared contract,
 * unrelated events/actions are ignored, and signature verification runs over the exact raw bytes
 * (a semantically-equal but re-spaced payload does not verify).
 */
class GitHubScmProviderTest {

    private static final String SECRET = "gh-webhook-secret";

    private final GitHubScmProvider provider =
            new GitHubScmProvider(new ObjectMapper(), new GitHubWebhookVerifier());

    @Test
    void normalizesOpenedPullRequestToContract() {
        byte[] body = fixture("opened.json");
        Optional<NormalizedPullRequestEvent> normalized = provider.normalize(body, headers("pull_request", "d-1"));

        assertThat(normalized).isPresent();
        NormalizedPullRequestEvent event = normalized.get();
        ScmProviderContractTest.assertSatisfiesContract(event);
        assertThat(event.provider()).isEqualTo(ScmProviderType.GITHUB);
        assertThat(event.installationRef()).isEqualTo("42");
        assertThat(event.pullRequestNumber()).isEqualTo(7);
        assertThat(event.headSha()).isEqualTo("headsha111");
        assertThat(event.baseSha()).isEqualTo("basesha000");
        assertThat(event.sourceBranch()).isEqualTo("feature/order-validation");
        assertThat(event.targetBranch()).isEqualTo("main");
        assertThat(event.repositoryCloneUrl()).isEqualTo("https://github.com/acme/mall-order-service.git");
        assertThat(event.action()).isEqualTo("opened");
        assertThat(event.deliveryId()).isEqualTo("d-1");
    }

    @Test
    void ignoresUnrelatedAction() {
        assertThat(provider.normalize(fixture("labeled.json"), headers("pull_request", "d-2"))).isEmpty();
    }

    @Test
    void ignoresNonPullRequestEvent() {
        assertThat(provider.normalize(fixture("opened.json"), headers("push", "d-3"))).isEmpty();
    }

    @Test
    void verifiesSignatureOverExactRawBytes() {
        byte[] body = fixture("opened.json");
        String good = "sha256=" + WebhookSignatures.hmacSha256Hex(body, SECRET);

        assertThat(provider.verifySignature(body, signatureHeaders(good), SECRET)).isTrue();
        assertThat(provider.verifySignature(body, signatureHeaders("sha256=deadbeef"), SECRET)).isFalse();
        assertThat(provider.verifySignature(body, signatureHeaders(good), "wrong-secret")).isFalse();
        assertThat(provider.verifySignature(body, signatureHeaders(good), " ")).isFalse();

        // Same signature against a re-spaced (semantically equal) payload must fail: we hash raw bytes.
        byte[] reSpaced = ("  " + new String(body)).getBytes();
        assertThat(provider.verifySignature(reSpaced, signatureHeaders(good), SECRET)).isFalse();
    }

    private static Map<String, String> headers(String event, String deliveryId) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("X-GitHub-Event", event);
        headers.put("X-GitHub-Delivery", deliveryId);
        return headers;
    }

    private static Map<String, String> signatureHeaders(String signature) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("X-Hub-Signature-256", signature);
        return headers;
    }

    private static byte[] fixture(String name) {
        try (var in = GitHubScmProviderTest.class.getResourceAsStream("/webhooks/github/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
