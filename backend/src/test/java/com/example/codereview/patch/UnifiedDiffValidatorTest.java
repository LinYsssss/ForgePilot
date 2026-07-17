package com.example.codereview.patch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnifiedDiffValidatorTest {

    private final UnifiedDiffValidator validator = new UnifiedDiffValidator(2, 5);

    @Test
    void acceptsBoundedTextPatch() {
        PatchValidation result = validator.validate(diff("src/App.java", "+fixed"));
        assertThat(result.valid()).isTrue();
        assertThat(result.files()).containsExactly("src/App.java");
        assertThat(result.changedLines()).isEqualTo(2);
    }

    @Test
    void rejectsAbsoluteTraversalBinaryAndProtectedPaths() {
        assertRejected(diff("/etc/passwd", "+x"), "absolute path");
        assertRejected(diff("src/../secret.txt", "+x"), "path traversal");
        assertRejected("diff --git a/image.png b/image.png\nBinary files a/image.png and b/image.png differ\n", "binary patch");
        assertRejected(diff(".github/workflows/release.yml", "+curl evil"), "protected file");
        assertRejected(diff("CODEOWNERS", "+* attacker"), "protected file");
        assertRejected(diff("backend/src/main/resources/db/migration/V4__rewrite.sql", "+alter"), "protected file");
    }

    @Test
    void rejectsExcessiveFilesAndChangedLines() {
        assertRejected(diff("a.txt", "+1") + diff("b.txt", "+2") + diff("c.txt", "+3"), "file count");
        assertRejected(diff("a.txt", "+1\n+2\n+3\n+4\n+5\n+6"), "changed lines");
    }

    @Test
    void rejectsFileMarkersThatEscapeAValidLookingHeader() {
        assertRejected("diff --git a/src/App.java b/src/App.java\n--- a/src/App.java\n+++ b/../../evil\n@@ -1 +1 @@\n-old\n+new\n",
                "does not match");
    }

    private void assertRejected(String patch, String reason) {
        assertThat(validator.validate(patch)).satisfies(result -> {
            assertThat(result.valid()).isFalse();
            assertThat(result.reason()).contains(reason);
        });
    }

    private static String diff(String path, String body) {
        return "diff --git a/" + path + " b/" + path + "\n"
                + "--- a/" + path + "\n+++ b/" + path + "\n@@ -1 +1 @@\n-old\n" + body + "\n";
    }
}
