package com.example.reposage.sandbox;

import java.io.IOException;
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
        String fileName;
        try {
            // Every syntactic rule (scheme, traversal, whitelist, length) lives in the codec that
            // mirrors the backend's encoder, so producer and validator can no longer drift apart.
            // The former hand-rolled check here rejected the backend's real output unconditionally.
            fileName = WorkspaceArchiveReference.parse(reference);
        } catch (IllegalArgumentException ex) {
            throw new SecurityException(ex.getMessage());
        }
        Path normalized = archiveRoot.resolve(fileName).toAbsolutePath().normalize();
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
