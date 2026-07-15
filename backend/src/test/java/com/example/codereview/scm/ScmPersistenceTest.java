package com.example.codereview.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.codereview.common.security.CryptoService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

/**
 * Persistence contract for SCM installations and webhook deliveries: encrypted credentials never
 * land as plaintext, both idempotency keys are unique, every delivery status round-trips, and the
 * stored payload preview is bounded.
 */
@DataJpaTest
@Import(CryptoService.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:scmtestdb;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "app.security.token-encrypt-key=test-encrypt-key"
})
class ScmPersistenceTest {

    @Autowired
    private ScmInstallationRepository installations;

    @Autowired
    private WebhookDeliveryRepository deliveries;

    @Autowired
    private CryptoService crypto;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsInstallationWithEncryptedCredentials() {
        ScmInstallation inst = installation(ScmProviderType.GITHUB, "inst-123");
        inst.setAppId("app-1");
        inst.setApiBaseUrl("https://api.github.com");
        inst.setEncryptedWebhookSecret(crypto.encrypt("wh-secret"));
        inst.setEncryptedCredential(crypto.encrypt("private-key-pem"));
        inst = installations.save(inst);
        entityManager.flush();
        entityManager.clear();

        ScmInstallation reloaded = installations.findById(inst.getId()).orElseThrow();
        // Stored columns are ciphertext, not the original secrets.
        assertThat(reloaded.getEncryptedWebhookSecret()).isNotNull().isNotEqualTo("wh-secret");
        assertThat(reloaded.getEncryptedCredential()).isNotNull().isNotEqualTo("private-key-pem");
        // ...but they round-trip through CryptoService.
        assertThat(crypto.decrypt(reloaded.getEncryptedWebhookSecret())).isEqualTo("wh-secret");
        assertThat(crypto.decrypt(reloaded.getEncryptedCredential())).isEqualTo("private-key-pem");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateProviderAndExternalInstallationId() {
        installations.saveAndFlush(installation(ScmProviderType.GITHUB, "dup-1"));
        assertThatThrownBy(() -> installations.saveAndFlush(installation(ScmProviderType.GITHUB, "dup-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameExternalInstallationIdAcrossProviders() {
        installations.saveAndFlush(installation(ScmProviderType.GITHUB, "shared"));
        assertThatCode(() -> installations.saveAndFlush(installation(ScmProviderType.GITLAB, "shared")))
                .doesNotThrowAnyException();
    }

    @Test
    void persistsEveryDeliveryStatus() {
        for (WebhookDeliveryStatus status : WebhookDeliveryStatus.values()) {
            WebhookDelivery d = delivery(ScmProviderType.GITHUB, "del-" + status);
            d.setStatus(status);
            deliveries.saveAndFlush(d);
        }
        assertThat(deliveries.count()).isEqualTo(WebhookDeliveryStatus.values().length);
        assertThat(deliveries.findByProviderAndDeliveryId(ScmProviderType.GITHUB, "del-PROCESSED"))
                .get()
                .extracting(WebhookDelivery::getStatus)
                .isEqualTo(WebhookDeliveryStatus.PROCESSED);
    }

    @Test
    void rejectsDuplicateProviderAndDeliveryId() {
        deliveries.saveAndFlush(delivery(ScmProviderType.GITHUB, "delivery-1"));
        assertThatThrownBy(() -> deliveries.saveAndFlush(delivery(ScmProviderType.GITHUB, "delivery-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameDeliveryIdAcrossProviders() {
        deliveries.saveAndFlush(delivery(ScmProviderType.GITHUB, "delivery-1"));
        assertThatCode(() -> deliveries.saveAndFlush(delivery(ScmProviderType.GITLAB, "delivery-1")))
                .doesNotThrowAnyException();
    }

    @Test
    void boundsPayloadPreviewToTheConfiguredMaximum() {
        WebhookDelivery d = delivery(ScmProviderType.GITHUB, "big");
        d.setPayloadPreview("x".repeat(WebhookDelivery.PREVIEW_MAX_LENGTH + 500));
        d = deliveries.saveAndFlush(d);
        assertThat(d.getPayloadPreview()).hasSize(WebhookDelivery.PREVIEW_MAX_LENGTH);
    }

    private ScmInstallation installation(ScmProviderType provider, String externalId) {
        ScmInstallation inst = new ScmInstallation();
        inst.setProvider(provider);
        inst.setExternalInstallationId(externalId);
        return inst;
    }

    private WebhookDelivery delivery(ScmProviderType provider, String deliveryId) {
        WebhookDelivery d = new WebhookDelivery();
        d.setProvider(provider);
        d.setDeliveryId(deliveryId);
        d.setStatus(WebhookDeliveryStatus.RECEIVED);
        d.setEventType("pull_request");
        d.setPayloadHash("hash-" + deliveryId);
        return d;
    }
}
