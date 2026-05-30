package com.example.codereview.auth;

import com.example.codereview.auth.AuthDtos.AuthResponse;
import com.example.codereview.auth.AuthDtos.LoginRequest;
import com.example.codereview.auth.AuthDtos.MeResponse;
import com.example.codereview.auth.AuthDtos.RegisterRequest;
import com.example.codereview.common.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (users.existsByUsername(request.username())) {
            throw new BusinessException(409, "用户名已存在");
        }
        UserAccount user = new UserAccount(
                request.username(),
                passwordEncoder.encode(request.password()),
                request.nickname(),
                "DEVELOPER"
        );
        users.save(user);
        return toAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        UserAccount user = users.findByUsername(request.username())
                .orElseThrow(() -> new BusinessException(400, "用户名或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(400, "用户名或密码错误");
        }
        return toAuthResponse(user);
    }

    public MeResponse me(Long userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        return new MeResponse(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    private AuthResponse toAuthResponse(UserAccount user) {
        return new AuthResponse(tokenService.issue(user), user.getId(), user.getUsername(), user.getRole());
    }
}
