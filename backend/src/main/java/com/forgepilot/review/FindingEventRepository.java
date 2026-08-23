package com.forgepilot.review;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingEventRepository extends JpaRepository<FindingEvent, Long> {

    /**
     * 顺序是确定的：ARCHITECTURE.md 3.6 通过查看**最近一次**人工判断来决定
     * 一个驳回能否被继承，因此“最近”在每一次运行中都必须指同一件事。
     */
    List<FindingEvent> findByProjectIdAndFindingIdOrderByCreatedAtAscIdAsc(long projectId, long findingId);
}
