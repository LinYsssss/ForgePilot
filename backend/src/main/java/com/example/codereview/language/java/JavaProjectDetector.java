package com.example.codereview.language.java;

import com.example.codereview.language.RepositoryProfile;
import java.util.ArrayList;
import java.util.List;

public final class JavaProjectDetector {

    private JavaProjectDetector() {
    }

    public static List<BuildSystem> detect(RepositoryProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        List<BuildSystem> result = new ArrayList<>();
        if (profile.buildSystems().contains("maven")) {
            result.add(BuildSystem.MAVEN);
        }
        if (profile.buildSystems().contains("gradle")) {
            result.add(BuildSystem.GRADLE);
        }
        return List.copyOf(result);
    }

    public enum BuildSystem {
        MAVEN,
        GRADLE
    }
}
