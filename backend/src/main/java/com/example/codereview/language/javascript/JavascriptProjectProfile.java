package com.example.codereview.language.javascript;

import java.util.Set;

public record JavascriptProjectProfile(
        PackageManager packageManager,
        boolean typescript,
        Set<TestFramework> testFrameworks) {

    public JavascriptProjectProfile {
        packageManager = packageManager == null ? PackageManager.UNKNOWN : packageManager;
        testFrameworks = testFrameworks == null ? Set.of() : Set.copyOf(testFrameworks);
    }

    public enum PackageManager {
        NPM,
        PNPM,
        YARN,
        UNKNOWN
    }

    public enum TestFramework {
        JEST,
        VITEST
    }
}
