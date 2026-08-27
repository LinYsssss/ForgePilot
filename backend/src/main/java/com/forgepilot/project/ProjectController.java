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

    /** 归档：项目从工作区列表收起，数据与审计全部留在原地。仅 LEADER。 */
    @PostMapping("/{projectId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable long projectId, Principal principal) {
        projects.archive(projectId, userIdOf(principal));
    }

    /** 取消归档：项目回到工作区列表。仅 LEADER。 */
    @PostMapping("/{projectId}/unarchive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unarchive(@PathVariable long projectId, Principal principal) {
        projects.unarchive(projectId, userIdOf(principal));
    }

    /**
     * 登录身份在这里——控制器层——通过只读账号 facade 解析为 user id。
     * 业务服务永远看不到 Spring Security，本功能模块也不依赖会话是如何建立的
     * （ARCHITECTURE.md 1.3）。
     */
    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    record CreateProjectRequest(@NotBlank @Size(max = 120) String name) {
    }
}
