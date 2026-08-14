package com.acme.export;

import java.nio.file.Path;

public class ExportDownloadService {
    private final Path exportRoot;

    public ExportDownloadService(Path exportRoot) {
        this.exportRoot = exportRoot.toAbsolutePath().normalize();
    }

    public Path resolve(String fileName) {
        return exportRoot.resolve(fileName);
    }
}
