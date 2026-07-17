package com.example.codereview.language.javascript;

import com.example.codereview.language.RepositoryProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class JavascriptProjectDetector {

    private final ObjectMapper mapper = new ObjectMapper();

    public JavascriptProjectProfile detect(RepositoryProfile profile, Map<String, String> fileContents) {
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        JavascriptProjectProfile.PackageManager manager = packageManager(profile);
        boolean typescript = profile.paths().stream().anyMatch(path -> path.endsWith(".ts")
                || path.endsWith(".tsx") || path.endsWith("tsconfig.json"));
        Set<JavascriptProjectProfile.TestFramework> frameworks = new LinkedHashSet<>();
        String packageJson = fileContents == null ? null : fileContents.get("package.json");
        if (packageJson != null && !packageJson.isBlank()) {
            try {
                JsonNode root = mapper.readTree(packageJson);
                Set<String> dependencies = new LinkedHashSet<>();
                root.path("dependencies").fieldNames().forEachRemaining(dependencies::add);
                root.path("devDependencies").fieldNames().forEachRemaining(dependencies::add);
                if (dependencies.contains("jest")) {
                    frameworks.add(JavascriptProjectProfile.TestFramework.JEST);
                }
                if (dependencies.contains("vitest")) {
                    frameworks.add(JavascriptProjectProfile.TestFramework.VITEST);
                }
                typescript = typescript || dependencies.contains("typescript");
            } catch (Exception ex) {
                throw new IllegalArgumentException("invalid package.json", ex);
            }
        }
        return new JavascriptProjectProfile(manager, typescript, frameworks);
    }

    private static JavascriptProjectProfile.PackageManager packageManager(RepositoryProfile profile) {
        if (profile.paths().stream().anyMatch(path -> path.endsWith("pnpm-lock.yaml"))) {
            return JavascriptProjectProfile.PackageManager.PNPM;
        }
        if (profile.paths().stream().anyMatch(path -> path.endsWith("yarn.lock"))) {
            return JavascriptProjectProfile.PackageManager.YARN;
        }
        if (profile.paths().stream().anyMatch(path -> path.endsWith("package-lock.json")
                || path.endsWith("npm-shrinkwrap.json"))) {
            return JavascriptProjectProfile.PackageManager.NPM;
        }
        return JavascriptProjectProfile.PackageManager.UNKNOWN;
    }
}
