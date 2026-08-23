package com.forgepilot.requirement;

/** API 对外呈现的验收条件。 */
public record AcceptanceCriterionView(long id, String acKey, int sortOrder, String text) {

    static AcceptanceCriterionView of(AcceptanceCriterion criterion) {
        return new AcceptanceCriterionView(criterion.getId(), criterion.getAcKey(),
                criterion.getSortOrder(), criterion.getText());
    }
}
