package com.example.codereview.project;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.project.ProjectDtos.CreateProjectRequest;
import com.example.codereview.project.ProjectDtos.ProjectResponse;
import com.example.codereview.project.ProjectDtos.UpdateProjectRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    @Transactional
    public ProjectResponse create(Long ownerId, CreateProjectRequest request) {
        ProjectEntity project = new ProjectEntity(ownerId, request.name(), request.description(), request.defaultBranch());
        projects.save(project);
        return ProjectResponse.from(project);
    }

    public List<ProjectResponse> list(Long ownerId) {
        return projects.findByOwnerIdOrderByCreatedAtDesc(ownerId)
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }

    public ProjectEntity getRequired(Long projectId, Long userId) {
        ProjectEntity project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目不存在"));
        if (!project.getOwnerId().equals(userId)) {
            throw new BusinessException(403, "无权访问该项目");
        }
        return project;
    }

    public ProjectResponse detail(Long projectId, Long userId) {
        return ProjectResponse.from(getRequired(projectId, userId));
    }

    @Transactional
    public ProjectResponse update(Long projectId, Long userId, UpdateProjectRequest request) {
        ProjectEntity project = getRequired(projectId, userId);
        project.update(request.name(), request.description(), request.defaultBranch());
        return ProjectResponse.from(project);
    }
}
