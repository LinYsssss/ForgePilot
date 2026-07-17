package com.example.codereview.evaluation;

import java.util.List;

public record EvaluationReport(String corpusVersion, String schemaVersion, FixedRun fixedRun,
                               List<CaseReport> cases, List<String> errors) {
    public record FixedRun(String toolImage, String model, String promptVersion, String findingSchemaVersion,
                           double temperature, int maxModelCalls, int maxToolCalls, int maxTokens,
                           int timeoutSeconds) {}
    public record CaseReport(String id, String split, String language, String fixture,
                             List<ExpectedFinding> expectedFindings, List<String> nonFindings,
                             ExpectedPatch expectedPatch) {}
    public record ExpectedFinding(String category, String severity, String path, int line) {}
    public record ExpectedPatch(String result, String file) {}
    public boolean valid() { return errors == null || errors.isEmpty(); }
}
