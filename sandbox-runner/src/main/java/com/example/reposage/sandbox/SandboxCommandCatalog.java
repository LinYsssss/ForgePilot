package com.example.reposage.sandbox;

import java.util.List;
import java.util.Map;

/** Fixed executable catalog shared by the Runner configuration and contract tests. */
public final class SandboxCommandCatalog {

    private SandboxCommandCatalog() {
    }

    public static Map<String, ContainerPolicy.CommandSpec> commands() {
        return Map.ofEntries(
                entry("sandbox.health", "/bin/true"),
                entry("patch.apply.check", "/usr/bin/git", "-C", "/workspace", "apply", "--check",
                        "/workspace/.reposage/candidate.patch"),
                entry("patch.apply", "/usr/bin/git", "-C", "/workspace", "apply", "--whitespace=error-all",
                        "/workspace/.reposage/candidate.patch"),
                entry("java.maven.compile", "/usr/bin/mvn", "-B", "-DskipTests", "compile"),
                entry("java.maven.test", "/usr/bin/mvn", "-B", "test"),
                entry("java.gradle.compile", "/opt/gradle/bin/gradle", "--no-daemon", "classes"),
                entry("java.gradle.test", "/opt/gradle/bin/gradle", "--no-daemon", "test"),
                entry("java.pmd", "/opt/pmd/bin/pmd", "check", "-d", "/workspace", "-f", "sarif",
                        "-R", "/opt/reposage/rulesets/pmd.xml"),
                entry("java.spotbugs", "/opt/spotbugs/bin/spotbugs", "-textui", "-xml:withMessages",
                        "-output", "/tmp/spotbugs.xml", "/workspace"),
                entry("java.checkstyle", "/usr/bin/java", "-jar", "/opt/checkstyle/checkstyle.jar",
                        "-c", "/opt/reposage/rulesets/checkstyle.xml", "/workspace"),
                entry("python.ruff", "/usr/local/bin/ruff", "check", "--output-format", "json", "/workspace"),
                entry("python.bandit", "/usr/local/bin/bandit", "-r", "/workspace", "-f", "json"),
                entry("python.pytest", "/usr/local/bin/pytest", "--junitxml=/tmp/pytest.xml", "/workspace"),
                entry("javascript.eslint", "/usr/local/bin/eslint", "--format", "json", "/workspace"),
                entry("javascript.semgrep", "/usr/local/bin/semgrep", "--json", "--config",
                        "/opt/reposage/rulesets/semgrep.yml", "/workspace"),
                entry("javascript.typescript", "/usr/local/bin/tsc", "--noEmit", "--pretty", "false",
                        "-p", "/workspace/tsconfig.json"),
                entry("javascript.jest", "/usr/local/bin/jest", "--runInBand", "--ci", "--json",
                        "--outputFile=/tmp/jest.json"),
                entry("javascript.vitest", "/usr/local/bin/vitest", "run", "--reporter=json",
                        "--outputFile=/tmp/vitest.json"));
    }

    private static Map.Entry<String, ContainerPolicy.CommandSpec> entry(String id, String... command) {
        return Map.entry(id, new ContainerPolicy.CommandSpec(List.of(command), List.of()));
    }
}
