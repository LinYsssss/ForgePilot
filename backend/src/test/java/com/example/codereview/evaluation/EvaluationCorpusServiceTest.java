package com.example.codereview.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EvaluationCorpusServiceTest {
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
}
