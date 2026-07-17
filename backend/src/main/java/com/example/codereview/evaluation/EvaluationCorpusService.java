package com.example.codereview.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class EvaluationCorpusService {
    private final ObjectMapper mapper;
    public EvaluationCorpusService(ObjectMapper mapper) { this.mapper = mapper; }

    public EvaluationReport validate(Path manifestPath) {
        List<String> errors = new ArrayList<>();
        try {
            Path manifest = manifestPath.toRealPath(); Path root = manifest.getParent();
            JsonNode json = mapper.readTree(manifest.toFile());
            EvaluationReport.FixedRun fixed = mapper.treeToValue(json.path("fixedRun"), EvaluationReport.FixedRun.class);
            if (fixed.temperature() != 0) errors.add("temperature must be zero");
            if (fixed.toolImage() == null || !fixed.toolImage().matches(".+@sha256:[a-fA-F0-9]+")) errors.add("tool image must be digest pinned");
            List<EvaluationReport.CaseReport> cases = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (JsonNode item : json.path("cases")) {
                EvaluationReport.CaseReport value = mapper.treeToValue(item, EvaluationReport.CaseReport.class);
                cases.add(value);
                if (!ids.add(value.id())) errors.add("duplicate case id: " + value.id());
                if (!Set.of("development", "holdout").contains(value.split())) errors.add("invalid split: " + value.id());
                Path fixture = root.resolve(value.fixture()).normalize();
                if (!fixture.startsWith(root) || !Files.isDirectory(fixture)) errors.add("missing fixture: " + value.id());
                if (value.expectedFindings() == null || value.nonFindings() == null) errors.add("missing labels: " + value.id());
                else value.expectedFindings().forEach(f -> {
                    if (blank(f.category()) || blank(f.severity()) || blank(f.path()) || f.line() <= 0)
                        errors.add("invalid expected finding: " + value.id());
                });
                if (value.expectedPatch() != null && !blank(value.expectedPatch().file())
                        && !Files.isRegularFile(fixture.resolve(value.expectedPatch().file()))) errors.add("missing patch: " + value.id());
            }
            if (cases.stream().noneMatch(c -> c.split().equals("development"))
                    || cases.stream().noneMatch(c -> c.split().equals("holdout"))) errors.add("development and holdout splits are required");
            return new EvaluationReport(json.path("corpusVersion").asText(), json.path("schemaVersion").asText(),
                    fixed, List.copyOf(cases), List.copyOf(errors));
        } catch (IOException ex) { throw new IllegalArgumentException("evaluation manifest is unreadable", ex); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
