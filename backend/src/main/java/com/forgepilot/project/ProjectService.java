package com.forgepilot.project;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
     * 创建项目与把创建者设为该项目 LEADER 是**同一个**事务（D013.5）：
     * 在这次提交之前创建者在项目中没有任何角色，因此 D004 的
     * “至少有一个 LEADER”不变式将无从起步。
     */
    @Transactional
    public ProjectResponse create(String name, long creatorId) {
        Project project = projects.save(new Project(name, creatorId));
        members.save(new ProjectMember(project.getId(), creatorId, ProjectRole.LEADER));
        return ProjectResponse.of(project, ProjectRole.LEADER);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listForUser(long userId) {
        Map<Long, ProjectRole> roles = members.findByUserIdOrderByProjectIdAsc(userId).stream()
                .collect(Collectors.toMap(ProjectMember::getProjectId, ProjectMember::getRole));
        return projects.findAllById(roles.keySet()).stream()
                .sorted(Comparator.comparing(Project::getName).thenComparing(Project::getId))
                .map(project -> ProjectResponse.of(project, roles.get(project.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(long projectId, long userId) {
        ProjectRole role = access.requireMember(projectId, userId).getRole();
        return projects.findById(projectId)
                .map(project -> ProjectResponse.of(project, role))
                .orElseThrow(ApiException::notFound);
    }
}
