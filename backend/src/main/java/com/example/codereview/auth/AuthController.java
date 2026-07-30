package com.example.codereview.auth;

import com.example.codereview.auth.AuthDtos.AuthResponse;
import com.example.codereview.auth.AuthDtos.LoginRequest;
import com.example.codereview.auth.AuthDtos.LoginResult;
import com.example.codereview.auth.AuthDtos.MeResponse;
import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CsrfTokenRotator;
import com.example.codereview.common.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.web.csrf.CsrfToken;
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
    private final CsrfTokenRotator csrfTokenRotator;

    public AuthController(AuthService authService, AuthCookieService authCookieService,
                          CurrentUserProvider currentUserProvider, CsrfTokenRotator csrfTokenRotator) {
        this.authService = authService;
        this.authCookieService = authCookieService;
        this.currentUserProvider = currentUserProvider;
        this.csrfTokenRotator = csrfTokenRotator;
    }

    /**
     * SPA 的 CSRF 引导端点:未登录即可访问,调用后浏览器拿到 {@code XSRF-TOKEN} Cookie,
     * 后续写请求把它放进 {@code X-XSRF-TOKEN} 头回传。响应只给出名字,不回显令牌值——
     * 值本来就在 Cookie 里,再塞进响应体只会削弱 BREACH 防护。
     */
    @GetMapping("/csrf")
    public ApiResponse<CsrfBootstrap> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            return ApiResponse.ok(new CsrfBootstrap(false, null, null));
        }
        // 令牌是延迟加载的:必须取一次值,CookieCsrfTokenRepository 才会把 Cookie 写回响应。
        token.getToken();
        return ApiResponse.ok(new CsrfBootstrap(true, CsrfTokenRotator.COOKIE_NAME, token.getHeaderName()));
    }

    public record CsrfBootstrap(boolean enabled, String cookieName, String headerName) {
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest httpRequest, HttpServletResponse response) {
        // 令牌只写进 HttpOnly Cookie,不回到响应体——避免前端把它落到 localStorage 被 XSS 取走。
        LoginResult result = authService.login(request);
        authCookieService.writeLoginCookie(result.token(), response);
        // 登录前那枚 CSRF 令牌可能是攻击者诱导受害者取得的,登录成功即换新。
        csrfTokenRotator.rotate(httpRequest, response);
        return ApiResponse.ok(result.response());
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(authService.me(currentUserProvider.getRequired().userId()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse response) {
        authService.logout(currentUserProvider.getRequired().userId());
        authCookieService.clearCookie(response);
        csrfTokenRotator.clear(httpRequest, response);
        return ApiResponse.ok();
    }
}
