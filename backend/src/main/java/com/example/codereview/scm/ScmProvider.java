package com.example.codereview.scm;

import java.util.Map;
import java.util.Optional;

/**
 * Provider adapter that turns one host's webhook dialect into the shared SCM domain model.
 *
 * <p>Every adapter (GitHub, GitLab, ...) returns the <em>same</em> normalized records, so the rest
 * of the platform stays provider-neutral. The method order mirrors the security boundary: resolve
 * identity from the payload, use it to select the installation secret, verify the signature against
 * the exact raw bytes, and only then normalize.
 *
 * <p>Header maps passed to these methods are expected to be case-insensitive (or already
 * lower-cased) by the caller.
 */
public interface ScmProvider {

    /** The provider family this adapter handles. */
    ScmProviderType type();

    /**
     * The provider's installation/project identifier carried by the delivery, used to resolve the
     * {@link ScmInstallation} and therefore its secret. Read from payload identity fields only —
     * never a secret, host, or other trust-bearing value.
     *
     * @return the reference, or {@code null} if the delivery carries no resolvable identity
     */
    String resolveInstallationRef(byte[] rawBody, Map<String, String> headers);

    /**
     * Verifies the delivery against the resolved installation secret, over the exact raw request
     * bytes, before any JSON normalization. Constant-time where a MAC is involved.
     */
    boolean verifySignature(byte[] rawBody, Map<String, String> headers, String secret);

    /**
     * Normalizes a reviewable pull/merge request delivery into the shared model.
     *
     * @return the normalized event, or {@link Optional#empty()} for deliveries we intentionally
     *         ignore (non-PR events, unrelated actions)
     */
    Optional<NormalizedPullRequestEvent> normalize(byte[] rawBody, Map<String, String> headers);
}
