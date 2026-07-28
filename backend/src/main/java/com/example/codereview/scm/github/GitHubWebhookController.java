package com.example.codereview.scm.github;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
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
 * Inbound GitHub App webhook endpoint for the SCM control plane.
 *
 * <p>Public (no bearer token) but gated by per-installation HMAC verification. The raw body is read
 * as bytes so the signature is checked against the exact payload before any parsing. The flow honours
 * the security boundary: dedupe the delivery, resolve installation identity from the payload, select
 * the installation secret, verify, normalize, then start the Agent Run. A duplicate
 * {@code X-GitHub-Delivery} returns the existing run without reprocessing. Always acknowledges with
 * {@code 202 Accepted} once the delivery is durably recorded — repository download and Agent execution
 * happen asynchronously.
 */
@RestController
@RequestMapping("/api/webhooks/scm/github")
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    /** Compact acknowledgement returned to GitHub; never echoes payload content. */
    public record WebhookAck(String deliveryId, Long agentRunId, String outcome) {
    }

    private final GitHubScmProvider provider;
    private final ScmInstallationRepository installations;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookAgentRunService agentRunService;
    private final CryptoService cryptoService;

    public GitHubWebhookController(GitHubScmProvider provider,
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
        String event = headers.get("X-GitHub-Event");
        String deliveryId = headers.get("X-GitHub-Delivery");

        // Only pull_request deliveries are reviewable; ack-ignore ping/push/etc.
        if (!"pull_request".equals(event)) {
            return accepted(new WebhookAck(deliveryId, null, "IGNORED_EVENT"));
        }
        if (deliveryId == null || deliveryId.isBlank()) {
            throw new BusinessException(400, "缺少 X-GitHub-Delivery");
        }

        // Idempotency: a delivery we have already seen returns its run, no reprocessing.
        Optional<WebhookDelivery> prior = deliveries.findByProviderAndDeliveryId(ScmProviderType.GITHUB, deliveryId);
        if (prior.isPresent()) {
            return accepted(new WebhookAck(deliveryId, prior.get().getAgentRunId(), "DUPLICATE"));
        }

        WebhookDelivery delivery = newDelivery(deliveryId, raw, event);

        // Resolve identity from the payload, then select the secret from the installation (never the payload).
        String installationRef = provider.resolveInstallationRef(raw, headers);
        ScmInstallation installation = installationRef == null ? null
                : installations.findByProviderAndExternalInstallationIdAndActiveTrue(
                        ScmProviderType.GITHUB, installationRef).orElse(null);
        if (installation == null) {
            delivery.setStatus(WebhookDeliveryStatus.REJECTED);
            deliveries.save(delivery);
            log.info("GitHub webhook: 未找到安装 ref={}", installationRef);
            return accepted(new WebhookAck(deliveryId, null, "NO_INSTALLATION"));
        }
        delivery.setInstallationId(installation.getId());

        // Verify the signature over the exact raw bytes, before parsing.
        String secret = cryptoService.decrypt(installation.getEncryptedWebhookSecret());
        if (!provider.verifySignature(raw, headers, secret)) {
            delivery.setStatus(WebhookDeliveryStatus.REJECTED);
            deliveries.save(delivery);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(ErrorCode.WEBHOOK_SIGNATURE_INVALID, "Webhook 签名校验失败"));
        }
        delivery.setStatus(WebhookDeliveryStatus.VERIFIED);

        Optional<NormalizedPullRequestEvent> normalized = provider.normalize(raw, headers);
        if (normalized.isEmpty()) {
            deliveries.save(delivery);
            return accepted(new WebhookAck(deliveryId, null, "IGNORED_ACTION"));
        }
        NormalizedPullRequestEvent prEvent = normalized.get();
        delivery.setAction(prEvent.action());

        if (installation.getProjectId() == null || installation.getRepositoryId() == null) {
            deliveries.save(delivery);
            return accepted(new WebhookAck(deliveryId, null, "INSTALLATION_NOT_BOUND"));
        }

        WebhookAgentRunService.StartResult result = agentRunService.startFromEvent(prEvent, installation);
        delivery.setStatus(WebhookDeliveryStatus.PROCESSED);
        delivery.setAgentRunId(result.run().getId());
        deliveries.save(delivery);
        return accepted(new WebhookAck(deliveryId, result.run().getId(),
                result.created() ? "PROCESSED" : "DUPLICATE"));
    }

    private WebhookDelivery newDelivery(String deliveryId, byte[] raw, String event) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setProvider(ScmProviderType.GITHUB);
        delivery.setDeliveryId(deliveryId);
        delivery.setEventType(event);
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
