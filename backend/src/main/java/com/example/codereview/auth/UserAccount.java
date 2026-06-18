package com.example.codereview.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(length = 64)
    private String nickname;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private int sessionVersion;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
    }

    public UserAccount(String username, String passwordHash, String nickname, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.role = role;
        this.status = "ENABLED";
        this.sessionVersion = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public String getRole() {
        return role;
    }

    public String getStatus() {
        return status;
    }

    public int getSessionVersion() {
        return sessionVersion;
    }

    public boolean isEnabled() {
        return "ENABLED".equalsIgnoreCase(status);
    }

    public void bumpSessionVersion() {
        this.sessionVersion++;
        this.updatedAt = Instant.now();
    }
}
