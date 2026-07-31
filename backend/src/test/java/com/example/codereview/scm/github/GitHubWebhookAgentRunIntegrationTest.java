package com.example.codereview.scm.github;

import static com.example.codereview.webhook.WebhookSignatures.hmacSha256Hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.codereview.agent.run.AgentRunRepository;
import com.example.codereview.agent.run.AgentRunStatus;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.scm.ScmInstallation;
import com.example.codereview.scm.ScmInstallationRepository;
import com.example.codereview.scm.ScmProviderType;
import com.example.codereview.scm.WebhookDeliveryRepository;
import com.example.codereview.scm.WebhookDeliveryStatus;
import com.example.codereview.support.IntegrationTestContainers;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies a signed GitHub delivery reaches durable Agent Run state on PostgreSQL and RabbitMQ. */
@ActiveProfiles("prod")
@SpringBootTest(properties = {
        "app.review.inline=false",
        "app.security.token-secret=test-secret-at-least-32-characters",
        "app.security.token-encrypt-key=test-encryption-key-at-least-32",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false",
        // 合流修复:prod profile 下 ProdSecretValidator 要求签名密钥非空且 ≥16 字符。
        "app.sandbox.signing-secret=it-only-signing-secret-not-prod"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GitHubWebhookAgentRunIntegrationTest extends IntegrationTestContainers {

    private static final String SECRET = "gh-webhook-secret";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry);
    }

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
        installation.setProvider(ScmProviderType.GITHUB);
        installation.setExternalInstallationId("42");
        installation.setProjectId(1L);
        installation.setRepositoryId(2L);
        installation.setEncryptedWebhookSecret(crypto.encrypt(SECRET));
        installation.setActive(true);
        installations.saveAndFlush(installation);
    }

    @Test
    void signedPullRequestDeliveryCreatesReceivedAgentRunAndProcessedDelivery() throws Exception {
        byte[] body = fixture("opened.json");
        mockMvc.perform(post("/api/webhooks/scm/github")
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", "tc-delivery-1")
                        .header("X-Hub-Signature-256", "sha256=" + hmacSha256Hex(body, SECRET))
                        .content(body))
                .andExpect(status().isAccepted());

        assertThat(agentRuns.findByTriggerKey("github:42:pr:7:headsha111"))
                .get()
                .extracting(run -> run.getStatus())
                .isEqualTo(AgentRunStatus.RECEIVED);
        assertThat(deliveries.findByProviderAndDeliveryId(ScmProviderType.GITHUB, "tc-delivery-1"))
                .get()
                .extracting(delivery -> delivery.getStatus())
                .isEqualTo(WebhookDeliveryStatus.PROCESSED);
    }

    private static byte[] fixture(String name) {
        try (var in = GitHubWebhookAgentRunIntegrationTest.class
                .getResourceAsStream("/webhooks/github/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
