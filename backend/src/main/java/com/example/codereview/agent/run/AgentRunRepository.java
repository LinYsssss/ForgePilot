package com.example.codereview.agent.run;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {
    Optional<AgentRun> findByTriggerKey(String triggerKey);

    /**
     * 所有触发键带指定前缀的 run——用来找出同一个 PR(provider + installation + PR 号)
     * 在不同 head SHA 下的兄弟 run,好在新 head 到来时把仍在跑的旧 run 顶掉。
     */
    List<AgentRun> findByTriggerKeyStartingWith(String triggerKeyPrefix);

    List<AgentRun> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** 给 API 用的分页版本;无界的那个留给内部调用方。 */
    org.springframework.data.domain.Page<AgentRun> findByProjectIdOrderByCreatedAtDesc(
            Long projectId, org.springframework.data.domain.Pageable pageable);
}
