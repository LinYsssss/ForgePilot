package com.example.codereview.common.security;

public record CurrentUser(Long userId, String username, String role) {
}
