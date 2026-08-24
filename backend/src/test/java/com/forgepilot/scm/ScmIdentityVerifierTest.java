package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class ScmIdentityVerifierTest {

    @Test
    void githubUsesTheCurrentUserAndStableRepositoryEndpoints() throws Exception {
        try (ProviderStub provider = new ProviderStub()) {
            provider.response("/user", "{\"id\":42,\"login\":\"octocat\"}");
            provider.response("/repositories/99", "{\"permissions\":{\"admin\":true}}");
            ScmIdentityVerifier verifier = verifier();

            VerifiedScmUser user = verifier.currentUser(ScmProvider.GITHUB, provider.baseUrl(), "token-1");
            ScmRepository repository = new ScmRepository(1L, ScmProvider.GITHUB,
                    provider.instanceIdentity(), "99", provider.baseUrl(), "x", "y");

            assertThat(user.externalUserId()).isEqualTo("42");
            assertThat(user.externalUsername()).isEqualTo("octocat");
            assertThat(verifier.repositoryAccess(repository, "token-1"))
                    .isEqualTo(ProjectMemberScmBinding.AccessLevel.ADMIN);
            assertThat(provider.paths()).containsExactly("/user", "/repositories/99");
            assertThat(provider.authorizationHeaders()).containsOnly("Bearer token-1");
        }
    }

    @Test
    void gitlabPreservesTheApiBasePathForUserAndProjectChecks() throws Exception {
        try (ProviderStub provider = new ProviderStub()) {
            provider.response("/api/v4/user", "{\"id\":84,\"username\":\"gitlab-user\"}");
            provider.response("/api/v4/projects/123", "{\"permissions\":{\"group_access\":{\"access_level\":30}}}");
            ScmIdentityVerifier verifier = verifier();
            String apiBase = provider.baseUrl() + "/api/v4";

            VerifiedScmUser user = verifier.currentUser(ScmProvider.GITLAB, apiBase, "token-2");
            ScmRepository repository = new ScmRepository(1L, ScmProvider.GITLAB,
                    provider.instanceIdentity(), "123", apiBase, "x", "y");

            assertThat(user.externalUserId()).isEqualTo("84");
            assertThat(user.externalUsername()).isEqualTo("gitlab-user");
            assertThat(verifier.repositoryAccess(repository, "token-2"))
                    .isEqualTo(ProjectMemberScmBinding.AccessLevel.WRITE);
            assertThat(provider.paths()).containsExactly("/api/v4/user", "/api/v4/projects/123");
            assertThat(provider.privateTokenHeaders()).containsOnly("token-2");
        }
    }

    private static ScmIdentityVerifier verifier() {
        return new ScmIdentityVerifier(new OutboundUrlPolicy("127.0.0.1"));
    }

    private static final class ProviderStub implements AutoCloseable {
        private final HttpServer server;
        private final List<String> paths = new ArrayList<>();
        private final List<String> authorizationHeaders = new ArrayList<>();
        private final List<String> privateTokenHeaders = new ArrayList<>();

        private ProviderStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
        }

        private void response(String path, String body) {
            server.createContext(path, exchange -> respond(exchange, body));
        }

        private void respond(HttpExchange exchange, String body) throws IOException {
            paths.add(exchange.getRequestURI().getPath());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) authorizationHeaders.add(authorization);
            String privateToken = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN");
            if (privateToken != null) privateTokenHeaders.add(privateToken);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String instanceIdentity() {
            return "127.0.0.1:" + server.getAddress().getPort();
        }

        private List<String> paths() { return paths; }
        private List<String> authorizationHeaders() { return authorizationHeaders; }
        private List<String> privateTokenHeaders() { return privateTokenHeaders; }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
