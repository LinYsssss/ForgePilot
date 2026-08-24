package com.forgepilot.scm;

import java.net.URI;
import java.time.Duration;

import com.forgepilot.common.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Provider calls made with a one-time personal token. The token is never returned or stored. */
@Component
class ScmIdentityVerifier {

    private final OutboundUrlPolicy outbound;
    private final JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory();

    ScmIdentityVerifier(OutboundUrlPolicy outbound) {
        this.outbound = outbound;
        this.requests.setReadTimeout(Duration.ofSeconds(20));
    }

    VerifiedScmUser currentUser(ScmProvider provider, String apiBase, String token) {
        URI base = outbound.requireAllowed(apiBase);
        JsonNode user = get(client(provider, base, token), "user");
        return new VerifiedScmUser(provider, InstanceIdentity.of(base), required(user, "id"),
                required(user, provider == ScmProvider.GITHUB ? "login" : "username"));
    }

    ProjectMemberScmBinding.AccessLevel repositoryAccess(ScmRepository repository, String token) {
        URI base = outbound.requireAllowed(repository.getApiBase());
        JsonNode response = get(client(repository.getProvider(), base, token),
                repository.getProvider() == ScmProvider.GITHUB
                        ? "repositories/" + repository.getExternalId()
                        : "projects/" + repository.getExternalId());
        return repository.getProvider() == ScmProvider.GITHUB
                ? githubAccess(response) : gitLabAccess(response);
    }

    private RestClient client(ScmProvider provider, URI base, String token) {
        String baseUrl = base.toString();
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(requests).defaultHeader(HttpHeaders.ACCEPT, "application/json");
        if (provider == ScmProvider.GITHUB) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        } else {
            builder.defaultHeader("PRIVATE-TOKEN", token);
        }
        return builder.build();
    }

    private static JsonNode get(RestClient client, String path) {
        try {
            JsonNode response = client.get().uri(path).retrieve().body(JsonNode.class);
            if (response == null) {
                throw ApiException.unprocessable("The SCM provider returned an empty response.");
            }
            return response;
        } catch (RestClientException failure) {
            throw ApiException.unprocessable("The SCM identity or repository access could not be verified.");
        }
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isValueNode() || value.isNull() || value.asString().isBlank()) {
            throw ApiException.unprocessable("The SCM provider did not return a stable user identity.");
        }
        return value.asString();
    }

    private static ProjectMemberScmBinding.AccessLevel githubAccess(JsonNode response) {
        JsonNode permissions = response.path("permissions");
        if (permissions.path("admin").asBoolean(false)) return ProjectMemberScmBinding.AccessLevel.ADMIN;
        if (permissions.path("push").asBoolean(false)
                || permissions.path("maintain").asBoolean(false)) {
            return ProjectMemberScmBinding.AccessLevel.WRITE;
        }
        return ProjectMemberScmBinding.AccessLevel.READ;
    }

    private static ProjectMemberScmBinding.AccessLevel gitLabAccess(JsonNode response) {
        int direct = response.path("permissions").path("project_access").path("access_level").asInt(0);
        int group = response.path("permissions").path("group_access").path("access_level").asInt(0);
        int level = Math.max(direct, group);
        if (level >= 40) return ProjectMemberScmBinding.AccessLevel.ADMIN;
        if (level >= 30) return ProjectMemberScmBinding.AccessLevel.WRITE;
        return ProjectMemberScmBinding.AccessLevel.READ;
    }
}
