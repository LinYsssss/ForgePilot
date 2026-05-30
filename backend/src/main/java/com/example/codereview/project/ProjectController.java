package com.example.codereview.project;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.project.ProjectDtos.CreateProjectRequest;
import com.example.codereview.project.ProjectDtos.ProjectResponse;
import com.example.codereview.project.ProjectDtos.UpdateProjectRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final CurrentUserProvider currentUserProvider;

    public ProjectController(ProjectService projectService, CurrentUserProvider currentUserProvider) {
        this.projectService = projectService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ApiResponse<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(projectService.create(currentUserProvider.getRequired().userId(), request));
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list() {
        return ApiResponse.ok(projectService.list(currentUserProvider.getRequired().userId()));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectResponse> detail(@PathVariable Long projectId) {
        return ApiResponse.ok(projectService.detail(projectId, currentUserProvider.getRequired().userId()));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ProjectResponse> update(@PathVariable Long projectId, @Valid @RequestBody UpdateProjectRequest request) {
        return ApiResponse.ok(projectService.update(projectId, currentUserProvider.getRequired().userId(), request));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId) {
        projectService.delete(projectId, currentUserProvider.getRequired().userId());
        return ApiResponse.ok();
    }
}
