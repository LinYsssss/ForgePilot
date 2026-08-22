package com.forgepilot.review;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingEventRepository extends JpaRepository<FindingEvent, Long> {

    /**
     * Deterministically ordered: ARCHITECTURE.md 3.6 decides whether a rejection
     * may be inherited by looking at the most recent human judgement, so "most
     * recent" has to mean the same thing on every run.
     */
    List<FindingEvent> findByProjectIdAndFindingIdOrderByCreatedAtAscIdAsc(long projectId, long findingId);
}
