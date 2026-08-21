package com.forgepilot.scm;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * Every project scoped read carries {@code projectId}. The one exception is the
 * webhook lookup: a delivery does not carry a project, and the stable identity is
 * globally unique precisely so that it identifies exactly one row (design.md 3.6).
 */
interface ScmRepositoryRepository extends JpaRepository<ScmRepository, Long> {

    Optional<ScmRepository> findByProjectIdAndId(long projectId, long id);

    /**
     * Locks the row before the stable identity is compared, so two concurrent
     * updates cannot both read "no pull requests yet" and both re-identify the
     * repository (design.md 3.7).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ScmRepository> findWithLockByProjectIdAndId(long projectId, long id);

    Optional<ScmRepository> findByProviderAndInstanceIdentityAndExternalId(
            ScmProvider provider, String instanceIdentity, String externalId);
}
