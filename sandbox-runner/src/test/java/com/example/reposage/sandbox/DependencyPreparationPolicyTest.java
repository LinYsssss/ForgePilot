package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DependencyPreparationPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void lockfilesProduceStableKeysAcrossTraversalOrderAndChangeOnContentChange() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("repo"));
        Files.writeString(workspace.resolve("package-lock.json"), "{\"lockfileVersion\":3}");
        Files.writeString(workspace.resolve("pom.xml"), "<project><version>1</version></project>");
        DependencyCacheManager manager = manager();

        DependencyCacheKey first = manager.computeCacheKey(workspace).orElseThrow();
        DependencyCacheKey second = manager.computeCacheKey(workspace).orElseThrow();
        assertThat(first).isEqualTo(second);
        assertThat(first.value()).startsWith("v1-");
        assertThat(first.ecosystems()).containsExactly("maven", "npm");

        Files.writeString(workspace.resolve("package-lock.json"), "{\"lockfileVersion\":3,\"x\":1}");
        assertThat(manager.computeCacheKey(workspace).orElseThrow()).isNotEqualTo(first);
    }

    @Test
    void supportsMavenGradlePythonAndNodeLockfiles() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("repo"));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("gradle.lockfile", "gradle");
        files.put("poetry.lock", "python");
        files.put("pnpm-lock.yaml", "node");
        files.forEach((name, content) -> {
            try {
                Files.writeString(workspace.resolve(name), content);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        assertThat(manager().computeCacheKey(workspace).orElseThrow().ecosystems())
                .containsExactly("gradle", "node", "python");
    }

    @ParameterizedTest
    @CsvSource({
            "pom.xml,maven",
            "gradle.lockfile,gradle",
            "requirements.txt,python",
            "package-lock.json,npm",
            "pnpm-lock.yaml,node",
            "yarn.lock,node"
    })
    void recognizesEachPlannedLockfileFamily(String fileName, String ecosystem) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("repo-" + fileName.replace('.', '-')));
        Files.writeString(workspace.resolve(fileName), "locked");

        assertThat(manager().computeCacheKey(workspace).orElseThrow().ecosystems())
                .containsExactly(ecosystem);
    }

    @Test
    void onlySeparateAllowlistedPreparationMayUseNetworkAndSecretsAreRejected() {
        DependencyPreparationPolicy policy = new DependencyPreparationPolicy();
        DependencyPreparationRequest valid = new DependencyPreparationRequest(
                "deps.npm.prepare", 300, 512L * 1024 * 1024, true, Map.of("CI", "true"));
        assertThat(policy.validatePreparation(valid).allowNetwork()).isTrue();

        assertThatThrownBy(() -> policy.validatePreparation(new DependencyPreparationRequest(
                "sandbox.health", 300, 1, true, Map.of())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("allowlist");
        assertThatThrownBy(() -> policy.validatePreparation(new DependencyPreparationRequest(
                "deps.npm.prepare", 300, 1, true, Map.of("NPM_TOKEN", "secret"))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("secret");
    }

    @Test
    void untrustedMountIsReadOnlyAndKeepsNetworkDisabled() throws Exception {
        Path cache = Files.createDirectory(tempDir.resolve("cache"));
        DependencyCacheMount mount = manager().readOnlyMount(cache, "/cache");

        assertThat(mount.dockerArguments()).containsExactly(
                "--mount", "type=bind,src=" + cache.toRealPath() + ",dst=/cache,readonly");
        assertThat(mount.networkMode()).isEqualTo("none");
        assertThat(mount.readOnly()).isTrue();
    }

    @Test
    void missingCacheReturnsEnvironmentIncompleteNotFinding() throws Exception {
        DependencyCacheManager manager = manager();
        DependencyCacheKey key = new DependencyCacheKey("v1-maven-deadbeef", List.of("maven"));

        DependencyResolution resolution = manager.resolve(key);

        assertThat(resolution.available()).isFalse();
        assertThat(resolution.status()).isEqualTo(SandboxJobStatus.ENVIRONMENT_INCOMPLETE);
        assertThat(resolution.createsCodeFinding()).isFalse();
    }

    private DependencyCacheManager manager() {
        return new DependencyCacheManager(tempDir.resolve("cache"), new DependencyPreparationPolicy());
    }
}
