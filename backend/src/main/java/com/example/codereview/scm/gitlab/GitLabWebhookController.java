package com.example.codereview.scm.gitlab;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.scm.NormalizedPullRequestEvent;
import com.example.codereview.scm.ScmInstallation;
import com.example.codereview.scm.ScmInstallationRepository;
import com.example.codereview.scm.ScmProviderType;
import com.example.codereview.scm.WebhookAgentRunService;
import com.example.codereview.scm.WebhookDelivery;
import com.example.codereview.scm.WebhookDeliveryRepository;
import com.example.codereview.scm.WebhookDeliveryStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound GitLab webhook endpoint for the SCM control plane.
 *
 * <p>Mirrors the GitHub flow but with GitLab's conventions: the event type is the
 * {@code X-Gitlab-Event} header ("Merge Request Hook"), verification compares the
 * {@code X-Gitlab-Token} against the installation secret, and the idempotency key is derived (UUID
 * header or deterministic hash) since GitLab does not always send a delivery id. A duplicate
 * delivery returns the existing run; everything is acknowledged with {@code 202 Accepted} once
 * durably recorded.
 */
@RestController
@RequestMapping("/api/webhooks/scm/gitlab")
public class GitLabWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookController.class);
    private static final String MERGE_REQUEST_EVENT = "Merge Request Hook";

    /** Compact acknowledgement; never echoes payload content. */
    public record WebhookAck(String deliveryId, Long agentRunId, String outcome) {
    }

    private final GitLabScmProvider provider;
    private final ScmInstallationRepository installations;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookAgentRunService agentRunService;
    private final CryptoService cryptoService;

    public GitLabWebhookController(GitLabScmProvider provider,
                                   ScmInstallationRepository installations,
                                   WebhookDeliveryRepository deliveries,
                                   WebhookAgentRunService agentRunService,
                                   CryptoService cryptoService) {
        this.provider = provider;
        this.installations = installations;
        this.deliveries = deliveries;
        this.agentRunService = agentRunService;
        this.cryptoService = cryptoService;
    }

    @PostMapping(consumes = MediaType.ALL_VALUE)
    public ResponseEntity<ApiResponse<WebhookAck>> receive(
            @RequestBody(required = false) byte[] body,
            @RequestHeader Map<String, String> headerMap) {
        byte[] raw = body == null ? new byte[0] : body;
        Map<String, String> headers = caseInsensitive(headerMap);

        if (!MERGE_REQUEST_EVENT.equals(headers.get("X-Gitlab-Event"))) {
            return accepted(new WebhookAck(null, null, "IGNORED_EVENT"));
        }

        String deliveryId = provider.deliveryKey(raw, headers);

        // Idempotency: a delivery we have already seen returns its run, no reprocessing.
        Optional<WebhookDelivery> prior = deliveries.findByProviderAndDeliveryId(ScmProviderType.GITLAB, deliveryId);
        if (prior.isPresent()) {
            return accepted(new WebhookAck(deliveryId, prior.get().getAgentRunId(), "DUPLICATE"));
        }

        WebhookDelivery delivery = newDelivery(deliveryId, raw);

        String installationRef = provider.resolveInstallationRef(raw, headers);
        ScmInstallation installation = installationRef == null ? null
                : installations.findByProviderAndExternalInstallationIdAndActiveTrue(
                        ScmProviderType.GITLAB, installationRef).orElse(null);
        if (installation == null) {
            delivery.setStatus(WebhookDeliveryStatus.REJECTED);
            deliveries.save(delivery);
            log.info("GitLab webhook: 未找到安装 ref={}", installationRef);
            return accepted(new WebhookAck(deliveryId, null, "NO_INSTALLATION"));
        }
        delivery.setInstallationId(installation.getId());

        String secret = cryptoService.decrypt(installation.getEncryptedWebhookSecret());
        if (!provider.verifySignature(raw, headers, secret)) {
            delivery.setStatus(WebhookDeliveryStatus.REJECTED);
            deliveries.save(delivery);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiResponse<>(401, "Webhook 令牌校验失败", null));
        }
        delivery.setStatus(WebhookDeliveryStatus.VERIFIED);

        Optional<NormalizedPullRequestEvent> normalized = provider.normalize(raw, headers);
        if (normalized.isEmpty()) {
            deliveries.save(delivery);
            return accepted(new WebhookAck(deliveryId, null, "IGNORED_ACTION"));
        }
        NormalizedPullRequestEvent mrEvent = normalized.get();
        delivery.setAction(mrEvent.action());

        if (installation.getProjectId() == null || installation.getRepositoryId() == null) {
            deliveries.save(delivery);
            return accepted(new WebhookAck(deliveryId, null, "INSTALLATION_NOT_BOUND"));
        }

        WebhookAgentRunService.StartResult result = agentRunService.startFromEvent(mrEvent, installation);
        delivery.setStatus(WebhookDeliveryStatus.PROCESSED);
        delivery.setAgentRunId(result.run().getId());
        deliveries.save(delivery);
        return accepted(new WebhookAck(deliveryId, result.run().getId(),
                result.created() ? "PROCESSED" : "DUPLICATE"));
    }

    private WebhookDelivery newDelivery(String deliveryId, byte[] raw) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setProvider(ScmProviderType.GITLAB);
        delivery.setDeliveryId(deliveryId);
        delivery.setEventType(MERGE_REQUEST_EVENT);
        delivery.setStatus(WebhookDeliveryStatus.RECEIVED);
        delivery.setPayloadHash(sha256Hex(raw));
        delivery.setPayloadPreview(new String(raw, StandardCharsets.UTF_8));
        return delivery;
    }

    private static ResponseEntity<ApiResponse<WebhookAck>> accepted(WebhookAck ack) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(ack));
    }

    private static Map<String, String> caseInsensitive(Map<String, String> source) {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (source != null) {
            map.putAll(source);
        }
        return map;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
