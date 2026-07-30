package com.example.codereview.scm.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.common.security.SecurityAuditLogger;
import com.example.codereview.scm.ReviewPublication;
import com.example.codereview.scm.ScmProviderType;
import com.example.codereview.scm.ScmPublicationContext;
import com.example.codereview.scm.ScmPublicationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubReviewPublisherTest {

    private WireMockServer server;
    private GitHubReviewPublisher publisher;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        publisher = new GitHubReviewPublisher(new ObjectMapper(), true, new SecurityAuditLogger("test-audit-salt"));
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void publishesCheckRunAndPullRequestCommentWithReviewEvidence() {
        server.stubFor(post(urlEqualTo("/repos/acme/repo/check-runs"))
                .willReturn(aResponse().withStatus(201)));
        server.stubFor(post(urlEqualTo("/repos/acme/repo/issues/7/comments"))
                .willReturn(aResponse().withStatus(201)));

        ScmPublicationResult result = publisher.publish(context(true), publication(false));

        assertThat(publisher.type()).isEqualTo(ScmProviderType.GITHUB);
        assertThat(result.success()).isTrue();
        assertThat(result.responseCodes()).containsExactly(201, 201);
        server.verify(postRequestedFor(urlEqualTo("/repos/acme/repo/check-runs"))
                .withHeader("Authorization", equalTo("Bearer github-token"))
                .withRequestBody(WireMock.matchingJsonPath("$.name", WireMock.equalTo("RepoSage PR Gatekeeper")))
                .withRequestBody(WireMock.matchingJsonPath("$.head_sha", WireMock.equalTo("abc123")))
                .withRequestBody(WireMock.matchingJsonPath("$.status", WireMock.equalTo("completed")))
                .withRequestBody(WireMock.matchingJsonPath("$.conclusion", WireMock.equalTo("action_required")))
                .withRequestBody(WireMock.matchingJsonPath("$.details_url", WireMock.equalTo("https://app.example/runs/run-7")))
                .withRequestBody(WireMock.matchingJsonPath("$.output.summary", WireMock.containing("SQL injection in UserRepository")))
                .withRequestBody(WireMock.matchingJsonPath("$.output.summary", WireMock.containing("https://app.example/evidence/ev-1")))
                .withRequestBody(WireMock.matchingJsonPath("$.output.summary", WireMock.containing("VALIDATED"))));
        server.verify(postRequestedFor(urlEqualTo("/repos/acme/repo/issues/7/comments"))
                .withHeader("Authorization", equalTo("Bearer github-token"))
                .withRequestBody(WireMock.matchingJsonPath("$.body", WireMock.containing("SQL injection in UserRepository")))
                .withRequestBody(WireMock.matchingJsonPath("$.body", WireMock.containing("https://app.example/evidence/ev-1")))
                .withRequestBody(WireMock.matchingJsonPath("$.body", WireMock.containing("https://app.example/runs/run-7")))
                .withRequestBody(WireMock.matchingJsonPath("$.body", WireMock.containing("VALIDATED"))));
    }

    @Test
    void refusesToPublishPatchContentWithoutApproval() {
        assertThatThrownBy(() -> publisher.publish(context(false), publication(true)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("requires approval");

        assertThat(server.getAllServeEvents()).isEmpty();
    }

    private ScmPublicationContext context(boolean patchApproved) {
        return new ScmPublicationContext(
                server.baseUrl(), "github-token", "acme/repo", 7, "abc123", patchApproved);
    }

    private static ReviewPublication publication(boolean exposesPatchContent) {
        return new ReviewPublication(
                ReviewPublication.Conclusion.ACTION_REQUIRED,
                "Review found one blocker.",
                List.of("SQL injection in UserRepository"),
                List.of("https://app.example/evidence/ev-1"),
                "https://app.example/runs/run-7",
                ReviewPublication.PatchValidationState.VALIDATED,
                exposesPatchContent);
    }
}
