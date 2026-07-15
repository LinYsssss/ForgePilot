package com.example.codereview.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the provider-neutral SCM contract: the normalized records require exactly the fields every
 * adapter must supply, so a GitHub and a GitLab delivery for the same PR yield interchangeable
 * domain objects. The public fixtures and {@link #assertSatisfiesContract} are reused by the
 * concrete GitHub/GitLab adapter tests to assert they return the same shape.
 */
public class ScmProviderContractTest {

    @Test
    void validNormalizedEventExposesEveryRequiredField() {
        NormalizedPullRequestEvent event = sampleEvent(ScmProviderType.GITHUB);
        assertSatisfiesContract(event);
        assertThat(event.provider()).isEqualTo(ScmProviderType.GITHUB);
        assertThat(event.pullRequestNumber()).isPositive();
    }

    @Test
    void normalizedEventRejectsMissingRequiredFields() {
        assertThatThrownBy(() -> new NormalizedPullRequestEvent(
                null, "inst", "acme/widgets", "https://host/acme/widgets.git",
                7, "t", "octocat", "feature", "main", "base", "head", "opened", "del-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provider");

        assertThatThrownBy(() -> new NormalizedPullRequestEvent(
                ScmProviderType.GITHUB, " ", "acme/widgets", "https://host/acme/widgets.git",
                7, "t", "octocat", "feature", "main", "base", "head", "opened", "del-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("installationRef");

        assertThatThrownBy(() -> new NormalizedPullRequestEvent(
                ScmProviderType.GITHUB, "inst", "acme/widgets", "  ",
                7, "t", "octocat", "feature", "main", "base", "head", "opened", "del-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repositoryCloneUrl");

        assertThatThrownBy(() -> new NormalizedPullRequestEvent(
                ScmProviderType.GITHUB, "inst", "acme/widgets", "https://host/acme/widgets.git",
                7, "t", "octocat", "feature", "main", "base", "  ", "opened", "del-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("headSha");

        assertThatThrownBy(() -> new NormalizedPullRequestEvent(
                ScmProviderType.GITHUB, "inst", "acme/widgets", "https://host/acme/widgets.git",
                7, "t", "octocat", "feature", "main", "base", "head", "opened", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deliveryId");

        assertThatThrownBy(() -> new NormalizedPullRequestEvent(
                ScmProviderType.GITHUB, "inst", "acme/widgets", "https://host/acme/widgets.git",
                0, "t", "octocat", "feature", "main", "base", "head", "opened", "del-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pullRequestNumber");
    }

    @Test
    void snapshotRejectsMissingRequiredFields() {
        assertThatThrownBy(() -> new PullRequestSnapshot(
                ScmProviderType.GITLAB, "acme/widgets", "https://host/acme/widgets.git",
                7, "t", "octocat", "feature", "main", "base", "head", "  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("state");

        assertThat(sampleSnapshot(ScmProviderType.GITLAB).headSha()).isEqualTo("headsha");
    }

    @Test
    void publicationRequiresSummaryAndRunUrl() {
        assertThatThrownBy(() -> new ReviewPublication(
                ReviewPublication.Conclusion.SUCCESS, "  ", List.of(), List.of(),
                "https://app/runs/1", ReviewPublication.PatchValidationState.NOT_APPLICABLE, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("summary");

        assertThatThrownBy(() -> new ReviewPublication(
                ReviewPublication.Conclusion.SUCCESS, "ok", List.of(), List.of(),
                null, ReviewPublication.PatchValidationState.NOT_APPLICABLE, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agentRunUrl");
    }

    @Test
    void publicationDefensivelyCopiesAndDefaultsLists() {
        List<String> findings = new ArrayList<>(List.of("npe in OrderService"));
        ReviewPublication pub = new ReviewPublication(
                ReviewPublication.Conclusion.ACTION_REQUIRED, "1 blocking issue", findings, null,
                "https://app/runs/1", null, false);

        // Null lists default to empty; the patch state defaults rather than staying null.
        assertThat(pub.evidenceLinks()).isEmpty();
        assertThat(pub.patchValidationState()).isEqualTo(ReviewPublication.PatchValidationState.NOT_APPLICABLE);

        // Mutating the source list must not leak into the record, and the copy is unmodifiable.
        findings.add("leak");
        assertThat(pub.blockingFindings()).containsExactly("npe in OrderService");
        assertThatThrownBy(() -> pub.blockingFindings().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- Reusable fixtures + assertion shared with GitHub/GitLab adapter tests ----

    /** A fully-populated valid event for the given provider. */
    public static NormalizedPullRequestEvent sampleEvent(ScmProviderType provider) {
        return new NormalizedPullRequestEvent(
                provider, "install-1", "acme/widgets", "https://host/acme/widgets.git",
                42, "Add widget", "octocat", "feature/widget", "main",
                "basesha", "headsha", "opened", "delivery-1");
    }

    public static PullRequestSnapshot sampleSnapshot(ScmProviderType provider) {
        return new PullRequestSnapshot(
                provider, "acme/widgets", "https://host/acme/widgets.git",
                42, "Add widget", "octocat", "feature/widget", "main",
                "basesha", "headsha", "OPEN");
    }

    public static ReviewPublication samplePublication() {
        return new ReviewPublication(
                ReviewPublication.Conclusion.SUCCESS, "No blocking issues", List.of(), List.of(),
                "https://app/runs/1", ReviewPublication.PatchValidationState.NOT_APPLICABLE, false);
    }

    /** Every adapter's normalized output must satisfy this for the platform to stay neutral. */
    public static void assertSatisfiesContract(NormalizedPullRequestEvent event) {
        assertThat(event).isNotNull();
        assertThat(event.provider()).isNotNull();
        assertThat(event.installationRef()).isNotBlank();
        assertThat(event.repositoryCloneUrl()).isNotBlank();
        assertThat(event.pullRequestNumber()).isPositive();
        assertThat(event.baseSha()).isNotBlank();
        assertThat(event.headSha()).isNotBlank();
        assertThat(event.sourceBranch()).isNotBlank();
        assertThat(event.targetBranch()).isNotBlank();
        assertThat(event.deliveryId()).isNotBlank();
    }
}
