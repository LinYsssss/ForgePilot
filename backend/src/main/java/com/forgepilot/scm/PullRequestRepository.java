package com.forgepilot.scm;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface PullRequestRepository extends JpaRepository<PullRequest, Long> {

    Optional<PullRequest> findByProjectIdAndId(long projectId, long id);

    /**
     * A human correction is a read-modify-write too: the audit row's
     * {@code from_requirement_id} is the value read here, so two concurrent
     * corrections must not both read the same one. Without the lock they would
     * record two changes out of the same starting point while only one of them
     * describes what actually happened.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PullRequest> findWithLockByProjectIdAndId(long projectId, long id);

    /**
     * The ordering rule is a compare-and-write, so the row is locked before its
     * {@code source_updated_at} is read. Without this two concurrent deliveries
     * both read the old value and the later commit wins by accident rather than by
     * being newer. A first insert races on {@code (repository_id, external_number)}
     * instead, and that conflict is left to the unique constraint.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PullRequest> findWithLockByProjectIdAndRepositoryIdAndExternalNumber(
            long projectId, long repositoryId, int externalNumber);

    boolean existsByProjectIdAndRepositoryId(long projectId, long repositoryId);
}
