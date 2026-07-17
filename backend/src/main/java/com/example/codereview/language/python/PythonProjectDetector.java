package com.example.codereview.language.python;

import com.example.codereview.language.RepositoryProfile;
import java.util.ArrayList;
import java.util.List;

public final class PythonProjectDetector {

    private PythonProjectDetector() {
    }

    public static List<ProjectMarker> detect(RepositoryProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        List<ProjectMarker> markers = new ArrayList<>();
        if (profile.paths().stream().anyMatch(path -> path.endsWith("pyproject.toml"))) {
            markers.add(ProjectMarker.PYPROJECT);
        }
        if (profile.paths().stream().anyMatch(path -> path.endsWith("requirements.txt"))) {
            markers.add(ProjectMarker.REQUIREMENTS);
        }
        if (profile.paths().stream().anyMatch(path -> path.endsWith(".py"))) {
            markers.add(ProjectMarker.PYTHON_SOURCE);
        }
        return List.copyOf(markers);
    }

    public enum ProjectMarker {
        PYPROJECT,
        REQUIREMENTS,
        PYTHON_SOURCE
    }
}
