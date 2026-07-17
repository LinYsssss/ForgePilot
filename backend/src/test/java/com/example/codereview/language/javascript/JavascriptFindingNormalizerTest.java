package com.example.codereview.language.javascript;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.finding.FindingSeverity;
import org.junit.jupiter.api.Test;

class JavascriptFindingNormalizerTest {

    private final JavascriptFindingNormalizer normalizer = new JavascriptFindingNormalizer();

    @Test
    void normalizesEslintSemgrepAndTypescriptOutputs() {
        var eslint = normalizer.parseEslintJson("""
                [{"filePath":"src/app.ts","messages":[{
                  "ruleId":"security/detect-object-injection","severity":2,
                  "message":"Variable object injection sink","line":8,"endLine":8
                }]}]
                """, "eslint-9.8.0");
        var semgrep = normalizer.parseSemgrepJson("""
                {"results":[{"check_id":"typescript.lang.security.audit.path-traversal",
                  "path":"src/files.ts","start":{"line":14},"end":{"line":14},
                  "extra":{"message":"Path traversal","severity":"ERROR","lines":"readFile(userPath)"}}]}
                """, "semgrep-rules-2026.07");
        var typescript = normalizer.parseTypescriptOutput(
                "src/app.ts(3,7): error TS2322: Type 'string' is not assignable to type 'number'.",
                "typescript-5.7.0");

        assertThat(eslint).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
            assertThat(finding.ruleId()).isEqualTo("security/detect-object-injection");
            assertThat(finding.filePath()).isEqualTo("src/app.ts");
        });
        assertThat(semgrep).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
            assertThat(finding.evidence()).singleElement().satisfies(evidence ->
                    assertThat(evidence.excerpt()).isEqualTo("readFile(userPath)"));
        });
        assertThat(typescript).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("TS2322");
            assertThat(finding.lineStart()).isEqualTo(3);
        });
    }

    @Test
    void parsesJestAndVitestJsonAsValidationResults() {
        JavascriptValidationResult jest = normalizer.parseTestJson("""
                {"numTotalTests":5,"numPassedTests":4,"numFailedTests":1,"numPendingTests":0,"startTime":1000,
                 "testResults":[{"endTime":1420}]}
                """, "jest-30.0.0");

        assertThat(jest.tests()).isEqualTo(5);
        assertThat(jest.passedTests()).isEqualTo(4);
        assertThat(jest.failedTests()).isEqualTo(1);
        assertThat(jest.passed()).isFalse();
        assertThat(jest.durationMillis()).isEqualTo(420);
    }
}
