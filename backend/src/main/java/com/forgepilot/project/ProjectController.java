package com.forgepilot.project;

import java.security.Principal;
import java.util.List;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
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
        return projects.listForUser(userIdOf(principal));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProjectResponse create(@Valid @RequestBody CreateProjectRequest request, Principal principal) {
        return projects.create(request.name(), userIdOf(principal));
    }

    @GetMapping("/{projectId}")
    ProjectResponse get(@PathVariable long projectId, Principal principal) {
        return projects.get(projectId, userIdOf(principal));
    }

    /**
     * The login identity is resolved to a user id here, in the controller, through
     * the read-only account facade. Business services never see Spring Security
     * and this feature never depends on how a session is established
     * (ARCHITECTURE.md 1.3 as narrowed by D013.6).
     */
    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    record CreateProjectRequest(@NotBlank @Size(max = 120) String name) {
    }
}
