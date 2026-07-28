package com.example.codereview.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            // 与注册端的用户名规则保持一致:白名单字符,拒绝超长输入以免无谓地走完整条认证链
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "^[A-Za-z0-9_.-]+$", message = "用户名只能包含字母、数字、下划线、点和连字符")
            String username,
            @NotBlank @Size(max = 128) String password
    ) {
    }

    public record AuthResponse(
            Long userId,
            String username,
            String role
    ) {
    }

    /**
     * 内部结果:令牌只用于写入 HttpOnly Cookie,绝不进入响应体。
     * 令牌一旦出现在 JSON 里,前端就可能把它存进 localStorage,XSS 便能直接窃取会话。
     */
    public record LoginResult(String token, AuthResponse response) {
    }

    public record MeResponse(
            Long userId,
            String username,
            String nickname,
            String role
    ) {
    }
}
