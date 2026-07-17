package com.example.codereview.language.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.language.ChangeAnalysis;
import com.example.codereview.language.ChangeSet;
import com.example.codereview.language.RepositoryProfile;
import com.example.codereview.language.ToolCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaAnalysisPluginTest {

    private static final String IMAGE = "registry.example/review-java@sha256:" + "a".repeat(64);

    @Test
    void detectsMavenAndGradleProjectsFromRepositoryProfile() {
        assertThat(JavaProjectDetector.detect(RepositoryProfile.fromPaths(List.of("pom.xml", "src/App.java"))))
                .containsExactly(JavaProjectDetector.BuildSystem.MAVEN);
        assertThat(JavaProjectDetector.detect(RepositoryProfile.fromPaths(List.of(
                "settings.gradle.kts", "build.gradle.kts", "src/main/java/App.java"))))
                .containsExactly(JavaProjectDetector.BuildSystem.GRADLE);
    }

    @Test
    void declaresOnlyFixedPinnedCommandsForTheDetectedBuildSystem() {
        JavaAnalysisPlugin plugin = new JavaAnalysisPlugin(IMAGE, "java-tools-2026.07");
        RepositoryProfile profile = RepositoryProfile.fromPaths(List.of("pom.xml", "src/App.java"));
        ChangeSet changeSet = new ChangeSet("base", "head", List.of(
                new ChangeSet.FileChange("src/App.java", ChangeSet.ChangeType.MODIFIED)));

        ChangeAnalysis analysis = plugin.analyze(profile, changeSet);

        assertThat(analysis.commands()).extracting(ToolCommand::commandId).containsExactly(
                "java.maven.compile",
                "java.maven.test",
                "java.pmd",
                "java.spotbugs",
                "java.checkstyle");
        assertThat(analysis.commands()).allSatisfy(command -> {
            assertThat(command.imageDigest()).isEqualTo(IMAGE);
            assertThat(command.sourceVersion()).isEqualTo("java-tools-2026.07");
        });
    }
}
