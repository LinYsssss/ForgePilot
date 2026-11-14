package com.forgepilot.project;

import java.security.Principal;
import java.util.List;

import com.forgepilot.auth.UserDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
class ProjectController {

    private final ProjectService projects;
    private final UserDirectory users;

    ProjectController(ProjectService projects, UserDirectory users) {
        this.projects = projects;
        this.users = users;
    }

    @GetMapping
    List<ProjectResponse> list(Principal principal) {
        return projects.listForUser(users.idOf(principal));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProjectResponse create(@Valid @RequestBody CreateProjectRequest request, Principal principal) {
        return projects.create(request.name(), users.idOf(principal));
    }

    @GetMapping("/{projectId}")
    ProjectResponse get(@PathVariable long projectId, Principal principal) {
        return projects.get(projectId, users.idOf(principal));
    }

    /** 归档：项目从工作区列表收起，数据与审计全部留在原地。仅 LEADER。 */
    @PostMapping("/{projectId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable long projectId, Principal principal) {
        projects.archive(projectId, users.idOf(principal));
    }

    /** 取消归档：项目回到工作区列表。仅 LEADER。 */
    @PostMapping("/{projectId}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unarchive(@PathVariable long projectId, Principal principal) {
        projects.unarchive(projectId, users.idOf(principal));
    }

    record CreateProjectRequest(@NotBlank @Size(max = 120) String name) {
    }
}
