package com.example.codereview.language.javascript;

import com.example.codereview.finding.EvidenceType;
import com.example.codereview.finding.FindingCandidate;
import com.example.codereview.finding.FindingEvidence;
import com.example.codereview.finding.FindingSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavascriptFindingNormalizer {

    private static final Pattern TYPESCRIPT_ERROR = Pattern.compile(
            "^(.+)\\((\\d+),(\\d+)\\):\\s+error\\s+(TS\\d+):\\s+(.+)$");

    private final ObjectMapper mapper = new ObjectMapper();

    public List<FindingCandidate> parseEslintJson(String json, String sourceVersion) {
        try {
            List<FindingCandidate> findings = new ArrayList<>();
            for (JsonNode fileResult : mapper.readTree(json)) {
                String file = required(fileResult.path("filePath").asText(), "ESLint filePath");
                for (JsonNode result : fileResult.path("messages")) {
                    String rule = required(result.path("ruleId").asText(), "ESLint ruleId");
                    String message = required(result.path("message").asText(), "ESLint message");
                    int start = result.path("line").asInt(1);
                    int end = result.path("endLine").asInt(start);
                    FindingSeverity severity = result.path("severity").asInt() >= 2
                            ? FindingSeverity.HIGH : FindingSeverity.LOW;
                    findings.add(candidate(severity, rule, message, file, start, end, sourceVersion, message));
                }
            }
            return List.copyOf(findings);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid ESLint JSON", ex);
        }
    }

    public List<FindingCandidate> parseSemgrepJson(String json, String sourceVersion) {
        try {
            List<FindingCandidate> findings = new ArrayList<>();
            for (JsonNode result : mapper.readTree(json).path("results")) {
                String rule = required(result.path("check_id").asText(), "Semgrep check_id");
                String message = required(result.path("extra").path("message").asText(), "Semgrep message");
                String file = required(result.path("path").asText(), "Semgrep path");
                int start = result.path("start").path("line").asInt(1);
                int end = result.path("end").path("line").asInt(start);
                FindingSeverity severity = switch (result.path("extra").path("severity").asText("WARNING")) {
                    case "ERROR" -> FindingSeverity.HIGH;
                    case "INFO" -> FindingSeverity.LOW;
                    default -> FindingSeverity.MEDIUM;
                };
                String excerpt = result.path("extra").path("lines").asText(message);
                findings.add(candidate(severity, rule, message, file, start, end, sourceVersion, excerpt));
            }
            return List.copyOf(findings);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid Semgrep JSON", ex);
        }
    }

    public List<FindingCandidate> parseTypescriptOutput(String output, String sourceVersion) {
        List<FindingCandidate> findings = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return List.of();
        }
        for (String line : output.lines().toList()) {
            Matcher matcher = TYPESCRIPT_ERROR.matcher(line.trim());
            if (matcher.matches()) {
                String file = matcher.group(1).replace('\\', '/');
                int row = Integer.parseInt(matcher.group(2));
                String rule = matcher.group(4);
                String message = matcher.group(5);
                findings.add(candidate(
                        FindingSeverity.MEDIUM, rule, message, file, row, row, sourceVersion, line.trim()));
            }
        }
        return List.copyOf(findings);
    }

    public JavascriptValidationResult parseTestJson(String json, String sourceVersion) {
        try {
            JsonNode root = mapper.readTree(json);
            long start = root.path("startTime").asLong(0);
            long end = start;
            for (JsonNode result : root.path("testResults")) {
                end = Math.max(end, result.path("endTime").asLong(start));
            }
            return new JavascriptValidationResult(
                    sourceVersion,
                    root.path("numTotalTests").asInt(),
                    root.path("numPassedTests").asInt(),
                    root.path("numFailedTests").asInt(),
                    root.path("numPendingTests").asInt(),
                    Math.max(0, end - start));
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid Jest/Vitest JSON", ex);
        }
    }

    private static FindingCandidate candidate(
            FindingSeverity severity,
            String rule,
            String message,
            String file,
            int start,
            int end,
            String sourceVersion,
            String excerpt) {
        FindingEvidence evidence = FindingEvidence.create(
                EvidenceType.STATIC_ANALYZER, sourceVersion, file, start, end, excerpt, 1.0);
        return new FindingCandidate(
                severity,
                "javascript." + rule,
                rule,
                message,
                file,
                start,
                end,
                null,
                rule,
                sourceVersion,
                List.of(evidence));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
