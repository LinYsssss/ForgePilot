package com.acme.export;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ArchiveStorageService {

    private final Path archiveRoot;

    public ArchiveStorageService(
            @Value("${acme.export.archive-root:/var/acme/export-archives}") String archiveRoot) {
        this.archiveRoot = Path.of(archiveRoot);
        try {
            Files.createDirectories(this.archiveRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("unable to prepare export archive root " + archiveRoot, e);
        }
    }

    public Path store(ExportResult result) {
        String fileName = result.projectCode() + "-" + result.generatedAt().toEpochMilli() + ".csv";
        Path target = archiveRoot.resolve(fileName);
        try {
            return Files.write(target, result.payload());
        } catch (IOException e) {
            throw new UncheckedIOException("unable to archive export " + fileName, e);
        }
    }
}
