package com.example.codereview.auth;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }

    public record AuthResponse(
            String token,
            Long userId,
            String username,
            String role
    ) {
    }

    public record MeResponse(
            Long userId,
            String username,
            String nickname,
            String role
    ) {
    }
}
