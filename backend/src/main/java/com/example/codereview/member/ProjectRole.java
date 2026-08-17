package com.example.codereview.member;

/**
 * 项目内角色(D1 决议收缩为 3 角色)。负责人 LEADER 与 {@code ProjectEntity.ownerId} 保持
 * 一对一:owner 恒为唯一 LEADER,成员管理只能授予 DEVELOPER / REVIEWER,负责人变更走移交。
 */
public enum ProjectRole {
    LEADER,
    DEVELOPER,
    REVIEWER;

    public static ProjectRole fromName(String name) {
        for (ProjectRole role : values()) {
            if (role.name().equals(name)) {
                return role;
            }
        }
        return null;
    }
}
