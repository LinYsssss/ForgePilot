package com.example.codereview.scm.github;

import com.example.codereview.scm.NormalizedPullRequestEvent;
import com.example.codereview.scm.ScmProvider;
import com.example.codereview.scm.ScmProviderType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * GitHub adapter: resolves the App installation identity, verifies the delivery signature, and
 * normalizes a {@code pull_request} webhook into the shared {@link NormalizedPullRequestEvent}.
 *
 * <p>Only the reviewable actions ({@code opened}, {@code reopened}, {@code synchronize},
 * {@code ready_for_review}) normalize to an event; everything else returns empty so the controller
 * can ack-ignore it. Identity comes from {@code installation.id} and the host comes from the
 * resolved installation — never from secret- or host-bearing payload fields.
 */
@Component
public class GitHubScmProvider implements ScmProvider {

    private static final Set<String> REVIEWABLE_ACTIONS =
            Set.of("opened", "reopened", "synchronize", "ready_for_review");

    private final ObjectMapper objectMapper;
    private final GitHubWebhookVerifier verifier;

    public GitHubScmProvider(ObjectMapper objectMapper, GitHubWebhookVerifier verifier) {
        this.objectMapper = objectMapper;
        this.verifier = verifier;
    }

    @Override
    public ScmProviderType type() {
        return ScmProviderType.GITHUB;
    }

    @Override
    public String resolveInstallationRef(byte[] rawBody, Map<String, String> headers) {
        JsonNode root = parse(rawBody);
        String id = root.path("installation").path("id").asText("");
        return id.isBlank() ? null : id;
    }

    @Override
    public boolean verifySignature(byte[] rawBody, Map<String, String> headers, String secret) {
        return verifier.verify(rawBody, headers.get("X-Hub-Signature-256"), secret);
    }

    @Override
    public Optional<NormalizedPullRequestEvent> normalize(byte[] rawBody, Map<String, String> headers) {
        if (!"pull_request".equals(headers.get("X-GitHub-Event"))) {
            return Optional.empty();
        }
        JsonNode root = parse(rawBody);
        String action = root.path("action").asText("");
        if (!REVIEWABLE_ACTIONS.contains(action)) {
            return Optional.empty();
        }
        JsonNode pr = root.path("pull_request");
        JsonNode repo = root.path("repository");
        String installationRef = resolveInstallationRef(rawBody, headers);
        return Optional.of(new NormalizedPullRequestEvent(
                ScmProviderType.GITHUB,
                installationRef,
                blankToNull(repo.path("full_name").asText("")),
                repo.path("clone_url").asText(""),
                pr.path("number").asInt(),
                blankToNull(pr.path("title").asText("")),
                blankToNull(pr.path("user").path("login").asText("")),
                pr.path("head").path("ref").asText(""),
                pr.path("base").path("ref").asText(""),
                pr.path("base").path("sha").asText(""),
                pr.path("head").path("sha").asText(""),
                action,
                headers.get("X-GitHub-Delivery")));
    }

    private JsonNode parse(byte[] rawBody) {
        try {
            return objectMapper.readTree(rawBody == null || rawBody.length == 0 ? "{}".getBytes() : rawBody);
        } catch (Exception ex) {
            throw new IllegalArgumentException("GitHub webhook payload 不是合法 JSON");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
