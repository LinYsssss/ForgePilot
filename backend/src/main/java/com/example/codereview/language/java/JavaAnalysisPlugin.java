package com.example.codereview.language.java;

import com.example.codereview.language.ChangeAnalysis;
import com.example.codereview.language.ChangeSet;
import com.example.codereview.language.Language;
import com.example.codereview.language.LanguagePlugin;
import com.example.codereview.language.RepositoryProfile;
import com.example.codereview.language.ToolCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class JavaAnalysisPlugin implements LanguagePlugin {

    private final String imageDigest;
    private final String sourceVersion;

    public JavaAnalysisPlugin(String imageDigest, String sourceVersion) {
        this.imageDigest = imageDigest;
        this.sourceVersion = sourceVersion;
        command("java.pmd");
    }

    @Override
    public String id() {
        return "java";
    }

    @Override
    public Set<Language> supportedLanguages() {
        return Set.of(Language.JAVA);
    }

    @Override
    public List<ToolCommand> commands() {
        return List.of(
                command("java.maven.compile"),
                command("java.maven.test"),
                command("java.gradle.compile"),
                command("java.gradle.test"),
                command("java.pmd"),
                command("java.spotbugs"),
                command("java.checkstyle"));
    }

    @Override
    public ChangeAnalysis analyze(RepositoryProfile profile, ChangeSet changeSet) {
        if (profile == null || changeSet == null) {
            throw new IllegalArgumentException("profile and changeSet are required");
        }
        List<ToolCommand> selected = new ArrayList<>();
        List<JavaProjectDetector.BuildSystem> systems = JavaProjectDetector.detect(profile);
        if (systems.contains(JavaProjectDetector.BuildSystem.MAVEN)) {
            selected.add(command("java.maven.compile"));
            selected.add(command("java.maven.test"));
        }
        if (systems.contains(JavaProjectDetector.BuildSystem.GRADLE)) {
            selected.add(command("java.gradle.compile"));
            selected.add(command("java.gradle.test"));
        }
        selected.add(command("java.pmd"));
        selected.add(command("java.spotbugs"));
        selected.add(command("java.checkstyle"));
        List<String> environment = systems.isEmpty()
                ? List.of("UNSUPPORTED_LAYOUT:no Maven or Gradle build descriptor")
                : List.of();
        return new ChangeAnalysis(id(), selected, List.of(), environment);
    }

    private ToolCommand command(String id) {
        return new ToolCommand(id, List.of(), imageDigest, sourceVersion);
    }
}
