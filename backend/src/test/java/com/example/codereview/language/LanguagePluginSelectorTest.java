package com.example.codereview.language;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.finding.FindingCandidate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LanguagePluginSelectorTest {

    private final LanguagePluginSelector selector = new LanguagePluginSelector(List.of(
            new TestPlugin("java", Set.of(Language.JAVA)),
            new TestPlugin("python", Set.of(Language.PYTHON)),
            new TestPlugin("javascript", Set.of(Language.JAVASCRIPT_TYPESCRIPT))));

    @Test
    void selectsOnlyJavaPluginForPureJavaChanges() {
        RepositoryProfile profile = RepositoryProfile.fromPaths(List.of(
                "pom.xml", "src/main/java/com/acme/OrderService.java", "README.md"));
        ChangeSet changes = new ChangeSet("base", "head", List.of(
                new ChangeSet.FileChange("src/main/java/com/acme/OrderService.java", ChangeSet.ChangeType.MODIFIED)));

        assertThat(selector.select(profile, changes))
                .extracting(LanguagePlugin::id)
                .containsExactly("java");
    }

    @Test
    void selectsEveryMatchingPluginForMixedLanguageChanges() {
        RepositoryProfile profile = RepositoryProfile.fromPaths(List.of(
                "pom.xml", "service.py", "web/package.json", "web/src/app.ts"));
        ChangeSet changes = new ChangeSet("base", "head", List.of(
                new ChangeSet.FileChange("src/main/java/com/acme/OrderService.java", ChangeSet.ChangeType.ADDED),
                new ChangeSet.FileChange("service.py", ChangeSet.ChangeType.MODIFIED),
                new ChangeSet.FileChange("web/src/app.ts", ChangeSet.ChangeType.MODIFIED)));

        assertThat(selector.select(profile, changes))
                .extracting(LanguagePlugin::id)
                .containsExactly("java", "javascript", "python");
    }

    @Test
    void usesRepositoryProfileForBuildOnlyChangesAndIgnoresUnsupportedFiles() {
        RepositoryProfile javaProfile = RepositoryProfile.fromPaths(List.of("settings.gradle", "src/App.java"));
        ChangeSet buildChange = new ChangeSet("base", "head", List.of(
                new ChangeSet.FileChange("settings.gradle", ChangeSet.ChangeType.MODIFIED)));
        ChangeSet docsChange = new ChangeSet("base", "head", List.of(
                new ChangeSet.FileChange("docs/design.md", ChangeSet.ChangeType.MODIFIED)));

        assertThat(selector.select(javaProfile, buildChange))
                .extracting(LanguagePlugin::id)
                .containsExactly("java");
        assertThat(selector.select(RepositoryProfile.fromPaths(List.of("README.md")), docsChange)).isEmpty();
    }

    private record TestPlugin(String id, Set<Language> supportedLanguages) implements LanguagePlugin {

        @Override
        public List<ToolCommand> commands() {
            return List.of();
        }

        @Override
        public ChangeAnalysis analyze(RepositoryProfile profile, ChangeSet changeSet) {
            return new ChangeAnalysis(id, List.of(), List.<FindingCandidate>of(), List.of());
        }
    }
}
