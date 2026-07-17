package com.example.codereview.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationReportExporterTest {
    @TempDir Path temp;
    @Test void exportsStableJsonAndMarkdown() throws Exception {
        EvaluationMetrics metrics = EvaluationMetrics.calculate(new EvaluationMetrics.Input(
                8, 2, 2, 18, 4, 1, 9, 10, 7, 6, 5, 4, 120_000, 10, 1.25));
        new EvaluationReportExporter(new ObjectMapper()).export(temp, "baseline", metrics);
        assertThat(Files.readString(temp.resolve("baseline.json"))).contains("\"precision\" : 0.8", "\"passed\" : true");
        assertThat(Files.readString(temp.resolve("baseline.md"))).contains("Precision", "Patch apply rate", "Gate: **PASS**");
    }
}
