package com.forgepilot.project;

import java.util.Set;

import com.forgepilot.common.ApiException;
import org.springframework.stereotype.Service;

/**
 * 项目内一切操作的**唯一**授权入口。{@code requirement} 以及此后的所有功能模块
 * 都经由这里，而不是自己去读 {@link ProjectMemberRepository}。
 *
 * <p>非成员得到的答案与「项目不存在」完全相同，因此无法跨项目探测 id。
 * <em>是</em>成员但角色不足的调用方得到 403：他本就知道该项目存在，
 * 这个答案不会再泄露任何信息。
 */
@Service
public class ProjectAccessService {

    private final ProjectMemberRepository members;

    ProjectAccessService(ProjectMemberRepository members) {
        this.members = members;
    }

    public ProjectMember requireMember(long projectId, long userId) {
        return members.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(ApiException::notFound);
    }

    public ProjectMember requireRole(long projectId, long userId, ProjectRole... allowed) {
        ProjectMember member = requireMember(projectId, userId);
        if (!Set.of(allowed).contains(member.getRole())) {
            throw ApiException.forbidden();
        }
        return member;
    }
}
