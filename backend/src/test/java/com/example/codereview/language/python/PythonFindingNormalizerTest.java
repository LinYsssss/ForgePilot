package com.example.codereview.language.python;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.finding.FindingSeverity;
import java.util.List;
import org.junit.jupiter.api.Test;

class PythonFindingNormalizerTest {

    private final PythonFindingNormalizer normalizer = new PythonFindingNormalizer();

    @Test
    void normalizesRuffAndBanditJson() {
        var ruff = normalizer.parseRuffJson("""
                [{
                  "code": "S608",
                  "message": "Possible SQL injection vector",
                  "filename": "app/repository.py",
                  "location": {"row": 12, "column": 5},
                  "end_location": {"row": 12, "column": 20}
                }]
                """, "ruff-0.5.5");
        var bandit = normalizer.parseBanditJson("""
                {"results": [{
                  "test_id": "B608",
                  "issue_severity": "HIGH",
                  "issue_text": "Possible SQL injection vector through string-based query construction.",
                  "filename": "app/repository.py",
                  "line_number": 12,
                  "line_range": [12, 13],
                  "code": "query = 'select ' + user_input"
                }]}
                """, "bandit-1.7.9");

        assertThat(ruff).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo("S608");
            assertThat(finding.filePath()).isEqualTo("app/repository.py");
            assertThat(finding.lineStart()).isEqualTo(12);
        });
        assertThat(bandit).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
            assertThat(finding.ruleId()).isEqualTo("B608");
            assertThat(finding.lineEnd()).isEqualTo(13);
            assertThat(finding.evidence()).singleElement().satisfies(evidence ->
                    assertThat(evidence.sourceVersion()).isEqualTo("bandit-1.7.9"));
        });
    }

    @Test
    void parsesPytestJUnitAsValidationResultWithoutTurningFailuresIntoFindings() {
        PythonValidationResult result = normalizer.parsePytestJunit("""
                <testsuites tests="4" failures="1" errors="0" skipped="1" time="0.42">
                  <testsuite name="pytest" tests="4" failures="1" errors="0" skipped="1" time="0.42"/>
                </testsuites>
                """, "pytest-8.3.2");

        assertThat(result.toolVersion()).isEqualTo("pytest-8.3.2");
        assertThat(result.tests()).isEqualTo(4);
        assertThat(result.failures()).isEqualTo(1);
        assertThat(result.errors()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.passed()).isFalse();
        assertThat(result.durationSeconds()).isEqualTo(0.42);
    }
}
