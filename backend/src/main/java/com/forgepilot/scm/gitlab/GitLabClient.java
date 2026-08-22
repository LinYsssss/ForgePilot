package com.forgepilot.scm.gitlab;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.forgepilot.common.ApiException;
import com.forgepilot.scm.ChangedFile;
import com.forgepilot.scm.OutboundUrlPolicy;
import com.forgepilot.scm.PullRequestSnapshot;
import com.forgepilot.scm.ScmRepository;
import com.forgepilot.scm.ScmSecretCipher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Reads one internally consistent, authoritative merge-request snapshot. */
@Component
class GitLabClient {

    private static final int PAGE_SIZE = 100;
    private static final int CONSISTENCY_ATTEMPTS = 2;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final OutboundUrlPolicy outbound;
    private final ScmSecretCipher cipher;
    private final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();

    GitLabClient(OutboundUrlPolicy outbound, ScmSecretCipher cipher) {
        this.outbound = outbound;
        this.cipher = cipher;
        this.requestFactory.setReadTimeout(READ_TIMEOUT);
    }

    PullRequestSnapshot fetch(ScmRepository repository, int number) {
        RestClient client = clientFor(repository);
        for (int attempt = 0; attempt < CONSISTENCY_ATTEMPTS; attempt++) {
            JsonNode before = mergeRequest(client, repository.getExternalId(), number);
            MrIdentity identity = identity(before, number);
            String version = currentVersion(client, repository.getExternalId(), number, identity);
            List<ChangedFile> files = changedFiles(client, repository.getExternalId(), number);
            MrIdentity after = identity(mergeRequest(client, repository.getExternalId(), number), number);
            if (identity.sameInputAs(after)) {
                return new PullRequestSnapshot(number, identity.baseSha(), identity.headSha(),
                        identity.headRef(), identity.title(), version, identity.updatedAt(),
                        identity.authorId(), identity.authorUsername(), files);
            }
        }
        throw unavailable("GitLab changed the merge request while its diff was being read.");
    }

