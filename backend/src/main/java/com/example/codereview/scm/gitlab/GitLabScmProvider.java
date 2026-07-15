package com.example.codereview.scm.gitlab;

import com.example.codereview.scm.NormalizedPullRequestEvent;
import com.example.codereview.scm.ScmProvider;
import com.example.codereview.scm.ScmProviderType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * GitLab adapter: resolves the project identity, verifies the {@code X-Gitlab-Token}, and normalizes
 * a Merge Request webhook into the shared {@link NormalizedPullRequestEvent}.
 *
 * <p>Only the reviewable MR actions ({@code open}, {@code reopen}, {@code update}) normalize; the
 * rest return empty. The merge request's {@code iid} is the PR number, the head SHA is the last
 * commit (or {@code diff_refs.head_sha}), and the base SHA comes from {@code diff_refs.base_sha}
 * (falling back to {@code oldrev}/{@code start_sha}). Since GitLab does not always send a delivery
 * id header, {@link #deliveryKey} derives a deterministic key from the event UUID, or from
 * project + MR + head SHA + timestamp when absent.
 */
@Component
public class GitLabScmProvider implements ScmProvider {

    private static final Set<String> REVIEWABLE_ACTIONS = Set.of("open", "reopen", "update");

    private final ObjectMapper objectMapper;
    private final GitLabWebhookVerifier verifier;

    public GitLabScmProvider(ObjectMapper objectMapper, GitLabWebhookVerifier verifier) {
        this.objectMapper = objectMapper;
        this.verifier = verifier;
    }

    @Override
    public ScmProviderType type() {
        return ScmProviderType.GITLAB;
    }

    @Override
    public String resolveInstallationRef(byte[] rawBody, Map<String, String> headers) {
        JsonNode root = parse(rawBody);
        String id = root.path("project").path("id").asText("");
        return id.isBlank() ? null : id;
    }

    @Override
    public boolean verifySignature(byte[] rawBody, Map<String, String> headers, String secret) {
        return verifier.verify(headers.get("X-Gitlab-Token"), secret);
    }

    @Override
    public Optional<NormalizedPullRequestEvent> normalize(byte[] rawBody, Map<String, String> headers) {
        JsonNode root = parse(rawBody);
        if (!"merge_request".equals(root.path("object_kind").asText(""))) {
            return Optional.empty();
        }
        JsonNode attrs = root.path("object_attributes");
        String action = attrs.path("action").asText("");
        if (!REVIEWABLE_ACTIONS.contains(action)) {
            return Optional.empty();
        }
        JsonNode project = root.path("project");
        JsonNode diffRefs = attrs.path("diff_refs");
        String headSha = firstNonBlank(
                attrs.path("last_commit").path("id").asText(""),
                diffRefs.path("head_sha").asText(""));
        String baseSha = firstNonBlank(
                diffRefs.path("base_sha").asText(""),
                attrs.path("oldrev").asText(""),
                diffRefs.path("start_sha").asText(""));
        return Optional.of(new NormalizedPullRequestEvent(
                ScmProviderType.GITLAB,
                resolveInstallationRef(rawBody, headers),
                blankToNull(project.path("path_with_namespace").asText("")),
                project.path("git_http_url").asText(""),
                attrs.path("iid").asInt(),
                blankToNull(attrs.path("title").asText("")),
                blankToNull(root.path("user").path("username").asText("")),
                attrs.path("source_branch").asText(""),
                attrs.path("target_branch").asText(""),
                baseSha,
                headSha,
                action,
                deliveryKey(rawBody, headers)));
    }

    /**
     * Deterministic idempotency key for a GitLab delivery: the {@code X-Gitlab-Event-UUID} header
     * when present, otherwise a hash of project id, MR iid, head SHA, and event timestamp.
     */
    public String deliveryKey(byte[] rawBody, Map<String, String> headers) {
        String uuid = headers.get("X-Gitlab-Event-UUID");
        if (uuid != null && !uuid.isBlank()) {
            return uuid;
        }
        JsonNode root = parse(rawBody);
        JsonNode attrs = root.path("object_attributes");
        String material = root.path("project").path("id").asText("")
                + ":" + attrs.path("iid").asText("")
                + ":" + attrs.path("last_commit").path("id").asText("")
                + ":" + firstNonBlank(attrs.path("updated_at").asText(""), attrs.path("created_at").asText(""));
        return "gl-" + sha256Hex(material.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode parse(byte[] rawBody) {
        try {
            return objectMapper.readTree(rawBody == null || rawBody.length == 0 ? "{}".getBytes() : rawBody);
        } catch (Exception ex) {
            throw new IllegalArgumentException("GitLab webhook payload 不是合法 JSON");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
