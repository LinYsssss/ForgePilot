package com.example.codereview.scm;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.scm.ScmInstallationDtos.InstallationResponse;
import com.example.codereview.scm.ScmInstallationDtos.RegisterInstallationRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅管理员可用(SecurityConfig 以 hasRole(ADMIN) 拦截整个前缀)。 */
@RestController
@RequestMapping("/api/scm/installations")
public class ScmInstallationController {

    private final ScmInstallationAdminService service;
    private final CurrentUserProvider currentUserProvider;

    public ScmInstallationController(ScmInstallationAdminService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ApiResponse<InstallationResponse> register(@Valid @RequestBody RegisterInstallationRequest request) {
        return ApiResponse.ok(service.register(currentUserProvider.getRequired().userId(), request));
    }

    @GetMapping
    public ApiResponse<List<InstallationResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @DeleteMapping("/{installationId}")
    public ApiResponse<Void> deactivate(@PathVariable Long installationId) {
        service.deactivate(installationId);
        return ApiResponse.ok();
    }
}
