package com.forgepilot.requirement;

/** An acceptance criterion as the API shows it. */
public record AcceptanceCriterionView(long id, String acKey, int sortOrder, String text) {

    static AcceptanceCriterionView of(AcceptanceCriterion criterion) {
        return new AcceptanceCriterionView(criterion.getId(), criterion.getAcKey(),
                criterion.getSortOrder(), criterion.getText());
    }
}