    private RestClient clientFor(ScmRepository repository) {
        URI base = outbound.requireAllowed(repository.getApiBase());
        return RestClient.builder()
                .baseUrl(base.toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .defaultHeader("PRIVATE-TOKEN", cipher.decrypt(repository.getEncryptedToken()))
                .build();
    }

    private JsonNode mergeRequest(RestClient client, String project, int number) {
        return request(() -> client.get()
                .uri("/projects/{project}/merge_requests/{number}", project, number)
                .retrieve().body(JsonNode.class));
    }

    private String currentVersion(RestClient client, String project, int number, MrIdentity identity) {
        for (int page = 1; ; page++) {
            int current = page;
            JsonNode versions = request(() -> client.get()
                    .uri(uri -> uri.path("/projects/{project}/merge_requests/{number}/versions")
                            .queryParam("per_page", PAGE_SIZE).queryParam("page", current)
                            .build(project, number))
                    .retrieve().body(JsonNode.class));
            requireArray(versions, "diff versions");
            for (JsonNode version : versions) {
                if (identity.baseSha().equals(requiredText(version, "base_commit_sha"))
                        && identity.headSha().equals(requiredText(version, "head_commit_sha"))) {
                    return requiredValue(version, "id");
                }
            }
            if (versions.size() < PAGE_SIZE) {
                throw malformed("GitLab returned no diff version matching the current merge request.");
            }
        }
    }

    private List<ChangedFile> changedFiles(RestClient client, String project, int number) {
        List<ChangedFile> files = new ArrayList<>();
        int characters = 0;
        for (int page = 1; ; page++) {
            int current = page;
            JsonNode batch = request(() -> client.get()
                    .uri(uri -> uri.path("/projects/{project}/merge_requests/{number}/diffs")
                            .queryParam("per_page", PAGE_SIZE).queryParam("page", current)
                            .build(project, number))
                    .retrieve().body(JsonNode.class));
            requireArray(batch, "merge request diffs");
            for (JsonNode file : batch) {
                String path = requiredText(file, "new_path");
                String patch = patch(file);
                ChangedFile changed = new ChangedFile(path, changeType(file), patch);
                characters += path.length() + (patch == null ? 0 : patch.length());
                if (characters > ChangedFile.MAX_TOTAL_CHARS) {
                    throw ApiException.unprocessable(
                            "This merge request's diff is larger than this deployment stores.");
                }
                files.add(changed);
            }
            if (batch.size() < PAGE_SIZE) {
                return files;
            }
        }
    }

    private static String patch(JsonNode file) {
        if (optionalBoolean(file, "too_large") || optionalBoolean(file, "collapsed")) {
            return null;
        }
        JsonNode patch = file.path("diff");
        if (patch.isMissingNode() || patch.isNull()) {
            return null;
        }
        if (!patch.isTextual()) {
            throw malformed("GitLab's diff has an invalid diff body.");
        }
        return patch.asString();
    }

    private static String changeType(JsonNode file) {
        boolean added = requiredBoolean(file, "new_file");
        boolean deleted = requiredBoolean(file, "deleted_file");
        boolean renamed = requiredBoolean(file, "renamed_file");
        if (added) {
            return "added";
        }
        if (deleted) {
            return "deleted";
        }
        return renamed ? "renamed" : "modified";
    }

    private static MrIdentity identity(JsonNode node, int requestedNumber) {
        int iid = requiredInteger(node, "iid");
        if (iid != requestedNumber) {
            throw malformed("GitLab returned a different merge request IID.");
        }
        JsonNode refs = node.path("diff_refs");
        JsonNode author = node.path("author");
        return new MrIdentity(
                requiredText(refs, "base_sha", "diff_refs.base_sha"),
                requiredText(refs, "head_sha", "diff_refs.head_sha"),
                requiredText(node, "source_branch"), requiredText(node, "title"),
                requiredInstant(node, "updated_at"), requiredValue(author, "id", "author.id"),
                requiredText(author, "username", "author.username"));
    }

    private static JsonNode request(Supplier<JsonNode> call) {
        try {
            JsonNode response = call.get();
            if (response == null) {
                throw malformed("GitLab returned an empty response.");
            }
            return response;
        } catch (RestClientResponseException response) {
            if (response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError()) {
                throw unavailable("GitLab is temporarily unable to provide the merge request.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "provider_error",
                    "GitLab refused the authoritative merge request read.");
        } catch (ResourceAccessException network) {
            throw unavailable("GitLab is temporarily unable to provide the merge request.");
        }
    }

    private static void requireArray(JsonNode node, String label) {
        if (!node.isArray()) {
            throw malformed("GitLab returned malformed " + label + ".");
        }
    }

    private static String requiredText(JsonNode parent, String field) {
        return requiredText(parent, field, field);
    }

    private static String requiredText(JsonNode parent, String field, String label) {
        JsonNode node = parent.path(field);
        if (!node.isTextual()) {
            throw malformed("GitLab's merge request is missing " + label + ".");
        }
        String value = node.asString();
        if (value.isBlank()) {
            throw malformed("GitLab's merge request has an empty " + label + ".");
        }
        return value;
    }

    private static String requiredValue(JsonNode parent, String field) {
        return requiredValue(parent, field, field);
    }

    private static String requiredValue(JsonNode parent, String field, String label) {
        JsonNode node = parent.path(field);
        if (!node.isValueNode() || node.isNull()) {
            throw malformed("GitLab's merge request is missing " + label + ".");
        }
        String value = node.asString();
        if (value.isBlank()) {
            throw malformed("GitLab's merge request has an empty " + label + ".");
        }
        return value;
    }

    private static int requiredInteger(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw malformed("GitLab's merge request is missing " + field + ".");
        }
        return node.asInt();
    }

    private static boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isBoolean()) {
            throw malformed("GitLab's diff is missing " + field + ".");
        }
        return node.asBoolean();
    }

    private static boolean optionalBoolean(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode()) {
            return false; // GitLab before 18.4 did not expose these fields.
        }
        if (!node.isBoolean()) {
            throw malformed("GitLab's diff has an invalid " + field + ".");
        }
        return node.asBoolean();
    }

    private static Instant requiredInstant(JsonNode parent, String field) {
        String value = requiredText(parent, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException malformed) {
            throw malformed("GitLab's merge request has an invalid " + field + ".");
        }
    }

    private static ApiException malformed(String message) {
        return ApiException.unprocessable(message);
    }

    private static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "provider_unavailable", message);
    }

    private record MrIdentity(String baseSha, String headSha, String headRef, String title,
            Instant updatedAt, String authorId, String authorUsername) {

        boolean sameInputAs(MrIdentity other) {
            return baseSha.equals(other.baseSha) && headSha.equals(other.headSha)
                    && headRef.equals(other.headRef) && title.equals(other.title)
                    && updatedAt.equals(other.updatedAt) && authorId.equals(other.authorId)
                    && authorUsername.equals(other.authorUsername);
        }
    }
}
