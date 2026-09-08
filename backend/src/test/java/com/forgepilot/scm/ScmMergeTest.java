package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.forgepilot.common.ApiException;
import com.forgepilot.scm.github.GitHubClient;
import com.forgepilot.scm.gitlab.GitLabClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Provider effects use a local HTTP server, never credentials or real repositories. */
@SpringBootTest
class ScmMergeTest extends ScmTestBase {
    @Autowired private GitHubClient github;
    @Autowired private GitLabClient gitlab;
    @Autowired private ScmSecretCipher cipher;
    private HttpServer server;
    private final List<String> writes = new ArrayList<>();
    private volatile boolean merged;
    private volatile boolean refuse;
    private volatile boolean ambiguous;
    private volatile boolean responseLost;
    private volatile String head = "reviewed-head";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean githubRequest = exchange.getRequestURI().getPath().startsWith("/repositories/");
            boolean write = !exchange.getRequestMethod().equals("GET");
            if (write) {
                writes.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                        + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                merged = !refuse;
                if (responseLost) {
                    exchange.close();
                    return;
                }
            }
            String state = githubRequest
                    ? "{\"head\":{\"sha\":\"" + head + "\"},\"merged\":" + merged + ",\"state\":\"" + (merged ? "closed" : "open") + "\"}"
                    : "{\"sha\":\"" + head + "\",\"state\":\"" + (merged ? "merged" : "opened") + "\"}";
            byte[] body = state.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(write && ambiguous ? 503 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @ParameterizedTest
    @EnumSource(ScmProvider.class)
    void mergesOnlyReviewedHeadAndRecognizesAlreadyMergedRetry(ScmProvider provider) {
        head = "new-unreviewed-head";
        assertThatThrownBy(() -> merge(provider)).isInstanceOf(ApiException.class);
        assertThat(writes).isEmpty();
        head = "reviewed-head";
        merge(provider);
        assertThat(writes).singleElement().asString().contains("PUT", "\"sha\":\"reviewed-head\"");
        if (provider == ScmProvider.GITLAB) assertThat(writes.getFirst()).contains("\"should_remove_source_branch\":false");
        merge(provider);
        assertThat(writes).hasSize(1);
        head = "new-unreviewed-head";
        assertThatThrownBy(() -> merge(provider)).isInstanceOf(ApiException.class);
        assertThat(writes).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(ScmProvider.class)
    void aSuccessfulHttpResponseDoesNotProveMergeSucceeded(ScmProvider provider) {
        refuse = true;
        assertThatThrownBy(() -> merge(provider)).isInstanceOf(ApiException.class);
        assertThat(writes).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(ScmProvider.class)
    void ambiguousMergeIsConfirmedByReadingNotRepeatedWriting(ScmProvider provider) {
        ambiguous = true;
        merge(provider);
        assertThat(writes).hasSize(1);
        merged = false;
        refuse = true;
        assertThatThrownBy(() -> merge(provider)).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.getCode()).isEqualTo("merge_outcome_unknown"));
        assertThat(writes).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(ScmProvider.class)
    void aLostMergeResponseIsConfirmedWithoutRepeatingTheWrite(ScmProvider provider) {
        responseLost = true;
        merge(provider);
        assertThat(writes).hasSize(1);
    }

    private void merge(ScmProvider provider) {
        ScmRepository repository = new ScmRepository(1L, provider, "test", "123",
                "http://127.0.0.1:" + server.getAddress().getPort(), cipher.encrypt("fake"), cipher.encrypt("fake"));
        if (provider == ScmProvider.GITHUB) github.merge(repository, 6, "reviewed-head");
        else gitlab.merge(repository, 6, "reviewed-head");
    }
}
