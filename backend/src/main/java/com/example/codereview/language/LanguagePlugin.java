package com.example.codereview.language;

import java.util.List;
import java.util.Set;

public interface LanguagePlugin {
    String id();

    Set<Language> supportedLanguages();

    List<ToolCommand> commands();

    ChangeAnalysis analyze(RepositoryProfile profile, ChangeSet changeSet);
}
