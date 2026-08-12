package com.example.codereview.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationCorpusServiceTest {
    private static final String HOLDOUT_FILLER_CASE = """
            { "id": "holdout-filler", "split": "holdout", "language": "JAVA", "fixture": "cases/holdout-filler",
              "expectedFindings": [], "nonFindings": [], "expectedPatch": null }""";

    @Test
    void validatesVersionedDevelopmentAndHoldoutCorpus() {
        EvaluationReport report = new EvaluationCorpusService(new ObjectMapper())
                .validate(Path.of("..", "evaluation", "manifest.json"));
        assertThat(report.valid()).as(report.errors().toString()).isTrue();
        assertThat(report.corpusVersion()).isEqualTo("pr-gatekeeper-eval-v1");
        assertThat(report.fixedRun().temperature()).isZero();
        assertThat(report.cases()).extracting(EvaluationReport.CaseReport::split)
                .contains("development", "holdout");
        assertThat(report.cases()).anyMatch(c -> c.id().equals("prompt-injection-comment"))
                .anyMatch(c -> c.expectedPatch() != null && c.expectedPatch().result().equals("APPLIES_AND_PASSES"));
    }

    @Test
    void acceptsBaseHeadLayoutWithLineRangeAndCategoryEquivalents(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("cases/base-head-case/base"));
        Files.createDirectories(tempDir.resolve("cases/base-head-case/head"));
        Files.createDirectories(tempDir.resolve("cases/holdout-filler"));
        Path manifest = writeManifest(tempDir, """
                { "id": "base-head-case", "split": "development", "language": "JAVA",
                  "fixture": "cases/base-head-case", "fixtureLayout": "base-head",
                  "expectedFindings": [{ "category": "NULL_POINTER", "severity": "HIGH", "path": "src/App.java",
                                         "line": 3, "lineEnd": 5, "categoryEquivalents": ["UNKNOWN"] }],
                  "nonFindings": [], "expectedPatch": null }""");
        EvaluationReport report = new EvaluationCorpusService(new ObjectMapper()).validate(manifest);
        assertThat(report.valid()).as(report.errors().toString()).isTrue();
        EvaluationReport.CaseReport caseReport = report.cases().get(0);
        assertThat(caseReport.fixtureLayout()).isEqualTo("base-head");
        EvaluationReport.ExpectedFinding finding = caseReport.expectedFindings().get(0);
        assertThat(finding.lineEnd()).isEqualTo(5);
        assertThat(finding.categoryEquivalents()).containsExactly("UNKNOWN");
    }

    @Test
    void rejectsBaseHeadFixtureWithoutHeadDirectory(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("cases/base-head-case/base"));
        Files.createDirectories(tempDir.resolve("cases/holdout-filler"));
        Path manifest = writeManifest(tempDir, """
                { "id": "base-head-case", "split": "development", "language": "JAVA",
                  "fixture": "cases/base-head-case", "fixtureLayout": "base-head",
                  "expectedFindings": [], "nonFindings": [], "expectedPatch": null }""");
        EvaluationReport report = new EvaluationCorpusService(new ObjectMapper()).validate(manifest);
        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).contains("missing base or head directory: base-head-case");
    }

    @Test
    void rejectsUnknownFixtureLayout(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("cases/layout-case"));
        Files.createDirectories(tempDir.resolve("cases/holdout-filler"));
        Path manifest = writeManifest(tempDir, """
                { "id": "layout-case", "split": "development", "language": "JAVA",
                  "fixture": "cases/layout-case", "fixtureLayout": "two-way-diff",
                  "expectedFindings": [], "nonFindings": [], "expectedPatch": null }""");
        EvaluationReport report = new EvaluationCorpusService(new ObjectMapper()).validate(manifest);
        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).contains("invalid fixture layout: layout-case");
    }

    @Test
    void rejectsLineEndBeforeLine(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("cases/range-case"));
        Files.createDirectories(tempDir.resolve("cases/holdout-filler"));
        Path manifest = writeManifest(tempDir, """
                { "id": "range-case", "split": "development", "language": "JAVA",
                  "fixture": "cases/range-case",
                  "expectedFindings": [{ "category": "NULL_POINTER", "severity": "HIGH", "path": "src/App.java",
                                         "line": 7, "lineEnd": 3 }],
                  "nonFindings": [], "expectedPatch": null }""");
        EvaluationReport report = new EvaluationCorpusService(new ObjectMapper()).validate(manifest);
        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).contains("invalid line range: range-case");
    }

    @Test
    void rejectsBlankCategoryEquivalentEntry(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("cases/equiv-case"));
        Files.createDirectories(tempDir.resolve("cases/holdout-filler"));
        Path manifest = writeManifest(tempDir, """
                { "id": "equiv-case", "split": "development", "language": "JAVA",
                  "fixture": "cases/equiv-case",
                  "expectedFindings": [{ "category": "NULL_POINTER", "severity": "HIGH", "path": "src/App.java",
                                         "line": 7, "categoryEquivalents": [" "] }],
                  "nonFindings": [], "expectedPatch": null }""");
        EvaluationReport report = new EvaluationCorpusService(new ObjectMapper()).validate(manifest);
        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).contains("invalid category equivalents: equiv-case");
    }

    private Path writeManifest(Path root, String developmentCase) throws IOException {
        String json = """
                {
                  "corpusVersion": "schema-extension-test-v1",
                  "schemaVersion": "evaluation-manifest-v1",
                  "fixedRun": { "toolImage": "reposage-tools@sha256:abcdef", "model": "test-model",
                                "promptVersion": "pr-gatekeeper-v1", "findingSchemaVersion": "finding-v1",
                                "temperature": 0, "maxModelCalls": 1, "maxToolCalls": 1,
                                "maxTokens": 1, "timeoutSeconds": 1 },
                  "cases": [
                %s,
                %s
                  ]
                }""".formatted(developmentCase, HOLDOUT_FILLER_CASE);
        Path manifest = root.resolve("manifest.json");
        Files.writeString(manifest, json);
        return manifest;
    }
}
