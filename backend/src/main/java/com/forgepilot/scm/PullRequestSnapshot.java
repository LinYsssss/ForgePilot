package com.forgepilot.scm;

import java.time.Instant;
import java.util.List;

/**
 * What the provider says a pull request currently is. A webhook delivery is only a
 * signal; this comes from a fresh authoritative read, which is what makes replays
 * harmless and out-of-order deliveries detectable.
 *
 * @param sourceRevision the provider's stable diff revision if it has one. GitHub
 *     exposes none, so it is null there; it orders events and never enters the
 *     fingerprint.
 */
public record PullRequestSnapshot(
        int externalNumber,
        String baseSha,
        String headSha,
        String headRef,
        String title,
        String sourceRevision,
        Instant sourceUpdatedAt,
        String authorExternalUserId,
        String authorUsername,
        List<ChangedFile> changedFiles) {
}
