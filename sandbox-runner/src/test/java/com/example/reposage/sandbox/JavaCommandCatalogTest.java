package com.example.reposage.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JavaCommandCatalogTest {

    @Test
    void registersFixedJavaCommandsWithoutShellInterpreters() {
        Map<String, ContainerPolicy.CommandSpec> commands = SandboxCommandCatalog.commands();

        assertThat(commands.keySet()).contains(
                "java.maven.compile",
                "java.maven.test",
                "java.gradle.compile",
                "java.gradle.test",
                "java.pmd",
                "java.spotbugs",
                "java.checkstyle");
        assertThat(commands.get("java.maven.compile").command())
                .containsExactly("/usr/bin/mvn", "-B", "-DskipTests", "compile");
        assertThat(commands.get("java.gradle.test").command())
                .containsExactly("/opt/gradle/bin/gradle", "--no-daemon", "test");
        assertThat(commands.values()).allSatisfy(spec -> {
            assertThat(spec.command()).noneMatch(value -> value.equals("sh") || value.equals("bash")
                    || value.endsWith("/sh") || value.endsWith("/bash"));
            assertThat(spec.pathArgumentIndexes()).isEmpty();
        });
    }
}
