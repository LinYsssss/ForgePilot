package com.example.codereview.scm;

/**
 * Supported source control providers.
 *
 * <p>Used as the discriminator for {@link ScmInstallation installations}, {@link WebhookDelivery
 * deliveries}, and adapter selection. The provider <em>behaviour</em> (verification, normalization,
 * publication) lives behind the {@code ScmProvider} adapter interface; this enum only identifies the
 * host family so a single secret and API base can be resolved per installation.
 */
public enum ScmProviderType {
    GITHUB,
    GITLAB
}
