package com.forgepilot.ai.fixture;

import com.forgepilot.knowledge.fixture.FixtureDocumentStore;

/** Counter-probe: reaches into another feature's repository, disguised by its name. */
public class FixtureAiConsumer {

    private final FixtureDocumentStore store;

    public FixtureAiConsumer(FixtureDocumentStore store) {
        this.store = store;
    }

    public FixtureDocumentStore store() {
        return store;
    }
}
