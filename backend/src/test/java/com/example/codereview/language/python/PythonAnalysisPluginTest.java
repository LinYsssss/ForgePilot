package com.example.codereview.language.python;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.language.ChangeAnalysis;
import com.example.codereview.language.ChangeSet;
import com.example.codereview.language.RepositoryProfile;
import com.example.codereview.language.ToolCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

class PythonAnalysisPluginTest {

    private static final String IMAGE = "registry.example/review-python@sha256:" + "b".repeat(64);

    @Test
    void detectsPyprojectRequirementsAndSourceOnlyProjects() {
        assertThat(PythonProjectDetector.detect(RepositoryProfile.fromPaths(List.of("pyproject.toml", "app.py"))))
                .containsExactly(PythonProjectDetector.ProjectMarker.PYPROJECT, PythonProjectDetector.ProjectMarker.PYTHON_SOURCE);
        assertThat(PythonProjectDetector.detect(RepositoryProfile.fromPaths(List.of("requirements.txt", "src/app.py"))))
                .containsExactly(PythonProjectDetector.ProjectMarker.REQUIREMENTS, PythonProjectDetector.ProjectMarker.PYTHON_SOURCE);
        assertThat(PythonProjectDetector.detect(RepositoryProfile.fromPaths(List.of("tools/check.py"))))
                .containsExactly(PythonProjectDetector.ProjectMarker.PYTHON_SOURCE);
    }

    @Test
    void declaresRuffBanditAndPytestAsPinnedFixedCommands() {
        PythonAnalysisPlugin plugin = new PythonAnalysisPlugin(IMAGE, "python-tools-2026.07");
        ChangeAnalysis analysis = plugin.analyze(
                RepositoryProfile.fromPaths(List.of("pyproject.toml", "app.py")),
                new ChangeSet("base", "head", List.of(
                        new ChangeSet.FileChange("app.py", ChangeSet.ChangeType.MODIFIED))));

        assertThat(analysis.commands()).extracting(ToolCommand::commandId)
                .containsExactly("python.ruff", "python.bandit", "python.pytest");
        assertThat(analysis.commands()).allSatisfy(command -> {
            assertThat(command.imageDigest()).isEqualTo(IMAGE);
            assertThat(command.arguments()).isEmpty();
        });
    }
}
