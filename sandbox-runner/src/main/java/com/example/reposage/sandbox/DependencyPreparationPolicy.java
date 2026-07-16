package com.example.reposage.sandbox;

import java.util.Set;

/** Security policy for the separate, network-enabled dependency preparation job type. */
public final class DependencyPreparationPolicy {

    private static final int MAX_TIMEOUT_SECONDS = 900;
    private static final long MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final Set<String> COMMANDS = Set.of(
            "deps.maven.prepare",
            "deps.gradle.prepare",
            "deps.python.prepare",
            "deps.npm.prepare",
            "deps.pnpm.prepare",
            "deps.yarn.prepare");
    private static final Set<String> SAFE_ENVIRONMENT = Set.of(
            "CI",
            "MAVEN_OPTS",
            "GRADLE_USER_HOME",
            "PIP_DISABLE_PIP_VERSION_CHECK",
            "NPM_CONFIG_CACHE");

    public DependencyPreparationRequest validatePreparation(DependencyPreparationRequest request) {
        if (request == null || !COMMANDS.contains(request.commandId())) {
            throw new SecurityException("dependency preparation commandId is not on the allowlist");
        }
        if (!request.allowNetwork()) {
            throw new SecurityException("dependency preparation must explicitly declare its network requirement");
        }
        if (request.timeoutSeconds() <= 0 || request.timeoutSeconds() > MAX_TIMEOUT_SECONDS) {
            throw new SecurityException("dependency preparation timeout exceeds policy");
        }
        if (request.maxCacheBytes() <= 0 || request.maxCacheBytes() > MAX_CACHE_BYTES) {
            throw new SecurityException("dependency cache size exceeds policy");
        }
        for (String name : request.environment().keySet()) {
            if (!SAFE_ENVIRONMENT.contains(name)) {
                throw new SecurityException("secret or unapproved environment variable is forbidden: " + name);
            }
        }
        return request;
    }
}
