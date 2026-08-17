package com.example.codereview.project;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.member.ProjectMemberEntity;
import com.example.codereview.member.ProjectMemberRepository;
import com.example.codereview.member.ProjectRole;
import com.example.codereview.project.ProjectDtos.CreateProjectRequest;
import com.example.codereview.project.ProjectDtos.ProjectResponse;
import com.example.codereview.project.ProjectDtos.UpdateProjectRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final ProjectCleanupService cleanupService;
    private final ProjectMemberRepository members;
    private final ProjectAuthorization projectAuthorization;

    public ProjectService(ProjectRepository projects, ProjectCleanupService cleanupService,
                          ProjectMemberRepository members, ProjectAuthorization projectAuthorization) {
        this.projects = projects;
        this.cleanupService = cleanupService;
        this.members = members;
        this.projectAuthorization = projectAuthorization;
    }

    @Transactional
    public ProjectResponse create(Long ownerId, CreateProjectRequest request) {
        ProjectEntity project = new ProjectEntity(ownerId, request.name(), request.description(), request.defaultBranch());
        projects.save(project);
        // 创建者即负责人:成员表与 ownerId 同事务保持一致(存量数据由 V29 回填)。
        members.save(new ProjectMemberEntity(project.getId(), ownerId, ProjectRole.LEADER));
        return ProjectResponse.from(project, ProjectRole.LEADER.name());
    }

    /** 我参与的全部项目(负责/开发/审查),按创建时间倒序,附带我的角色。 */
    public List<ProjectResponse> list(Long userId) {
        Map<Long, String> roleByProject = new HashMap<>();
        for (ProjectMemberEntity member : members.findByUserId(userId)) {
            roleByProject.put(member.getProjectId(), member.getRole().name());
        }
        List<ProjectEntity> result = new ArrayList<>(
                roleByProject.isEmpty() ? List.of() : projects.findAllById(roleByProject.keySet()));
        // owner 兜底:成员行缺失的存量项目仍可见(owner 视为 LEADER,与授权层同一兜底)。
        for (ProjectEntity owned : projects.findByOwnerIdOrderByCreatedAtDesc(userId)) {
            roleByProject.putIfAbsent(owned.getId(), ProjectRole.LEADER.name());
            if (result.stream().noneMatch(p -> p.getId().equals(owned.getId()))) {
                result.add(owned);
            }
        }
        result.sort(Comparator.comparing(ProjectEntity::getCreatedAt).reversed());
        return result.stream()
                .map(project -> ProjectResponse.from(project,
                        userId.equals(project.getOwnerId())
                                ? ProjectRole.LEADER.name()
                                : roleByProject.get(project.getId())))
                .toList();
    }

    /**
     * 读语义的项目获取:任意成员可通过(P1a 起;此前为 owner-only)。写路径不要依赖本方法,
     * 应在 service 层显式调用 {@code ProjectAuthorization.requireWrite/requireRole}。
     */
    public ProjectEntity getRequired(Long projectId, Long userId) {
        projectAuthorization.requireRead(projectId, userId);
        return projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在"));
    }

    public ProjectResponse detail(Long projectId, Long userId) {
        ProjectEntity project = getRequired(projectId, userId);
        String myRole = projectAuthorization.roleOf(projectId, userId).map(Enum::name).orElse(null);
        return ProjectResponse.from(project, myRole);
    }

    @Transactional
    public ProjectResponse update(Long projectId, Long userId, UpdateProjectRequest request) {
        projectAuthorization.requireWrite(projectId, userId);
        ProjectEntity project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在"));
        project.update(request.name(), request.description(), request.defaultBranch());
        return ProjectResponse.from(project, ProjectRole.LEADER.name());
    }

    @Transactional
    public void delete(Long projectId, Long userId) {
        projectAuthorization.requireWrite(projectId, userId);
        ProjectEntity project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_NOT_FOUND, "项目不存在"));
        cleanupService.purgeProjectData(projectId);
        projects.delete(project);
    }
}
