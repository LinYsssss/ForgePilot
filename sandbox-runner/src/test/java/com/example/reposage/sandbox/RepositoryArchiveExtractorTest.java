package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryArchiveExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsBoundedArchiveIntoWorkspace() throws Exception {
        Path archive = zip(Map.of(
                "src/App.java", "class App {}",
                "README.md", "demo"));
        Path workspace = tempDir.resolve("workspace");

        RepositoryArchiveSummary summary = extractor().extract(archive, workspace);

        assertThat(summary.fileCount()).isEqualTo(2);
        assertThat(Files.readString(workspace.resolve("src/App.java"))).isEqualTo("class App {}");
    }

    @Test
    void rejectsTraversalAbsolutePathsAndOversizedArchive() throws Exception {
        assertThatThrownBy(() -> extractor().extract(zip(Map.of("../escape.txt", "bad")), tempDir.resolve("a")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("path");
        assertThatThrownBy(() -> extractor().extract(zip(Map.of("/absolute.txt", "bad")), tempDir.resolve("b")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("path");

        RepositoryArchiveExtractor tiny = new RepositoryArchiveExtractor(
                new RepositoryArchiveLimits(1, 4, 8), new RepositoryUrlPolicy());
        assertThatThrownBy(() -> tiny.extract(zip(Map.of("large.txt", "12345")), tempDir.resolve("c")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("size");
    }

    @Test
    void rejectsPrivateOrNonHttpsSubmoduleUrls() throws Exception {
        String gitmodules = "[submodule \"bad\"]\n  path = vendor/bad\n  url = http://127.0.0.1/repo.git\n";
        assertThatThrownBy(() -> extractor().extract(
                zip(Map.of(".gitmodules", gitmodules)), tempDir.resolve("workspace")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("submodule");
    }

    private RepositoryArchiveExtractor extractor() {
        return new RepositoryArchiveExtractor(
                new RepositoryArchiveLimits(100, 1024 * 1024, 2 * 1024 * 1024),
                new RepositoryUrlPolicy());
    }

    private Path zip(Map<String, String> entries) throws IOException {
        Path archive = Files.createTempFile(tempDir, "repo-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }
}
