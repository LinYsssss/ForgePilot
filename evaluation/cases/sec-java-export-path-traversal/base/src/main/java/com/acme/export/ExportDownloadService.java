package com.acme.export;

import java.nio.file.Path;

public class ExportDownloadService {
    private final Path exportRoot;

    public ExportDownloadService(Path exportRoot) {
        this.exportRoot = exportRoot.toAbsolutePath().normalize();
    }

    public Path resolve(String fileName) {
        Path candidate = exportRoot.resolve(fileName).normalize();
        if (!candidate.startsWith(exportRoot)) {
            throw new IllegalArgumentException("invalid export path");
        }
        return candidate;
    }
}
