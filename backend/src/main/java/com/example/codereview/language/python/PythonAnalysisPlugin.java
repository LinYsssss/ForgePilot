package com.example.codereview.language.python;

import com.example.codereview.language.ChangeAnalysis;
import com.example.codereview.language.ChangeSet;
import com.example.codereview.language.Language;
import com.example.codereview.language.LanguagePlugin;
import com.example.codereview.language.RepositoryProfile;
import com.example.codereview.language.ToolCommand;
import java.util.List;
import java.util.Set;

public final class PythonAnalysisPlugin implements LanguagePlugin {

    private final String imageDigest;
    private final String sourceVersion;

    public PythonAnalysisPlugin(String imageDigest, String sourceVersion) {
        this.imageDigest = imageDigest;
        this.sourceVersion = sourceVersion;
        command("python.ruff");
    }

    @Override
    public String id() {
        return "python";
    }

    @Override
    public Set<Language> supportedLanguages() {
        return Set.of(Language.PYTHON);
    }

    @Override
    public List<ToolCommand> commands() {
        return List.of(command("python.ruff"), command("python.bandit"), command("python.pytest"));
    }

    @Override
    public ChangeAnalysis analyze(RepositoryProfile profile, ChangeSet changeSet) {
        if (profile == null || changeSet == null) {
            throw new IllegalArgumentException("profile and changeSet are required");
        }
        List<PythonProjectDetector.ProjectMarker> markers = PythonProjectDetector.detect(profile);
        List<String> environment = markers.isEmpty()
                ? List.of("UNSUPPORTED_LAYOUT:no Python project marker or source file")
                : List.of();
        return new ChangeAnalysis(id(), commands(), List.of(), environment);
    }

    private ToolCommand command(String id) {
        return new ToolCommand(id, List.of(), imageDigest, sourceVersion);
    }
}
