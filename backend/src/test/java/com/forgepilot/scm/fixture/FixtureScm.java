package com.forgepilot.scm.fixture;

import com.forgepilot.review.fixture.FixtureReviewRepository;

public class FixtureScm {
    private final FixtureReviewRepository repository;

    public FixtureScm(FixtureReviewRepository repository) {
        this.repository = repository;
    }

    public FixtureReviewRepository repository() {
        return repository;
    }
}
