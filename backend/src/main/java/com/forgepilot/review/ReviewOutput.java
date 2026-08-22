package com.forgepilot.review;

import java.util.List;

/**
 * The validated result of one Review: a verdict for every acceptance criterion,
 * the findings that survived validation, and what validation refused.
 *
 * <p>This is what {@link ReviewOutputValidator} produces, never what the model
 * returned. Every field here has been checked against the Review's own immutable
 * context — the acceptance criteria of its requirement revision, the recall
 * whitelist and the changed files it was actually shown.
 *
 * <p>{@link #warnings} exists because dropping a hallucinated citation silently
 * would leave the operator with a shorter report and no way to tell it was
 * shortened. That is the same rule D002 states for unreviewed files, applied to
 * unreviewable claims.
 */
public record ReviewOutput(List<AcResult> acVerdicts, List<FindingCandidate> findings,
        List<String> warnings) {

    public ReviewOutput {
        acVerdicts = List.copyOf(acVerdicts);
        findings = List.copyOf(findings);
        warnings = List.copyOf(warnings);
    }

    /**
     * One AC's verdict. {@code acKey} travels alongside {@code acId} because it is
     * the stable cross-revision identity (D011): the id changes when a new revision
     * is published, the key does not, and history pages compare across revisions.
     */
    public record AcResult(long acId, String acKey, AcVerdict verdict) {
    }

    /**
     * A finding that passed validation but has not been given its lineage yet —
     * {@link FindingContinuityCalculator} decides {@code continuity} and
     * {@code carried_from_finding_id} afterwards, because those depend on the pull
     * request's history rather than on this answer.
     *
     * <p>{@code requirementId} and {@code requirementRevisionId} are copied from
     * the Review's own context and never read from the model, so a finding cannot
     * disagree with its parent. What the model does choose is {@code acId}, and
     * that is checked against the current revision's criteria.
     *
     * <p>There is no field for the model's prose. The {@code finding} table has no
     * column for a title, a severity or a description, and the three lineage keys
     * must not cover model wording (D009) — so the only text carried is
     * {@code evidence}, the excerpt itself.
     */
    public record FindingCandidate(FindingType findingType, String path, Integer line, String evidence,
            Long requirementId, Long requirementRevisionId, Long acId, String acKey,
            String findingKey, String evidenceHash, String basisHash) {
    }
}
