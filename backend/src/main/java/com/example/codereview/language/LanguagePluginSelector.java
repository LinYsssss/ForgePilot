package com.example.codereview.language;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class LanguagePluginSelector {

    private final List<LanguagePlugin> plugins;

    public LanguagePluginSelector(List<LanguagePlugin> plugins) {
        this.plugins = plugins == null ? List.of() : plugins.stream()
                .sorted(Comparator.comparing(LanguagePlugin::id))
                .toList();
    }

    public List<LanguagePlugin> select(RepositoryProfile profile, ChangeSet changeSet) {
        if (profile == null || changeSet == null) {
            throw new IllegalArgumentException("profile and changeSet are required");
        }
        Set<Language> changedLanguages = changeSet.languages();
        return plugins.stream()
                .filter(plugin -> plugin.supportedLanguages() != null
                        && plugin.supportedLanguages().stream().anyMatch(changedLanguages::contains))
                .toList();
    }
}
