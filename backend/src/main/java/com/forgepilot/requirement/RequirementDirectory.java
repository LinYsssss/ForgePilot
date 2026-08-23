package com.forgepilot.requirement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 其他功能模块询问需求的**唯一**途径（D015.6）。它是查询 facade 而非仓库，
 * 因此 {@code scm} 既接触不到 {@code RequirementRepository}，
 * 也接触不到需求内部是怎么运作的。
 *
 * <p>{@code scm} 之所以需要它，是因为 {@code REQ-<n>} 必须<em>先于</em>
 * pull request 行的写入被解析：复合外键只能让整条插入失败，
 * 而捕获那个违例后继续执行是被禁止的（D013.11）；同时 D007 又要求
 * 一个错误的引用完全不能阻塞入库。
 */
@Service
@Transactional(readOnly = true)
public class RequirementDirectory {

    private final RequirementRepository requirements;

    RequirementDirectory(RequirementRepository requirements) {
        this.requirements = requirements;
    }

    /**
     * 这条需求是否存在<em>于本项目之内</em>。属于别的项目的 id 会得到 false，
     * 正是这一点把跨项目的 {@code REQ-<n>} 变成「没有关联需求」而不是一个错误
     * （D013.2）。
     */
    public boolean existsInProject(long projectId, long requirementId) {
        return requirements.findByProjectIdAndId(projectId, requirementId).isPresent();
    }
}
