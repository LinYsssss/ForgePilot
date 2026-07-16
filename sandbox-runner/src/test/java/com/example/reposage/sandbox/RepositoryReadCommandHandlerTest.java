package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryReadCommandHandlerTest {

    @TempDir
    Path workspace;

    @Test
    void readsFilesAndTruncatesBoundedOutput() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), "0123456789");
        RepositoryReadCommandHandler handler = new RepositoryReadCommandHandler(6, 20, 10);

        RepositoryCommandResult result = handler.file(workspace, "src/App.java");

        assertThat(result.status()).isEqualTo(SandboxJobStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo("012345");
        assertThat(result.truncated()).isTrue();
        assertThatThrownBy(() -> handler.file(workspace, "../secret"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void searchesTextWithBoundedResultsAndSkipsBinaryFiles() throws Exception {
        Files.writeString(workspace.resolve("one.txt"), "needle first\nneedle second\n");
        Files.write(workspace.resolve("binary.bin"), new byte[] {0, 1, 2, 3});
        RepositoryReadCommandHandler handler = new RepositoryReadCommandHandler(1024, 1, 100);

        RepositoryCommandResult result = handler.search(workspace, "needle");

        assertThat(result.output()).contains("one.txt:1:needle first");
        assertThat(result.output()).doesNotContain("binary.bin");
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void returnsPreparedUnifiedDiffWithBoundedOutput() throws Exception {
        Files.createDirectories(workspace.resolve(".reposage"));
        Files.writeString(workspace.resolve(".reposage/review.diff"), "diff --git a/a b/a\n+changed\n");
        RepositoryReadCommandHandler handler = new RepositoryReadCommandHandler(1024, 10, 18);

        RepositoryCommandResult result = handler.diff(workspace);

        assertThat(result.output()).startsWith("diff --git");
        assertThat(result.truncated()).isTrue();
    }
}
