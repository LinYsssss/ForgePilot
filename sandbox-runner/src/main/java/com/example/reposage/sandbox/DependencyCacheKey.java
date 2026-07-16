package com.example.reposage.sandbox;

import java.util.List;

public record DependencyCacheKey(String value, List<String> ecosystems) {

    public DependencyCacheKey {
        if (value == null || !value.matches("v1-[a-z0-9+.-]+-[a-f0-9]+")) {
            throw new IllegalArgumentException("invalid dependency cache key");
        }
        ecosystems = List.copyOf(ecosystems);
        if (ecosystems.isEmpty()) {
            throw new IllegalArgumentException("ecosystems are required");
        }
    }
}
