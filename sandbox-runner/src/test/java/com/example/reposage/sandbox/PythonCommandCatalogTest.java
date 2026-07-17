package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PythonCommandCatalogTest {

    @Test
    void registersFixedRuffBanditAndPytestCommands() {
        var commands = SandboxCommandCatalog.commands();

        assertThat(commands.get("python.ruff").command())
                .containsExactly("/usr/local/bin/ruff", "check", "--output-format", "json", "/workspace");
        assertThat(commands.get("python.bandit").command())
                .containsExactly("/usr/local/bin/bandit", "-r", "/workspace", "-f", "json");
        assertThat(commands.get("python.pytest").command())
                .containsExactly("/usr/local/bin/pytest", "--junitxml=/tmp/pytest.xml", "/workspace");
    }
}
