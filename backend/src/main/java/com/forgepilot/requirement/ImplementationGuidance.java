package com.forgepilot.requirement;

/**
 * One-shot implementation guidance for one revision of one requirement.
 *
 * <p>The revision is named in the answer because that is what the guidance
 * describes: the prose is immutable, so guidance produced for revision 2 stays
 * true of revision 2 after revision 3 is published, and a reader who cannot see
 * which revision was used cannot tell whether the advice is still current.
 */
public record ImplementationGuidance(long requirementId, long revisionId, int revisionSeq,
        String guidance) {
}
