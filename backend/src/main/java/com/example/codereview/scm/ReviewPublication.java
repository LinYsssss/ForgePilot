package com.example.codereview.scm;

import java.util.List;

/**
 * Provider-neutral result of an Agent review, ready to be rendered into a GitHub Check / PR comment
 * or a GitLab MR note / commit status by the matching adapter.
 *
 * <p>Carries the summary, blocking findings, evidence links, the Agent Run URL, and the patch
 * validation state. {@link #exposesPatchContent()} marks publications that would reveal a
 * generated patch — those require explicit approval before they are sent (see Task 11). Findings
 * and evidence lists are defensively copied and never null.
 */
public record ReviewPublication(
        Conclusion conclusion,
        String summary,
        List<String> blockingFindings,
        List<String> evidenceLinks,
        String agentRunUrl,
        PatchValidationState patchValidationState,
        boolean exposesPatchContent
) {
    /** High-level outcome, mapped per provider to a check conclusion / MR status. */
    public enum Conclusion {
        SUCCESS,
        ACTION_REQUIRED,
        NEUTRAL
    }

    /** State of any generated patch's validation, surfaced to reviewers. */
    public enum PatchValidationState {
        NOT_APPLICABLE,
        PENDING,
        VALIDATED,
        FAILED,
        PENDING_APPROVAL
    }

    public ReviewPublication {
        if (conclusion == null) {
            throw new IllegalArgumentException("conclusion is required");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary is required");
        }
        if (agentRunUrl == null || agentRunUrl.isBlank()) {
            throw new IllegalArgumentException("agentRunUrl is required");
        }
        patchValidationState = patchValidationState == null ? PatchValidationState.NOT_APPLICABLE : patchValidationState;
        blockingFindings = blockingFindings == null ? List.of() : List.copyOf(blockingFindings);
        evidenceLinks = evidenceLinks == null ? List.of() : List.copyOf(evidenceLinks);
    }
}
