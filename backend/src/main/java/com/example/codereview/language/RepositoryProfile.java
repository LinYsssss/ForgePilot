package com.example.codereview.language;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record RepositoryProfile(Set<Language> languages, Set<String> buildSystems, List<String> paths) {

    public RepositoryProfile {
        languages = languages == null ? Set.of() : Set.copyOf(languages);
        buildSystems = buildSystems == null ? Set.of() : Set.copyOf(buildSystems);
        paths = paths == null ? List.of() : paths.stream().map(RepositoryProfile::normalize).distinct().toList();
    }

    public static RepositoryProfile fromPaths(Collection<String> sourcePaths) {
        List<String> paths = sourcePaths == null
                ? List.of()
                : sourcePaths.stream().filter(path -> path != null && !path.isBlank())
                        .map(RepositoryProfile::normalize).distinct().toList();
        Set<Language> languages = new LinkedHashSet<>();
        Set<String> buildSystems = new LinkedHashSet<>();
        for (String path : paths) {
            Language.fromPath(path).ifPresent(languages::add);
            String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (name.equals("pom.xml")) {
                buildSystems.add("maven");
            } else if (name.endsWith(".gradle") || name.endsWith(".gradle.kts")
                    || name.equals("gradle-wrapper.properties")) {
                buildSystems.add("gradle");
            } else if (name.equals("pyproject.toml")) {
                buildSystems.add("pyproject");
            } else if (name.equals("package.json")) {
                buildSystems.add("node");
            }
        }
        return new RepositoryProfile(languages, buildSystems, paths);
    }

    private static String normalize(String path) {
        return path.replace('\\', '/').replaceAll("^\\./+", "");
    }
}
