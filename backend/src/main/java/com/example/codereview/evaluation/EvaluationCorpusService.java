package com.example.codereview.evaluation;

import com.example.codereview.common.PinnedImageDigests;
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
            if (!PinnedImageDigests.isPinned(fixed.toolImage())) errors.add("tool image must be digest pinned");
            List<EvaluationReport.CaseReport> cases = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (JsonNode item : json.path("cases")) {
                EvaluationReport.CaseReport value = mapper.treeToValue(item, EvaluationReport.CaseReport.class);
                cases.add(value);
                if (!ids.add(value.id())) errors.add("duplicate case id: " + value.id());
                if (!Set.of("development", "holdout").contains(value.split())) errors.add("invalid split: " + value.id());
                Path fixture = root.resolve(value.fixture()).normalize();
                if (!fixture.startsWith(root) || !Files.isDirectory(fixture)) errors.add("missing fixture: " + value.id());
                if (value.fixtureLayout() != null && !Set.of("single", "base-head").contains(value.fixtureLayout()))
                    errors.add("invalid fixture layout: " + value.id());
                if ("base-head".equals(value.fixtureLayout())) {
                    Path base = fixture.resolve("base").normalize(); Path head = fixture.resolve("head").normalize();
                    if (!base.startsWith(root) || !Files.isDirectory(base)
                            || !head.startsWith(root) || !Files.isDirectory(head)) errors.add("missing base or head directory: " + value.id());
                }
                if (value.expectedFindings() == null || value.nonFindings() == null) errors.add("missing labels: " + value.id());
                else value.expectedFindings().forEach(f -> {
                    if (blank(f.category()) || blank(f.severity()) || blank(f.path()) || f.line() <= 0)
                        errors.add("invalid expected finding: " + value.id());
                    if (f.lineEnd() != null && f.lineEnd() < f.line()) errors.add("invalid line range: " + value.id());
                    if (f.categoryEquivalents() != null && f.categoryEquivalents().stream().anyMatch(EvaluationCorpusService::blank))
                        errors.add("invalid category equivalents: " + value.id());
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
