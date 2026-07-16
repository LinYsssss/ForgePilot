package com.example.reposage.sandbox;

import java.nio.file.Path;

public record DependencyResolution(
        boolean available,
        SandboxJobStatus status,
        Path cachePath,
        boolean createsCodeFinding,
        String message) {
}
