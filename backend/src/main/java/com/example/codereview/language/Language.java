package com.example.codereview.language;

import java.util.Locale;
import java.util.Optional;

public enum Language {
    JAVA,
    PYTHON,
    JAVASCRIPT_TYPESCRIPT;

    public static Optional<Language> fromPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (normalized.endsWith(".java")
                || name.equals("pom.xml")
                || name.endsWith(".gradle")
                || name.endsWith(".gradle.kts")
                || name.equals("gradle-wrapper.properties")) {
            return Optional.of(JAVA);
        }
        if (normalized.endsWith(".py")
                || name.equals("pyproject.toml")
                || name.equals("requirements.txt")
                || name.equals("requirements.lock")
                || name.equals("poetry.lock")
                || name.equals("pipfile.lock")) {
            return Optional.of(PYTHON);
        }
        if (normalized.endsWith(".js")
                || normalized.endsWith(".jsx")
                || normalized.endsWith(".mjs")
                || normalized.endsWith(".cjs")
                || normalized.endsWith(".ts")
                || normalized.endsWith(".tsx")
                || name.equals("package.json")
                || name.equals("package-lock.json")
                || name.equals("pnpm-lock.yaml")
                || name.equals("yarn.lock")) {
            return Optional.of(JAVASCRIPT_TYPESCRIPT);
        }
        return Optional.empty();
    }
}
