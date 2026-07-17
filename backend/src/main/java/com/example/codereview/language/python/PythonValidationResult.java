package com.example.codereview.language.python;

public record PythonValidationResult(
        String toolVersion,
        int tests,
        int failures,
        int errors,
        int skipped,
        double durationSeconds) {

    public PythonValidationResult {
        if (toolVersion == null || toolVersion.isBlank()) {
            throw new IllegalArgumentException("toolVersion is required");
        }
        if (tests < 0 || failures < 0 || errors < 0 || skipped < 0 || durationSeconds < 0
                || failures + errors + skipped > tests) {
            throw new IllegalArgumentException("test result counts are invalid");
        }
    }

    public boolean passed() {
        return failures == 0 && errors == 0;
    }
}
