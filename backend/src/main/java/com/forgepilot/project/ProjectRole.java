package com.forgepilot.project;

/**
 * Role inside one project. Deliberately not a Spring Security authority: project
 * roles are a project-scoped concept, global security only tells authenticated
 * from anonymous (design.md 5).
 */
public enum ProjectRole {
    LEADER,
    DEVELOPER,
    REVIEWER
}
