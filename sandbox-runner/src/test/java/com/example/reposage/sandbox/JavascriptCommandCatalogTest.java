package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JavascriptCommandCatalogTest {

    @Test
    void registersDirectToolBinariesAndNeverPackageScripts() {
        var commands = SandboxCommandCatalog.commands();

        assertThat(commands.get("javascript.eslint").command().get(0)).isEqualTo("/usr/local/bin/eslint");
        assertThat(commands.get("javascript.semgrep").command().get(0)).isEqualTo("/usr/local/bin/semgrep");
        assertThat(commands.get("javascript.typescript").command().get(0)).isEqualTo("/usr/local/bin/tsc");
        assertThat(commands.get("javascript.jest").command().get(0)).isEqualTo("/usr/local/bin/jest");
        assertThat(commands.get("javascript.vitest").command().get(0)).isEqualTo("/usr/local/bin/vitest");
        assertThat(commands.values()).allSatisfy(spec ->
                assertThat(spec.command()).noneMatch(value -> value.equals("npm") || value.equals("pnpm")
                        || value.equals("yarn") || value.equals("npx")));
    }
}
