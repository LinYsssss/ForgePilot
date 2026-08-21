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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The auth endpoints of api-contract.md 1 that are not handled by the filter chain. */
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
        return this.auth.register(request.username(), request.password());
    }

    /** Also the SPA's cold start: the response carries the XSRF-TOKEN cookie (api-contract.md 0). */
    @GetMapping("/me")
    AccountResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return new AccountResponse(principal.getUserId(), principal.getUsername());
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request, HttpSession session) {
        int sessionVersion = this.auth.changePassword(
                principal.getUserId(), request.currentPassword(), request.newPassword());
        // This session adopts the new version and stays usable; every other session
        // keeps the old one and is ended by SessionVersionFilter.
        session.setAttribute(SessionVersionFilter.SESSION_VERSION, sessionVersion);
    }

    record RegisterRequest(
            @NotBlank @Size(max = 64) String username,
            @NotNull @Size(min = 8) String password) {
    }

    record ChangePasswordRequest(
            @NotNull String currentPassword,
            @NotNull @Size(min = 8) String newPassword) {
    }
}
