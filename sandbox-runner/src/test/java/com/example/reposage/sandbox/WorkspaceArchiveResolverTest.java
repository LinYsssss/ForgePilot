package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceArchiveResolverTest {

    @TempDir
    Path tempDir;

    private WorkspaceArchiveResolver resolver(Path root) throws IOException {
        return new WorkspaceArchiveResolver(root);
    }

    /** Regression pin: the backend's real encoded reference must resolve to the provisioned file. */
    @Test
    void resolvesBackendEncodedReferenceToProvisionedFile() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("archives"));
        String reference = WorkspaceArchiveReference.forAgentRun(42L, "abcdef1234567");
        Files.writeString(root.resolve(reference), "tar-bytes");

        Path resolved = resolver(root).resolve(reference);

        assertThat(resolved).isEqualTo(root.toRealPath().resolve("agent-run-42-abcdef1234567.tar"));
        assertThat(Files.isRegularFile(resolved)).isTrue();
    }

    @Test
    void resolvesPatchReferenceToProvisionedFile() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("archives"));
        String reference = WorkspaceArchiveReference.forPatch(9L, "a".repeat(64));
        Files.writeString(root.resolve(reference), "tar-bytes");

        assertThat(resolver(root).resolve(reference)).isEqualTo(root.toRealPath().resolve(reference));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "workspace://agent-run-1-abcdef1.tar",
            "file:///etc/passwd",
            "../outside.tar",
            "a\\b.tar",
            "/etc/passwd",
            "sub/dir.tar",
            "-leading.tar",
            ".hidden.tar",
            "a..b.tar",
            " "
    })
    void rejectsUnsafeReferencesWithSecurityException(String reference) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("archives"));
        WorkspaceArchiveResolver resolver = resolver(root);

        assertThatThrownBy(() -> resolver.resolve(reference)).isInstanceOf(SecurityException.class);
    }

    /** A syntactically valid but unprovisioned reference is an environment problem, not a breach. */
    @Test
    void missingArchiveSurfacesAsIoException() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("archives"));
        WorkspaceArchiveResolver resolver = resolver(root);

        assertThatThrownBy(() -> resolver.resolve("agent-run-1-abcdef1.tar"))
                .isInstanceOf(IOException.class);
    }

    /** The real-path fence stays: a symlink inside the root must not lead outside of it. */
    @Test
    void symlinkEscapingArchiveRootIsRejected() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("archives"));
        Path outside = Files.writeString(tempDir.resolve("outside.tar"), "outside");
        Files.createSymbolicLink(root.resolve("agent-run-1-abcdef1.tar"), outside);
        WorkspaceArchiveResolver resolver = resolver(root);

        assertThatThrownBy(() -> resolver.resolve("agent-run-1-abcdef1.tar"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("escapes archive root");
    }
}
