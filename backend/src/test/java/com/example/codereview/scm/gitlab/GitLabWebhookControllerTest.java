package com.example.codereview.scm.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.agent.run.AgentRun;
import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.scm.ScmInstallation;
import com.example.codereview.scm.ScmInstallationRepository;
import com.example.codereview.scm.ScmProviderType;
import com.example.codereview.scm.WebhookDelivery;
import com.example.codereview.scm.WebhookDeliveryRepository;
import com.example.codereview.scm.WebhookDeliveryStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end webhook behaviour for the GitLab SCM endpoint: token gating, reviewable MR-action
 * handling, ack-ignore for the rest, and idempotent replay returning the original Agent Run.
 */
@SpringBootTest(properties = {
        "app.security.token-secret=test-secret",
        "app.security.token-encrypt-key=test-encrypt-key",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
@AutoConfigureMockMvc
class GitLabWebhookControllerTest {

    private static final String TOKEN = "gl-webhook-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScmInstallationRepository installations;

    @Autowired
    private WebhookDeliveryRepository deliveries;

    @Autowired
    private AgentRunRepository agentRuns;

    @Autowired
    private CryptoService crypto;

    @BeforeEach
    void setUp() {
        deliveries.deleteAll();
        agentRuns.deleteAll();
        installations.deleteAll();
        ScmInstallation installation = new ScmInstallation();
        installation.setProvider(ScmProviderType.GITLAB);
        installation.setExternalInstallationId("99");
        installation.setProjectId(1L);
        installation.setRepositoryId(2L);
        installation.setEncryptedWebhookSecret(crypto.encrypt(TOKEN));
        installation.setActive(true);
        installations.save(installation);
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        mockMvc.perform(request(fixture("mr_open.json"), "wrong-token", "u-bad"))
                .andExpect(status().isUnauthorized());
        assertThat(deliveries.findByProviderAndDeliveryId(ScmProviderType.GITLAB, "u-bad"))
                .get().extracting(WebhookDelivery::getStatus).isEqualTo(WebhookDeliveryStatus.REJECTED);
    }

    @Test
    void acceptsOpenMergeRequestAndStartsReceivedRun() throws Exception {
        mockMvc.perform(request(fixture("mr_open.json"), TOKEN, "u-open"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("PROCESSED"))
                .andExpect(jsonPath("$.data.agentRunId").isNumber());

        assertThat(agentRuns.findByTriggerKey("gitlab:99:pr:5:glhead111"))
                .get().extracting(AgentRun::getStatus).isEqualTo(AgentRunStatus.RECEIVED);
    }

    @Test
    void handlesReopenAndUpdateActions() throws Exception {
        mockMvc.perform(request(fixture("mr_reopen.json"), TOKEN, "u-reopen"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("PROCESSED"));
        mockMvc.perform(request(fixture("mr_update.json"), TOKEN, "u-update"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("PROCESSED"));

        assertThat(agentRuns.findByTriggerKey("gitlab:99:pr:5:glhead333")).isPresent();
        assertThat(agentRuns.findByTriggerKey("gitlab:99:pr:5:glhead222")).isPresent();
    }

    @Test
    void ignoresUnrelatedActionWithoutStartingRun() throws Exception {
        mockMvc.perform(request(fixture("mr_close.json"), TOKEN, "u-close"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("IGNORED_ACTION"));

        assertThat(agentRuns.count()).isZero();
        assertThat(deliveries.findByProviderAndDeliveryId(ScmProviderType.GITLAB, "u-close"))
                .get().extracting(WebhookDelivery::getStatus).isEqualTo(WebhookDeliveryStatus.VERIFIED);
    }

    @Test
    void duplicateDeliveryReturnsExistingRunWithoutReprocessing() throws Exception {
        byte[] body = fixture("mr_open.json");
        mockMvc.perform(request(body, TOKEN, "u-dup"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("PROCESSED"));
        mockMvc.perform(request(body, TOKEN, "u-dup"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));

        assertThat(agentRuns.count()).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder request(byte[] body, String token, String uuid) {
        return post("/api/webhooks/scm/gitlab")
                .header("X-Gitlab-Event", "Merge Request Hook")
                .header("X-Gitlab-Token", token)
                .header("X-Gitlab-Event-UUID", uuid)
                .content(body);
    }

    private static byte[] fixture(String name) {
        try (var in = GitLabWebhookControllerTest.class.getResourceAsStream("/webhooks/gitlab/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
