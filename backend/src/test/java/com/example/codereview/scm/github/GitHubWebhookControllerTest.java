package com.example.codereview.scm.github;

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
import com.example.codereview.webhook.WebhookSignatures;
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
 * End-to-end webhook behaviour for the GitHub SCM endpoint: signature gating, reviewable-action
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
class GitHubWebhookControllerTest {

    private static final String SECRET = "gh-webhook-secret";

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
        installations.save(installation);
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        byte[] body = fixture("opened.json");
        mockMvc.perform(signed(body, "sha256=deadbeef", "d-bad"))
                .andExpect(status().isUnauthorized());
        // 验签失败的请求不再落库:端点公开可达,持久化未验签流量等于开放一张可被任意灌入的审计表
        assertThat(deliveries.findByProviderAndDeliveryId(ScmProviderType.GITHUB, "d-bad")).isEmpty();
    }

    @Test
    void acceptsOpenedAndStartsReceivedRun() throws Exception {
        byte[] body = fixture("opened.json");
        mockMvc.perform(validSigned(body, "d-open"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("PROCESSED"))
                .andExpect(jsonPath("$.data.agentRunId").isNumber());

        assertThat(agentRuns.findByTriggerKey("github:42:pr:7:headsha111"))
                .get().extracting(AgentRun::getStatus).isEqualTo(AgentRunStatus.RECEIVED);
    }

    @Test
    void handlesReopenedAndSynchronizeActions() throws Exception {
        mockMvc.perform(validSigned(fixture("reopened.json"), "d-reopen"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("PROCESSED"));
        mockMvc.perform(validSigned(fixture("synchronize.json"), "d-sync"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("PROCESSED"));

        // Different head SHAs => distinct runs.
        assertThat(agentRuns.findByTriggerKey("github:42:pr:7:headsha333")).isPresent();
        assertThat(agentRuns.findByTriggerKey("github:42:pr:7:headsha222")).isPresent();
    }

    @Test
    void ignoresUnrelatedActionWithoutStartingRun() throws Exception {
        mockMvc.perform(validSigned(fixture("labeled.json"), "d-label"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("IGNORED_ACTION"));

        assertThat(agentRuns.count()).isZero();
        assertThat(deliveries.findByProviderAndDeliveryId(ScmProviderType.GITHUB, "d-label"))
                .get().extracting(WebhookDelivery::getStatus).isEqualTo(WebhookDeliveryStatus.VERIFIED);
    }

    @Test
    void duplicateDeliveryReturnsExistingRunWithoutReprocessing() throws Exception {
        byte[] body = fixture("opened.json");
        String firstId = mockMvc.perform(validSigned(body, "d-dup"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("PROCESSED"))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(validSigned(body, "d-dup"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));

        assertThat(agentRuns.count()).isEqualTo(1);
        assertThat(firstId).contains("PROCESSED");
    }

    @Test
    void unverifiedTrafficLeavesNoAuditRecordAndLeaksNoRunId() throws Exception {
        // 先制造一条已处理的投递,拿到真实 runId
        byte[] opened = fixture("opened.json");
        mockMvc.perform(validSigned(opened, "delivery-leak-probe"))
                .andExpect(status().isAccepted());
        long recorded = deliveries.count();
        assertThat(recorded).isEqualTo(1);

        // 错误签名:不得落库,也不得回显任何 runId
        mockMvc.perform(signed(opened, "sha256=deadbeef", "delivery-leak-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist());

        // 未知安装:同样不落库(端点公开可达,否则任何人都能灌审计表)
        mockMvc.perform(validSigned(fixture("opened_unknown_installation.json"), "delivery-unknown-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.outcome").value("NO_INSTALLATION"))
                .andExpect(jsonPath("$.data.agentRunId").doesNotExist());

        assertThat(deliveries.count())
                .as("未验签流量不应新增投递记录")
                .isEqualTo(recorded);
    }

    @Test
    void payloadPreviewIsNotPersistedByDefault() throws Exception {
        mockMvc.perform(validSigned(fixture("opened.json"), "delivery-preview-1"))
                .andExpect(status().isAccepted());

        WebhookDelivery stored = deliveries.findByProviderAndDeliveryId(
                ScmProviderType.GITHUB, "delivery-preview-1").orElseThrow();
        assertThat(stored.getPayloadPreview())
                .as("默认不保存报文正文,只留哈希与元数据")
                .isNull();
        assertThat(stored.getPayloadHash()).isNotBlank();
    }

    private MockHttpServletRequestBuilder validSigned(byte[] body, String deliveryId) {
        return signed(body, "sha256=" + WebhookSignatures.hmacSha256Hex(body, SECRET), deliveryId);
    }

    private MockHttpServletRequestBuilder signed(byte[] body, String signature, String deliveryId) {
        return post("/api/webhooks/scm/github")
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", deliveryId)
                .header("X-Hub-Signature-256", signature)
                .content(body);
    }

    private static byte[] fixture(String name) {
        try (var in = GitHubWebhookControllerTest.class.getResourceAsStream("/webhooks/github/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
