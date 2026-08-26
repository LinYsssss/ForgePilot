package com.forgepilot.auth;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API.md 中不由安全过滤器链直接处理的那部分认证端点。 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse register(@Valid @RequestBody RegisterRequest request) {
        return this.auth.register(request.username(), request.displayName(), request.password());
    }

    /** 同时也是 SPA 的冷启动入口：该响应会带上 XSRF-TOKEN cookie（API.md）。 */
    @GetMapping("/me")
    AccountResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return this.auth.current(principal.getUserId());
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request, HttpSession session) {
        int sessionVersion = this.auth.changePassword(
                principal.getUserId(), request.currentPassword(), request.newPassword());
        // 本会话采纳新版本号因而继续可用；其余会话仍持有旧版本号，
        // 会被 SessionVersionFilter 终止。
        session.setAttribute(SessionVersionFilter.SESSION_VERSION, sessionVersion);
    }

    @PatchMapping("/profile")
    AccountResponse changeProfile(@AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ChangeProfileRequest request) {
        return this.auth.changeDisplayName(principal.getUserId(), request.displayName());
    }

    record RegisterRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 120) String displayName,
            @NotNull @Size(min = 8) String password) {
    }

    record ChangeProfileRequest(@NotBlank @Size(max = 120) String displayName) {
    }

    record ChangePasswordRequest(
            @NotNull String currentPassword,
            @NotNull @Size(min = 8) String newPassword) {
    }
}
