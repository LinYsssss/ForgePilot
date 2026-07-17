package com.example.codereview.patch;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class UnifiedDiffValidator {

    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[a-zA-Z]:[/\\\\].*");
    private final int maxFiles;
    private final int maxChangedLines;

    public UnifiedDiffValidator(
            @Value("${app.patch.max-files:20}") int maxFiles,
            @Value("${app.patch.max-changed-lines:1000}") int maxChangedLines) {
        if (maxFiles <= 0 || maxChangedLines <= 0) {
            throw new IllegalArgumentException("patch limits must be positive");
        }
        this.maxFiles = maxFiles;
        this.maxChangedLines = maxChangedLines;
    }

    public PatchValidation validate(String patch) {
        if (patch == null || patch.isBlank()) {
            return PatchValidation.rejected("patch is empty");
        }
        if (patch.indexOf('\0') >= 0 || patch.contains("GIT binary patch") || patch.contains("Binary files ")) {
            return PatchValidation.rejected("binary patch is not allowed");
        }
        Set<String> files = new LinkedHashSet<>();
        int changedLines = 0;
        String currentPath = null;
        for (String line : patch.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                String[] fields = line.substring(11).trim().split("\\s+");
                if (fields.length != 2 || !fields[0].startsWith("a/") || !fields[1].startsWith("b/")) {
                    return PatchValidation.rejected("malformed unified diff header");
                }
                String left = fields[0].substring(2);
                String right = fields[1].substring(2);
                if (!left.equals(right)) {
                    return PatchValidation.rejected("rename patches are not allowed");
                }
                String invalid = invalidPathReason(right);
                if (invalid != null) {
                    return PatchValidation.rejected(invalid);
                }
                files.add(right.replace('\\', '/'));
                currentPath = right.replace('\\', '/');
                if (files.size() > maxFiles) {
                    return PatchValidation.rejected("patch exceeds file count limit " + maxFiles);
                }
            } else if (line.startsWith("--- ") || line.startsWith("+++ ")) {
                if (currentPath == null) {
                    return PatchValidation.rejected("file marker appears before diff header");
                }
                String marker = line.substring(4).trim();
                if (!marker.equals("/dev/null")) {
                    String prefix = line.startsWith("--- ") ? "a/" : "b/";
                    if (!marker.equals(prefix + currentPath)) {
                        return PatchValidation.rejected("file marker does not match diff header");
                    }
                }
            } else if ((line.startsWith("+") && !line.startsWith("+++"))
                    || (line.startsWith("-") && !line.startsWith("---"))) {
                changedLines++;
                if (changedLines > maxChangedLines) {
                    return PatchValidation.rejected("patch exceeds changed lines limit " + maxChangedLines);
                }
            }
        }
        if (files.isEmpty()) {
            return PatchValidation.rejected("patch contains no file diff");
        }
        return new PatchValidation(true, null, files.stream().toList(), changedLines);
    }

    private String invalidPathReason(String rawPath) {
        String path = rawPath.replace('\\', '/');
        if (path.startsWith("/") || WINDOWS_ABSOLUTE.matcher(path).matches()) {
            return "absolute path is not allowed";
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                return "path traversal is not allowed";
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.equals("codeowners") || lower.endsWith("/codeowners")
                || lower.startsWith(".github/workflows/") || lower.startsWith(".gitlab/ci/")
                || lower.equals(".gitlab-ci.yml") || lower.startsWith(".git/")
                || lower.contains("/db/migration/") || lower.startsWith("db/migration/")) {
            return "protected file cannot be modified: " + path;
        }
        return null;
    }
}
