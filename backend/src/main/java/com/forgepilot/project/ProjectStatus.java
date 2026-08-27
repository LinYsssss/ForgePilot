package com.forgepilot.project;

/** 项目的生命周期。归档与取消归档在 {@code ProjectService} 里双向转换（ARCHITECTURE.md 2.1）。 */
public enum ProjectStatus {
    ACTIVE,
    ARCHIVED
}
