package com.forgepilot.scm.github;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.forgepilot.common.ApiException;
import com.forgepilot.scm.ChangedFile;
import com.forgepilot.scm.OutboundUrlPolicy;
import com.forgepilot.scm.PullRequestSnapshot;
import com.forgepilot.scm.ScmRepository;
import com.forgepilot.scm.ScmSecretCipher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Reads the authoritative pull request snapshot from GitHub.
 *
 * <p>The base URI comes from {@code scm_repository.api_base} and a host is never
 * hardcoded (D015.8). That is not a testing concession: D010 requires self-hosted
 * instances to work, and the same column is what lets an integration test point a
 * repository at a loopback stub without a credential and without a production
 * change. Every call re-checks the address against {@link OutboundUrlPolicy},
 * because the column is LEADER-configurable and the policy may have narrowed since.
 *
 * <p>The repository is addressed by its numeric id rather than {@code owner/repo}:
 * the id survives a rename or a transfer, the slug does not.
 */
@Component
class GitHubClient {

    private static final int PAGE_SIZE = 100;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final OutboundUrlPolicy outbound;
    private final ScmSecretCipher cipher;
    private final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();

    GitHubClient(OutboundUrlPolicy outbound, ScmSecretCipher cipher) {
        this.outbound = outbound;
        this.cipher = cipher;
        this.requestFactory.setReadTimeout(READ_TIMEOUT);
    }

    PullRequestSnapshot fetch(ScmRepository repository, int number) {
        RestClient client = clientFor(repository);
        String externalId = repository.getExternalId();

        JsonNode pullRequest = client.get()
                .uri("/repositories/{repository}/pulls/{number}", externalId, number)
                .retrieve()
                .body(JsonNode.class);

        return new PullRequestSnapshot(
                number,
                required(pullRequest.path("base"), "sha", "base.sha"),
                required(pullRequest.path("head"), "sha", "head.sha"),
                required(pullRequest.path("head"), "ref", "head.ref"),
                required(pullRequest, "title"),
                // GitHub exposes no stable diff revision, so that slot stays empty
                // and ordering rests on updated_at alone.
                null,
                Instant.parse(required(pullRequest, "updated_at")),
                required(pullRequest.path("user"), "id", "user.id"),
                required(pullRequest.path("user"), "login", "user.login"),
                changedFiles(client, externalId, number));
    }

    private RestClient clientFor(ScmRepository repository) {
        URI base = outbound.requireAllowed(repository.getApiBase());
        // Built per repository rather than from an injected shared builder: api_base
        // is per-repository data (D010 self-hosted instances), so there is no single
        // base URI a shared builder could carry, and one carrying a fixed host would
        // be exactly the hardcoding D015.8 forbids.
        return RestClient.builder()
                .baseUrl(base.toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + cipher.decrypt(repository.getEncryptedToken()))
                .build();
    }

    /**
     * The file list is paginated and its order across pages is conventional rather
     * than contractual, which is exactly why the fingerprint sorts. The loop is
     * bounded by the same manifest limit the row is bounded by, so an outsized pull
     * request fails explicitly instead of being accumulated in memory first.
     */
    private List<ChangedFile> changedFiles(RestClient client, String externalId, int number) {
        List<ChangedFile> files = new ArrayList<>();
        int characters = 0;
        for (int page = 1; ; page++) {
            int current = page;
            JsonNode batch = client.get()
                    .uri(uri -> uri.path("/repositories/{repository}/pulls/{number}/files")
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", current)
                            .build(externalId, number))
                    .retrieve()
                    .body(JsonNode.class);
            for (JsonNode file : batch) {
                JsonNode patch = file.path("patch");
                // Absent is not empty: a binary file or one past GitHub's own diff
                // limit carries no patch at all, and the fingerprint distinguishes them.
                String content = patch.isMissingNode() || patch.isNull() ? null : patch.asString();
                ChangedFile changed = new ChangedFile(required(file, "filename"),
                        required(file, "status"), content);
                characters += changed.path().length() + (content == null ? 0 : content.length());
                if (characters > ChangedFile.MAX_TOTAL_CHARS) {
                    throw ApiException.unprocessable(
                            "This pull request's diff is larger than this deployment stores.");
                }
                files.add(changed);
            }
            if (batch.size() < PAGE_SIZE) {
                return files;
            }
        }
    }

    /**
     * An authoritative field that is absent, null, or a container must fail loudly.
     * {@code JsonNode.asString()} answers "" for a missing or null node, which would
     * write base_sha='' or author_external_user_id='' — both satisfy NOT NULL, so
     * the database cannot catch it, and the fingerprint would then be computed over
     * empty SHAs. author_external_user_id is worse still: D010 makes it the
     * authorization key, so every ghost-author pull request would share one identity.
     * R3 requires malformed input to fail explicitly rather than store bad data.
     */
    private static String required(JsonNode parent, String field, String label) {
        JsonNode node = parent.path(field);
        if (!node.isValueNode() || node.isNull()) {
            throw ApiException.unprocessable(
                    "The provider's pull request is missing " + label + ".");
        }
        String value = node.asString();
        if (value.isBlank()) {
            throw ApiException.unprocessable("The provider's pull request has an empty " + label + ".");
        }
        return value;
    }

    private static String required(JsonNode parent, String field) {
        return required(parent, field, field);
    }
}
