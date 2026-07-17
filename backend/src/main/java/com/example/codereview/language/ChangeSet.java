package com.example.codereview.language;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record ChangeSet(String baseSha, String headSha, List<FileChange> files) {

    public ChangeSet {
        requireText(baseSha, "baseSha");
        requireText(headSha, "headSha");
        files = files == null ? List.of() : List.copyOf(files);
    }

    public Set<Language> languages() {
        Set<Language> result = new LinkedHashSet<>();
        files.forEach(file -> Language.fromPath(file.path()).ifPresent(result::add));
        return Set.copyOf(result);
    }

    public enum ChangeType {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED
    }

    public record FileChange(String path, ChangeType type) {
        public FileChange {
            requireText(path, "path");
            path = path.replace('\\', '/').replaceAll("^\\./+", "");
            if (type == null) {
                throw new IllegalArgumentException("type is required");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
