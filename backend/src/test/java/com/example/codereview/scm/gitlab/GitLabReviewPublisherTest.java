package com.example.codereview.scm.gitlab;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class GitLabReviewPublisherTest {

    private WireMockServer server;
    private GitLabReviewPublisher publisher;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        publisher = new GitLabReviewPublisher(new ObjectMapper(), true);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void publishesCommitStatusAndMergeRequestNoteWithReviewEvidence() {
        server.stubFor(post(urlEqualTo("/projects/acme%2Frepo/statuses/abc123"))
                .willReturn(aResponse().withStatus(201)));
        server.stubFor(post(urlEqualTo("/projects/acme%2Frepo/merge_requests/7/notes"))
                .willReturn(aResponse().withStatus(201)));

        ScmPublicationResult result = publisher.publish(context(true), publication(false));

        assertThat(publisher.type()).isEqualTo(ScmProviderType.GITLAB);
        assertThat(result.success()).isTrue();
        assertThat(result.responseCodes()).containsExactly(201, 201);
        server.verify(postRequestedFor(urlEqualTo("/projects/acme%2Frepo/statuses/abc123"))
                .withHeader("PRIVATE-TOKEN", equalTo("gitlab-token"))
                .withRequestBody(WireMock.equalToJson("""
                        {
                          "state": "failed",
                          "name": "reposage/pr-gatekeeper",
                          "target_url": "https://app.example/runs/run-7",
                          "description": "Review found one blocker."
                        }
                        """, true, true)));
        server.verify(postRequestedFor(urlEqualTo("/projects/acme%2Frepo/merge_requests/7/notes"))
                .withHeader("PRIVATE-TOKEN", equalTo("gitlab-token"))
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
                server.baseUrl(), "gitlab-token", "acme/repo", 7, "abc123", patchApproved);
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
