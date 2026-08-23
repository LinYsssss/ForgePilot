package com.forgepilot.scm;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface PullRequestRepository extends JpaRepository<PullRequest, Long> {

    Optional<PullRequest> findByProjectIdAndId(long projectId, long id);

    /**
     * 人工纠正同样是一次「读-改-写」：审计行的 {@code from_requirement_id}
     * 就是这里读到的值，因此两次并发纠正绝不能读到同一个起点。没有这把锁，
     * 它们会从同一个起点记下两次变更，而其中只有一次真正描述了实际发生的事。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PullRequest> findWithLockByProjectIdAndId(long projectId, long id);

    /**
     * 定序规则是一次「比较并写入」，因此在读它的 {@code source_updated_at}
     * 之前先锁住该行。没有这一步，两次并发投递会都读到旧值，
     * 于是「后提交的赢」变成了偶然，而不是因为它确实更新。
     * 首次插入的竞争则发生在 {@code (repository_id, external_number)} 上，
     * 那次冲突交给唯一约束处理。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PullRequest> findWithLockByProjectIdAndRepositoryIdAndExternalNumber(
            long projectId, long repositoryId, int externalNumber);

    boolean existsByProjectIdAndRepositoryId(long projectId, long repositoryId);
}
