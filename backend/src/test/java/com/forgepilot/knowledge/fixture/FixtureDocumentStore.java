package com.forgepilot.knowledge.fixture;

import org.springframework.data.repository.Repository;

/**
 * Counter-probe: a Spring Data repository whose name does not end in
 * {@code Repository}. It is a class rather than an interface on purpose —
 * Spring Data only picks up interfaces, so this never becomes a bean, but
 * ArchUnit still sees it as assignable to {@link Repository}.
 */
public class FixtureDocumentStore implements Repository<Object, Long> {
}
