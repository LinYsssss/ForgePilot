package com.example.codereview.evaluation;

import java.util.List;

public record EvaluationReport(String corpusVersion, String schemaVersion, FixedRun fixedRun,
                               List<CaseReport> cases, List<String> errors) {
    public record FixedRun(String toolImage, String model, String promptVersion, String findingSchemaVersion,
                           double temperature, int maxModelCalls, int maxToolCalls, int maxTokens,
                           int timeoutSeconds) {}

    public record CaseReport(String id, String split, String language, String fixture, String fixtureLayout,
                             List<ExpectedFinding> expectedFindings, List<String> nonFindings,
                             ExpectedPatch expectedPatch, Requirement requirement,
                             List<AcceptanceCriterion> acceptanceCriteria,
                             List<ConsistencyTruth> consistencyTruth) {
        /**
         * Keep the pre-P8 constructor available for callers that only consume finding labels.
         * P8 validation still rejects the resulting report when the new corpus annotations are absent.
         */
        public CaseReport(String id, String split, String language, String fixture, String fixtureLayout,
                          List<ExpectedFinding> expectedFindings, List<String> nonFindings,
                          ExpectedPatch expectedPatch) {
            this(id, split, language, fixture, fixtureLayout, expectedFindings, nonFindings,
                    expectedPatch, null, null, null);
        }
    }

    public record Requirement(String title, String background, String description) {}

    public record AcceptanceCriterion(String id, String text) {}

    public record ConsistencyTruth(String acId, String verdict) {}

    public record ExpectedFinding(String category, String severity, String path, int line,
                                  Integer lineEnd, List<String> categoryEquivalents) {}
    public record ExpectedPatch(String result, String file) {}
    public boolean valid() { return errors == null || errors.isEmpty(); }
}
