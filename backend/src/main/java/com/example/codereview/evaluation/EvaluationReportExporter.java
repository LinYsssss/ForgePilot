package com.example.codereview.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

@Service
public class EvaluationReportExporter {
    private final ObjectMapper mapper;
    public EvaluationReportExporter(ObjectMapper mapper) { this.mapper = mapper; }
    public void export(Path directory, String name, EvaluationMetrics metrics) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("invalid report name");
        try {
            Files.createDirectories(directory);
            mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name + ".json").toFile(), metrics);
            Files.writeString(directory.resolve(name + ".md"), markdown(metrics));
        } catch (IOException ex) { throw new IllegalStateException("cannot export evaluation report", ex); }
    }
    public String markdown(EvaluationMetrics m) {
        return "# Agent Evaluation\n\n"
                + "| Metric | Value |\n|---|---:|\n"
                + row("Precision", m.precision()) + row("Recall", m.recall()) + row("F1", m.f1())
                + row("High-risk recall", m.highRiskRecall()) + row("False-positive rate", m.falsePositiveRate())
                + row("Location accuracy", m.locationAccuracy()) + row("Patch apply rate", m.patchApplyRate())
                + row("Patch build rate", m.patchBuildRate()) + row("Patch test rate", m.patchTestRate())
                + row("Average duration ms", m.averageDurationMs()) + row("Total cost", m.totalCost())
                + "\nGate: **" + (m.gate().passed() ? "PASS" : "FAIL") + "**\n"
                + (m.gate().failures().isEmpty() ? "" : "\nFailures: " + String.join(", ", m.gate().failures()) + "\n");
    }
    private static String row(String label, double value) { return "| " + label + " | " + String.format(java.util.Locale.ROOT, "%.4f", value) + " |\n"; }
}
