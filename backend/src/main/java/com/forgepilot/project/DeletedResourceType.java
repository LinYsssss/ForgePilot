package com.forgepilot.project;

/**
 * 会被记入 {@link ProjectDeletionRecord} 的三类资源。
 *
 * <p>这个 enum 与 {@code ck_project_deletion_record_resource_type} 是同一份词表的
 * 两个副本。走到那条 CHECK 的越界值中止的是整条插入，所以映射必须发生在写库之前，
 * 而 {@code ProjectDeletionLogTest} 会走完整个 enum 去证明两份副本没有分叉——
 * {@code finding.category} 当初需要的就是同一条纪律。
 */
public enum DeletedResourceType {
    KNOWLEDGE_DOCUMENT,
    PROJECT_MEMBER,
    REQUIREMENT
}
