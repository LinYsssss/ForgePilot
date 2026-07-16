package com.example.reposage.sandbox;

import java.util.List;

public record ContainerLaunchSpec(String containerName, List<String> createArguments, long timeoutMs) {

    public ContainerLaunchSpec {
        if (containerName == null || containerName.isBlank()) {
            throw new IllegalArgumentException("containerName is required");
        }
        createArguments = List.copyOf(createArguments);
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
    }
}
