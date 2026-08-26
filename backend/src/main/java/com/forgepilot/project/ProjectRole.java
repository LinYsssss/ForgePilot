package com.forgepilot.project;

/**
 * 项目内的角色。刻意**不做**成 Spring Security authority：项目角色是项目内的
 * 概念，全局安全体系只区分「已认证」与「匿名」。
 */
public enum ProjectRole {
    LEADER,
    DEVELOPER,
    REVIEWER
}
