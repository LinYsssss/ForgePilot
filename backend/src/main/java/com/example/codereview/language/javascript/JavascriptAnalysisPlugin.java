package com.example.codereview.language.javascript;

import com.example.codereview.language.ChangeAnalysis;
import com.example.codereview.language.ChangeSet;
import com.example.codereview.language.Language;
import com.example.codereview.language.LanguagePlugin;
import com.example.codereview.language.RepositoryProfile;
import com.example.codereview.language.ToolCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JavascriptAnalysisPlugin implements LanguagePlugin {

    private final String imageDigest;
    private final String sourceVersion;
    private final JavascriptProjectDetector detector = new JavascriptProjectDetector();

    public JavascriptAnalysisPlugin(String imageDigest, String sourceVersion) {
        this.imageDigest = imageDigest;
        this.sourceVersion = sourceVersion;
        command("javascript.eslint");
    }

    @Override
    public String id() {
        return "javascript";
    }

    @Override
    public Set<Language> supportedLanguages() {
        return Set.of(Language.JAVASCRIPT_TYPESCRIPT);
    }

    @Override
    public List<ToolCommand> commands() {
        return List.of(
                command("javascript.eslint"),
                command("javascript.semgrep"),
                command("javascript.typescript"),
                command("javascript.jest"),
                command("javascript.vitest"));
    }

    @Override
    public ChangeAnalysis analyze(RepositoryProfile profile, ChangeSet changeSet) {
        return analyze(profile, changeSet, Map.of());
    }

    public ChangeAnalysis analyze(
            RepositoryProfile profile,
            ChangeSet changeSet,
            Map<String, String> trustedFileContents) {
        if (profile == null || changeSet == null) {
            throw new IllegalArgumentException("profile and changeSet are required");
        }
        JavascriptProjectProfile project = detector.detect(profile, trustedFileContents);
        List<ToolCommand> selected = new ArrayList<>();
        selected.add(command("javascript.eslint"));
        selected.add(command("javascript.semgrep"));
        if (project.typescript()) {
            selected.add(command("javascript.typescript"));
        }
        if (project.testFrameworks().contains(JavascriptProjectProfile.TestFramework.JEST)) {
            selected.add(command("javascript.jest"));
        }
        if (project.testFrameworks().contains(JavascriptProjectProfile.TestFramework.VITEST)) {
            selected.add(command("javascript.vitest"));
        }
        List<String> environment = project.packageManager() == JavascriptProjectProfile.PackageManager.UNKNOWN
                ? List.of("ENVIRONMENT_INCOMPLETE:no supported package-manager lockfile")
                : List.of();
        return new ChangeAnalysis(id(), selected, List.of(), environment);
    }

    private ToolCommand command(String id) {
        return new ToolCommand(id, List.of(), imageDigest, sourceVersion);
    }
}
