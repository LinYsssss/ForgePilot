package com.forgepilot.scm;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * 每次项目内读取都带 {@code projectId}。唯一的例外是 webhook 查找：
 * 一次投递不携带项目信息，而稳定身份之所以全局唯一，正是为了让它能够
 * 恰好定位到唯一一行（design.md 3.6）。
 */
interface ScmRepositoryRepository extends JpaRepository<ScmRepository, Long> {

    Optional<ScmRepository> findByProjectIdAndId(long projectId, long id);

    List<ScmRepository> findByProjectId(long projectId);

    /**
     * 在比较稳定身份之前先锁行，使两个并发更新不会都读到「还没有 PR」
     * 从而都去重新标定这个仓库的身份（design.md 3.7）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ScmRepository> findWithLockByProjectIdAndId(long projectId, long id);

    Optional<ScmRepository> findByProviderAndInstanceIdentityAndExternalId(
            ScmProvider provider, String instanceIdentity, String externalId);
}
