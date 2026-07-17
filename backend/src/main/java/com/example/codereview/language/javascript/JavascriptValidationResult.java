package com.example.codereview.language.javascript;

public record JavascriptValidationResult(
        String toolVersion,
        int tests,
        int passedTests,
        int failedTests,
        int skippedTests,
        long durationMillis) {

    public JavascriptValidationResult {
        if (toolVersion == null || toolVersion.isBlank()) {
            throw new IllegalArgumentException("toolVersion is required");
        }
        if (tests < 0 || passedTests < 0 || failedTests < 0 || skippedTests < 0 || durationMillis < 0
                || passedTests + failedTests + skippedTests > tests) {
            throw new IllegalArgumentException("test result counts are invalid");
        }
    }

    public boolean passed() {
        return failedTests == 0;
    }
}
