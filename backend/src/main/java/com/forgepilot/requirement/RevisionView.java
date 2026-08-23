package com.forgepilot.requirement;

import java.time.Instant;
import java.util.List;

/** API 对外呈现的单次修订，附带属于该修订的验收条件。 */
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
