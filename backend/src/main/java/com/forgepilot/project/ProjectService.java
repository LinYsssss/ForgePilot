package com.forgepilot.project;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.forgepilot.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectAccessService access;

    ProjectService(ProjectRepository projects, ProjectMemberRepository members, ProjectAccessService access) {
        this.projects = projects;
        this.members = members;
        this.access = access;
    }

    /**
     * 创建项目与把创建者设为该项目 LEADER 是**同一个**事务：
     * 在这次提交之前创建者在项目中没有任何角色，
     * 因此“至少有一个 LEADER”这条不变式将无从起步。
     */
    @Transactional
    public ProjectResponse create(String name, long creatorId) {
        Project project = projects.save(new Project(name, creatorId));
        members.save(new ProjectMember(project.getId(), creatorId, Set.of(ProjectRole.LEADER)));
        return ProjectResponse.of(project, Set.of(ProjectRole.LEADER));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForUser(long userId) {
        Map<Long, Set<ProjectRole>> roles = members.findByUserIdOrderByProjectIdAsc(userId).stream()
                .collect(Collectors.toMap(ProjectMember::getProjectId, ProjectMember::getRoles));
        return projects.findAllById(roles.keySet()).stream()
                .sorted(Comparator.comparing(Project::getName).thenComparing(Project::getId))
                .map(project -> ProjectResponse.of(project, roles.get(project.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(long projectId, long userId) {
        Set<ProjectRole> roles = access.requireMember(projectId, userId).getRoles();
        return projects.findById(projectId)
                .map(project -> ProjectResponse.of(project, roles))
                .orElseThrow(ApiException::notFound);
    }

    public void lockForUpdate(long projectId) {
        projects.findByIdForUpdate(projectId).orElseThrow(ApiException::notFound);
    }

    /**
     * 归档一个项目：它从工作区列表里收起来，数据与审计一行不动。
     *
     * <p>只有 LEADER 可以归档，与其余项目级写操作同一口径。取行锁是为了让
     * 两个并发归档串行化——后到的那个会读到已提交的 ARCHIVED 并以 409 收场，
     * 而不是双双“成功”。
     *
     * <p>刻意**不**级联、不改任何子表：归档是产品面的可见性，不是删除。
     * 归档后该项目的需求、知识、仓库与审查仍可写入——{@code project.status}
     * 目前不 gate 任何写操作，把它变成只读闸门是另一件事。
     */
    @Transactional
    public void archive(long projectId, long actorId) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(ApiException::notFound);
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw ApiException.conflict("This project is already archived.");
        }
        project.archive();
    }

    /** 取消归档，与 {@link #archive} 完全对称：同样只有 LEADER，同样在行锁下判定。 */
    @Transactional
    public void unarchive(long projectId, long actorId) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        Project project = projects.findByIdForUpdate(projectId).orElseThrow(ApiException::notFound);
        if (project.getStatus() != ProjectStatus.ARCHIVED) {
            throw ApiException.conflict("This project is not archived.");
        }
        project.unarchive();
    }
}
