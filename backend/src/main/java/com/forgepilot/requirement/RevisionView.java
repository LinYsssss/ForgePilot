package com.forgepilot.requirement;

import java.time.Instant;
import java.util.List;

/** One revision as the API shows it, with the criteria that belong to that revision. */
public record RevisionView(long id, int seq, String title, String background, String description,
        long createdBy, String createdByUsername, String changeReason, Instant createdAt,
        List<AcceptanceCriterionView> acceptanceCriteria) {

    static RevisionView of(RequirementRevision revision, String createdByUsername,
            List<AcceptanceCriterion> acceptanceCriteria) {
        return new RevisionView(revision.getId(), revision.getSeq(), revision.getTitle(),
                revision.getBackground(), revision.getDescription(), revision.getCreatedBy(),
                createdByUsername, revision.getChangeReason(), revision.getCreatedAt(),
                acceptanceCriteria.stream().map(AcceptanceCriterionView::of).toList());
    }
}
