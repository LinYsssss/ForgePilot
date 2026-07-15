package com.example.codereview.scm.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.scm.NormalizedPullRequestEvent;
import com.example.codereview.scm.ScmProviderContractTest;
import com.example.codereview.scm.ScmProviderType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Adapter-level checks for GitLab: MR actions normalize to the shared contract, unrelated
 * object kinds/actions are ignored, the token is verified in constant time, and the delivery key is
 * the event UUID when present and a deterministic hash otherwise.
 */
class GitLabScmProviderTest {

    private static final String TOKEN = "gl-webhook-token";

    private final GitLabScmProvider provider =
            new GitLabScmProvider(new ObjectMapper(), new GitLabWebhookVerifier());

    @Test
    void normalizesOpenMergeRequestToContract() {
        byte[] body = fixture("mr_open.json");
        Optional<NormalizedPullRequestEvent> normalized = provider.normalize(body, headers("uuid-1"));

        assertThat(normalized).isPresent();
        NormalizedPullRequestEvent event = normalized.get();
        ScmProviderContractTest.assertSatisfiesContract(event);
        assertThat(event.provider()).isEqualTo(ScmProviderType.GITLAB);
        assertThat(event.installationRef()).isEqualTo("99");
        assertThat(event.pullRequestNumber()).isEqualTo(5);
        assertThat(event.headSha()).isEqualTo("glhead111");
        assertThat(event.baseSha()).isEqualTo("glbase000");
        assertThat(event.sourceBranch()).isEqualTo("feature/order-validation");
        assertThat(event.targetBranch()).isEqualTo("main");
        assertThat(event.repositoryCloneUrl()).isEqualTo("https://gitlab.com/acme/mall-order-service.git");
        assertThat(event.action()).isEqualTo("open");
        assertThat(event.deliveryId()).isEqualTo("uuid-1");
    }

    @Test
    void normalizesReopenAndUpdateActions() {
        assertThat(provider.normalize(fixture("mr_reopen.json"), headers("u"))).get()
                .extracting(NormalizedPullRequestEvent::action).isEqualTo("reopen");
        assertThat(provider.normalize(fixture("mr_update.json"), headers("u"))).get()
                .extracting(NormalizedPullRequestEvent::headSha).isEqualTo("glhead222");
    }

    @Test
    void ignoresUnrelatedActionAndNonMergeRequest() {
        assertThat(provider.normalize(fixture("mr_close.json"), headers("u"))).isEmpty();
        assertThat(provider.normalize("{\"object_kind\":\"push\"}".getBytes(), headers("u"))).isEmpty();
    }

    @Test
    void verifiesTokenInConstantTime() {
        Map<String, String> good = tokenHeaders(TOKEN);
        assertThat(provider.verifySignature(new byte[0], good, TOKEN)).isTrue();
        assertThat(provider.verifySignature(new byte[0], tokenHeaders("wrong"), TOKEN)).isFalse();
        assertThat(provider.verifySignature(new byte[0], good, " ")).isFalse();
        assertThat(provider.verifySignature(new byte[0], new TreeMap<>(String.CASE_INSENSITIVE_ORDER), TOKEN)).isFalse();
    }

    @Test
    void deliveryKeyPrefersUuidHeaderThenDeterministicHash() {
        byte[] body = fixture("mr_open.json");
        assertThat(provider.deliveryKey(body, headers("uuid-xyz"))).isEqualTo("uuid-xyz");

        // No UUID header: deterministic across calls, and head SHA changes the key.
        Map<String, String> noUuid = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String first = provider.deliveryKey(body, noUuid);
        String second = provider.deliveryKey(body, noUuid);
        assertThat(first).isEqualTo(second).startsWith("gl-");
        assertThat(provider.deliveryKey(fixture("mr_update.json"), noUuid)).isNotEqualTo(first);
    }

    private static Map<String, String> headers(String uuid) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("X-Gitlab-Event", "Merge Request Hook");
        headers.put("X-Gitlab-Event-UUID", uuid);
        return headers;
    }

    private static Map<String, String> tokenHeaders(String token) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("X-Gitlab-Token", token);
        return headers;
    }

    private static byte[] fixture(String name) {
        try (var in = GitLabScmProviderTest.class.getResourceAsStream("/webhooks/gitlab/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
