package com.example.reposage.sandbox;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves only local temporary archive references already provisioned for the trusted Runner. */
public final class WorkspaceArchiveResolver {

    private final Path archiveRoot;

    public WorkspaceArchiveResolver(Path archiveRoot) throws IOException {
        Files.createDirectories(archiveRoot);
        this.archiveRoot = archiveRoot.toRealPath();
    }

    public Path resolve(String reference) throws IOException {
        if (reference == null || reference.isBlank()) {
            throw new SecurityException("workspace archive reference is missing");
        }
        Path candidate;
        if (reference.startsWith("file:")) {
            candidate = Path.of(URI.create(reference));
        } else {
            if (reference.contains(":") || reference.contains("\\")) {
                throw new SecurityException("workspace archive reference scheme is not allowed");
            }
            candidate = archiveRoot.resolve(reference);
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(archiveRoot)) {
            throw new SecurityException("workspace archive reference escapes archive root");
        }
        Path real = normalized.toRealPath();
        if (!real.startsWith(archiveRoot) || !Files.isRegularFile(real)) {
            throw new SecurityException("workspace archive reference escapes archive root");
        }
        return real;
    }
}
