package com.forgepilot.project;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 三类删除写留痕的**唯一**途径。{@code knowledge} 与 {@code requirement} 经由它
 * 写入，因此都接触不到 {@link ProjectDeletionRecordRepository}——方向上
 * 二者本来就依赖 {@code project}（ARCHITECTURE.md 1.3），无需反转。
 *
 * <p>写入必须与删除同事务：留痕若能独立于删除成功或失败，台账就会开始描述
 * 没有发生过的删除，或漏掉发生过的删除。{@code MANDATORY} 把这一点变成运行时
 * 事实而不是口头约定——没有外层事务时它直接拒绝。
 */
@Service
public class ProjectDeletionLog {

    private final ProjectDeletionRecordRepository records;

    ProjectDeletionLog(ProjectDeletionRecordRepository records) {
        this.records = records;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long projectId, DeletedResourceType resourceType, long resourceId,
            long actorUserId, String detail) {
        records.save(new ProjectDeletionRecord(projectId, resourceType, resourceId, actorUserId, detail));
    }

    @Transactional(readOnly = true)
    public List<ProjectDeletionRecord> forProject(long projectId) {
        return records.findByProjectIdOrderByIdAsc(projectId);
    }
}
