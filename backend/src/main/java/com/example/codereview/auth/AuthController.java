package com.example.codereview.auth;

import com.example.codereview.auth.AuthDtos.AuthResponse;
import com.example.codereview.auth.AuthDtos.LoginRequest;
import com.example.codereview.auth.AuthDtos.MeResponse;
import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieService authCookieService;
    private final CurrentUserProvider currentUserProvider;

    public AuthController(AuthService authService, AuthCookieService authCookieService, CurrentUserProvider currentUserProvider) {
        this.authService = authService;
        this.authCookieService = authCookieService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthResponse authResponse = authService.login(request);
        authCookieService.writeLoginCookie(authResponse.token(), response);
        return ApiResponse.ok(authResponse);
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(authService.me(currentUserProvider.getRequired().userId()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        authService.logout(currentUserProvider.getRequired().userId());
        authCookieService.clearCookie(response);
        return ApiResponse.ok();
    }
}
