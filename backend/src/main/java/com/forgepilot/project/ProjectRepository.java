package com.forgepilot.project;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * 在项目行上把并发的 LEADER 转移串行化。没有它，两次转移可能都读到
     * 同一个 LEADER、都执行降级、再都执行升级；此时部分唯一索引会让
     * 后提交的那一方悄无声息地胜出。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :projectId")
    Optional<Project> findByIdForUpdate(long projectId);
}
