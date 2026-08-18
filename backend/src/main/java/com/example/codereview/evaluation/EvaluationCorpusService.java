package com.example.codereview.evaluation;

import com.example.codereview.common.PinnedImageDigests;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class EvaluationCorpusService {
    private static final Set<String> VALID_SPLITS = Set.of("development", "holdout");
    private static final Set<String> VALID_FIXTURE_LAYOUTS = Set.of("single", "base-head");
    private static final Set<String> VALID_CONSISTENCY_VERDICTS = Set.of("COVERED", "NOT_FOUND", "AT_RISK");

    private final ObjectMapper mapper;

    public EvaluationCorpusService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EvaluationReport validate(Path manifestPath) {
        List<String> errors = new ArrayList<>();
        try {
            Path manifest = manifestPath.toRealPath();
            Path root = manifest.getParent();
            JsonNode json = mapper.readTree(manifest.toFile());

            EvaluationReport.FixedRun fixed = readFixedRun(json, errors);
            if (fixed != null) {
                if (fixed.temperature() != 0) {
                    errors.add("temperature must be zero");
                }
                if (!PinnedImageDigests.isPinned(fixed.toolImage())) {
                    errors.add("tool image must be digest pinned");
                }
            }

            List<EvaluationReport.CaseReport> cases = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            JsonNode caseNodes = json.path("cases");
            if (!caseNodes.isArray()) {
                errors.add("cases must be an array");
            } else {
                for (JsonNode item : caseNodes) {
                    EvaluationReport.CaseReport value = mapper.treeToValue(item, EvaluationReport.CaseReport.class);
                    if (value == null) {
                        errors.add("invalid case: null");
                        continue;
                    }
                    cases.add(value);
                    validateCase(value, root, ids, errors);
                }
            }
            if (cases.stream().noneMatch(c -> "development".equals(c.split()))
                    || cases.stream().noneMatch(c -> "holdout".equals(c.split()))) {
                errors.add("development and holdout splits are required");
            }
            return new EvaluationReport(json.path("corpusVersion").asText(), json.path("schemaVersion").asText(),
                    fixed, List.copyOf(cases), List.copyOf(errors));
        } catch (IOException ex) {
            throw new IllegalArgumentException("evaluation manifest is unreadable", ex);
        }
    }

    private EvaluationReport.FixedRun readFixedRun(JsonNode json, List<String> errors) throws IOException {
        JsonNode fixedNode = json.path("fixedRun");
        if (fixedNode.isMissingNode() || fixedNode.isNull()) {
            errors.add("missing fixedRun");
            return null;
        }
        return mapper.treeToValue(fixedNode, EvaluationReport.FixedRun.class);
    }

    private void validateCase(EvaluationReport.CaseReport value, Path root, Set<String> ids, List<String> errors) {
        String caseId = value.id();
        if (blank(caseId)) {
            errors.add("missing case id");
        } else if (!ids.add(caseId)) {
            errors.add("duplicate case id: " + caseId);
        }
        if (!VALID_SPLITS.contains(value.split())) {
            errors.add("invalid split: " + caseId);
        }
        validateFixture(value, root, errors);
        validateFindingLabels(value, errors);
        validateRequirementAnnotations(value, errors);
    }

    private void validateFixture(EvaluationReport.CaseReport value, Path root, List<String> errors) {
        String caseId = value.id();
        if (blank(value.fixture())) {
            errors.add("missing fixture: " + caseId);
            return;
        }
        Path fixture = root.resolve(value.fixture()).normalize();
        if (!fixture.startsWith(root) || !Files.isDirectory(fixture)) {
            errors.add("missing fixture: " + caseId);
            return;
        }
        if (value.fixtureLayout() != null && !VALID_FIXTURE_LAYOUTS.contains(value.fixtureLayout())) {
            errors.add("invalid fixture layout: " + caseId);
        }
        if ("base-head".equals(value.fixtureLayout())) {
            Path base = fixture.resolve("base").normalize();
            Path head = fixture.resolve("head").normalize();
            if (!base.startsWith(root) || !Files.isDirectory(base)
                    || !head.startsWith(root) || !Files.isDirectory(head)) {
                errors.add("missing base or head directory: " + caseId);
            }
        }
        if (value.expectedPatch() != null && !blank(value.expectedPatch().file())) {
            Path patch = fixture.resolve(value.expectedPatch().file()).normalize();
            if (!patch.startsWith(fixture) || !Files.isRegularFile(patch)) {
                errors.add("missing patch: " + caseId);
            }
        }
    }

    private void validateFindingLabels(EvaluationReport.CaseReport value, List<String> errors) {
        String caseId = value.id();
        if (value.expectedFindings() == null || value.nonFindings() == null) {
            errors.add("missing labels: " + caseId);
            return;
        }
        value.expectedFindings().forEach(f -> {
            if (f == null || blank(f.category()) || blank(f.severity()) || blank(f.path()) || f.line() <= 0) {
                errors.add("invalid expected finding: " + caseId);
                return;
            }
            if (f.lineEnd() != null && f.lineEnd() < f.line()) {
                errors.add("invalid line range: " + caseId);
            }
            if (f.categoryEquivalents() != null && f.categoryEquivalents().stream().anyMatch(EvaluationCorpusService::blank)) {
                errors.add("invalid category equivalents: " + caseId);
            }
        });
    }

    private void validateRequirementAnnotations(EvaluationReport.CaseReport value, List<String> errors) {
        String caseId = value.id();
        EvaluationReport.Requirement requirement = value.requirement();
        if (requirement == null) {
            errors.add("missing requirement: " + caseId);
        } else if (blank(requirement.title()) || blank(requirement.background()) || blank(requirement.description())) {
            errors.add("invalid requirement: " + caseId);
        }

        List<EvaluationReport.AcceptanceCriterion> criteria = value.acceptanceCriteria();
        Set<String> criterionIds = new LinkedHashSet<>();
        if (criteria == null || criteria.isEmpty()) {
            errors.add("missing acceptance criteria: " + caseId);
        } else {
            for (EvaluationReport.AcceptanceCriterion criterion : criteria) {
                if (criterion == null || blank(criterion.id()) || blank(criterion.text())) {
                    errors.add("invalid acceptance criterion: " + caseId);
                    continue;
                }
                if (!criterionIds.add(criterion.id())) {
                    errors.add("duplicate acceptance criterion id: " + caseId + "/" + criterion.id());
                }
            }
        }

        List<EvaluationReport.ConsistencyTruth> truths = value.consistencyTruth();
        Set<String> truthIds = new LinkedHashSet<>();
        if (truths == null || truths.isEmpty()) {
            errors.add("missing consistency truth: " + caseId);
        } else {
            for (EvaluationReport.ConsistencyTruth truth : truths) {
                if (truth == null || blank(truth.acId())) {
                    errors.add("invalid consistency truth: " + caseId);
                    continue;
                }
                String acId = truth.acId();
                if (!truthIds.add(acId)) {
                    errors.add("duplicate consistency truth ac id: " + caseId + "/" + acId);
                }
                if (!criterionIds.contains(acId)) {
                    errors.add("consistency truth references unknown acceptance criterion: " + caseId + "/" + acId);
                }
                if (!VALID_CONSISTENCY_VERDICTS.contains(truth.verdict())) {
                    errors.add("invalid consistency truth verdict: " + caseId + "/" + acId);
                }
            }
        }
        for (String criterionId : criterionIds) {
            if (!truthIds.contains(criterionId)) {
                errors.add("missing consistency truth for acceptance criterion: " + caseId + "/" + criterionId);
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
