package com.example.codereview.language.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.finding.EvidenceType;
import com.example.codereview.finding.FindingCandidate;
import com.example.codereview.finding.FindingSeverity;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaFindingNormalizerTest {

    private final JavaFindingNormalizer normalizer = new JavaFindingNormalizer();

    @Test
    void normalizesPmdSpotBugsAndCheckstyleXml() {
        List<FindingCandidate> pmd = normalizer.parsePmdXml("""
                <pmd version="7.5.0"><file name="src/UserRepository.java">
                  <violation beginline="21" endline="21" rule="AvoidDuplicateLiterals" priority="2">
                    Repeated security-sensitive literal
                  </violation>
                </file></pmd>
                """, "pmd-7.5.0");
        List<FindingCandidate> spotBugs = normalizer.parseSpotBugsXml("""
                <BugCollection version="4.8.6"><BugInstance type="SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE" priority="1">
                  <Class classname="com.acme.UserRepository"/>
                  <SourceLine sourcepath="src/UserRepository.java" start="21" end="21"/>
                  <LongMessage>Nonconstant string passed to execute</LongMessage>
                </BugInstance></BugCollection>
                """, "spotbugs-4.8.6");
        List<FindingCandidate> checkstyle = normalizer.parseCheckstyleXml("""
                <checkstyle version="10.17"><file name="src/App.java">
                  <error line="8" column="5" severity="warning" message="Missing a Javadoc comment." source="JavadocMethod"/>
                </file></checkstyle>
                """, "checkstyle-10.17");

        assertFinding(pmd.get(0), FindingSeverity.HIGH, "AvoidDuplicateLiterals", "src/UserRepository.java", 21);
        assertFinding(spotBugs.get(0), FindingSeverity.HIGH, "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
                "src/UserRepository.java", 21);
        assertFinding(checkstyle.get(0), FindingSeverity.LOW, "JavadocMethod", "src/App.java", 8);
    }

    @Test
    void normalizesSarifResultsWithRuleAndVersionEvidence() {
        List<FindingCandidate> findings = normalizer.parseSarif("""
                {
                  "version": "2.1.0",
                  "runs": [{
                    "tool": {"driver": {"name": "PMD", "semanticVersion": "7.5.0"}},
                    "results": [{
                      "ruleId": "java/sql-injection",
                      "level": "error",
                      "message": {"text": "Untrusted SQL query"},
                      "locations": [{"physicalLocation": {
                        "artifactLocation": {"uri": "src/UserRepository.java"},
                        "region": {"startLine": 21, "endLine": 21, "snippet": {"text": "execute(sql)"}}
                      }}]
                    }]
                  }]
                }
                """, "pmd-rules-2026.07");

        FindingCandidate finding = findings.get(0);
        assertFinding(finding, FindingSeverity.HIGH, "java/sql-injection", "src/UserRepository.java", 21);
        assertThat(finding.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.evidenceType()).isEqualTo(EvidenceType.STATIC_ANALYZER);
            assertThat(evidence.sourceVersion()).isEqualTo("pmd-rules-2026.07");
            assertThat(evidence.excerpt()).isEqualTo("execute(sql)");
        });
    }

    private static void assertFinding(
            FindingCandidate finding,
            FindingSeverity severity,
            String ruleId,
            String file,
            int line) {
        assertThat(finding.severity()).isEqualTo(severity);
        assertThat(finding.ruleId()).isEqualTo(ruleId);
        assertThat(finding.filePath()).isEqualTo(file);
        assertThat(finding.lineStart()).isEqualTo(line);
        assertThat(finding.evidence()).singleElement()
                .extracting(evidence -> evidence.evidenceType())
                .isEqualTo(EvidenceType.STATIC_ANALYZER);
    }
}
