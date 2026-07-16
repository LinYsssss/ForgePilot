package com.example.reposage.sandbox;

import java.util.Map;

public record DependencyPreparationRequest(
        String commandId,
        int timeoutSeconds,
        long maxCacheBytes,
        boolean allowNetwork,
        Map<String, String> environment) {

    public DependencyPreparationRequest {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
