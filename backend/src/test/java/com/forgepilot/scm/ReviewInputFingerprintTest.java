package com.forgepilot.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The fingerprint rule is frozen. If it ever changes, every stored fingerprint and
 * therefore every Review identity derived from one becomes wrong, so the expected
 * digest below is pinned as a literal: it was computed from the documented byte
 * layout, independently of this implementation, and any change to
 * the normalization has to fail here loudly.
 */
class ReviewInputFingerprintTest {

    private static final String PROVIDER = "GITHUB";
    private static final String INSTANCE = "github.com";
    private static final String EXTERNAL_ID = "123456";
    private static final String BASE = "1111111111111111111111111111111111111111";
    private static final String HEAD = "2222222222222222222222222222222222222222";

    private static final ChangedFile MODIFIED =
            new ChangedFile("src/a.txt", "modified", "@@ -1 +1 @@\r\n-a\r\n+b\r\n");
    private static final ChangedFile ADDED = new ChangedFile("src/B.txt", "added", null);
    private static final ChangedFile RENAMED = new ChangedFile("docs/readme.md", "renamed", "");

    @Test
    void theCanonicalFormIsPinnedToALiteral() {
        assertThat(fingerprintOf(MODIFIED, ADDED, RENAMED))
                .isEqualTo("d35798fd6bd40110bcda6370df6e21cb61163e1fd87ce90c3fb382fca6511bbf");
    }

    @Test
    void thePageOrderTheProviderHappenedToUseDoesNotMatter() {
        // The provider's file list is paginated and its order is conventional, not
        // contractual, so a fingerprint that depended on it would change with
        // pagination alone. Note that the canonical order is by bytes: "src/B.txt"
        // sorts before "src/a.txt".
        assertThat(fingerprintOf(RENAMED, MODIFIED, ADDED)).isEqualTo(fingerprintOf(MODIFIED, ADDED, RENAMED));
        assertThat(fingerprintOf(ADDED, RENAMED, MODIFIED)).isEqualTo(fingerprintOf(MODIFIED, ADDED, RENAMED));
    }

    @Test
    void oneChangedByteAnywhereInTheInputChangesTheValue() {
        String unchanged = fingerprintOf(MODIFIED, ADDED, RENAMED);
        List<String> variants = List.of(
                identifiedAs("GITHUb", INSTANCE, EXTERNAL_ID),
                identifiedAs(PROVIDER, "github.co", EXTERNAL_ID),
                identifiedAs(PROVIDER, INSTANCE, "123457"),
                fingerprintFor(snapshot(BASE.substring(1) + "3", HEAD, MODIFIED, ADDED, RENAMED)),
                fingerprintFor(snapshot(BASE, HEAD.substring(1) + "3", MODIFIED, ADDED, RENAMED)),
                fingerprintOf(new ChangedFile("src/a.txt", "modified", "@@ -1 +1 @@\r\n-a\r\n+c\r\n"),
                        ADDED, RENAMED),
                fingerprintOf(new ChangedFile("src/a.tx", "modified", MODIFIED.patch()), ADDED, RENAMED),
                fingerprintOf(new ChangedFile("src/a.txt", "changed", MODIFIED.patch()), ADDED, RENAMED),
                // Case sensitive paths: the legacy system folded them and merged two
                // different files into one.
                fingerprintOf(new ChangedFile("src/A.txt", "modified", MODIFIED.patch()), ADDED, RENAMED),
                // Line endings are not normalized: a CRLF diff and an LF diff are two
                // different diffs, and folding them would collide two real inputs.
                fingerprintOf(new ChangedFile("src/a.txt", "modified", "@@ -1 +1 @@\n-a\n+b\n"), ADDED, RENAMED),
                // An absent patch is not an empty one.
                fingerprintOf(MODIFIED, ADDED, new ChangedFile("docs/readme.md", "renamed", null)),
                fingerprintOf(MODIFIED, ADDED),
                fingerprintOf(MODIFIED, ADDED, RENAMED, new ChangedFile("src/c.txt", "added", "")));

        assertThat(variants).doesNotContain(unchanged).doesNotHaveDuplicates();
    }

    @Test
    void theSameInputAlwaysProducesTheSameValue() {
        assertThat(fingerprintOf(MODIFIED, ADDED, RENAMED)).isEqualTo(fingerprintOf(MODIFIED, ADDED, RENAMED));
    }

    /**
     * source_revision and source_updated_at exist to order deliveries. If they took
     * part here, a redelivery of the very same diff would mint a new Review identity
     * and the Decision gate would reopen on unchanged code.
     */
    @Test
    void theOrderingFieldsAreNotPartOfTheIdentity() {
        PullRequestSnapshot first = new PullRequestSnapshot(7, BASE, HEAD, "feat/REQ-1-login", "Log in",
                "revision-1", Instant.parse("2026-08-21T10:00:00Z"), "gh-1", "octocat", List.of(MODIFIED));
        PullRequestSnapshot second = new PullRequestSnapshot(7, BASE, HEAD, "feat/REQ-1-login", "Log in",
                "revision-2", Instant.parse("2026-08-21T18:30:00Z"), "gh-1", "octocat", List.of(MODIFIED));

        assertThat(fingerprintFor(second)).isEqualTo(fingerprintFor(first));
    }

    /** Neither the author nor the requirement association may mint a Review identity. */
    @Test
    void theAuthorAndTheTitleAreNotPartOfTheIdentity() {
        PullRequestSnapshot renamed = new PullRequestSnapshot(7, BASE, HEAD, "fix/other-branch",
                "A different title", null, Instant.parse("2026-08-21T10:00:00Z"), "gh-9", "someone-else",
                List.of(MODIFIED));

        assertThat(fingerprintFor(renamed)).isEqualTo(fingerprintOf(MODIFIED));
    }

    private static String fingerprintOf(ChangedFile... files) {
        return fingerprintFor(snapshot(BASE, HEAD, files));
    }

    private static String identifiedAs(String provider, String instance, String externalId) {
        return ReviewInputFingerprint.of(provider, instance, externalId,
                snapshot(BASE, HEAD, MODIFIED, ADDED, RENAMED));
    }

    private static String fingerprintFor(PullRequestSnapshot snapshot) {
        return ReviewInputFingerprint.of(PROVIDER, INSTANCE, EXTERNAL_ID, snapshot);
    }

    private static PullRequestSnapshot snapshot(String base, String head, ChangedFile... files) {
        return new PullRequestSnapshot(7, base, head, "feat/REQ-1-login", "Log in", null,
                Instant.parse("2026-08-21T10:00:00Z"), "gh-1", "octocat", Arrays.asList(files));
    }
}
