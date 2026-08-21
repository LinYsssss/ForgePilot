package com.forgepilot.scm.fixture;

import com.forgepilot.review.fixture.FixtureReviewRepository;

public class FixtureScmController {
    private final FixtureReviewRepository repository;

    public FixtureScmController(FixtureReviewRepository repository) {
        this.repository = repository;
    }

    public FixtureReviewRepository repository() {
        return repository;
    }
}
