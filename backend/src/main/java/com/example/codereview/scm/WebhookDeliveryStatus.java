package com.example.codereview.scm;

/**
 * Lifecycle of a received webhook delivery, from raw receipt through verification and processing.
 *
 * <p>Persisted on {@link WebhookDelivery} for idempotency and audit. A delivery is captured as
 * {@link #RECEIVED} before any trust decision, promoted to {@link #VERIFIED} once its signature/token
 * matches the resolved installation secret, and finally {@link #PROCESSED} once it has produced an
 * Agent Run. Replays surface as {@link #DUPLICATE}; untrusted or unmatched deliveries as
 * {@link #REJECTED}; unexpected post-verification errors as {@link #FAILED}.
 */
public enum WebhookDeliveryStatus {
    /** Raw delivery captured before signature/token verification. */
    RECEIVED,
    /** Signature or token verified against the resolved installation secret. */
    VERIFIED,
    /** A delivery with this {@code (provider, deliveryId)} was already recorded. */
    DUPLICATE,
    /** Verification failed or no installation matched; never processed. */
    REJECTED,
    /** Successfully turned into an Agent Run / outbox event. */
    PROCESSED,
    /** Processing raised an unexpected error after verification. */
    FAILED
}
