package com.example.codereview.finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FindingDeduplicator {

    public String fingerprint(FindingCandidate candidate, String lineNeighborhood) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        String category = candidate.category().trim().toLowerCase(Locale.ROOT);
        String file = normalizePath(candidate.filePath());
        String symbol = candidate.symbol() == null ? "" : candidate.symbol().trim().toLowerCase(Locale.ROOT);
        String neighborhood = FindingEvidence.sha256(normalizeNeighborhood(lineNeighborhood));
        return FindingEvidence.sha256(category + "\n" + file + "\n" + symbol + "\n" + neighborhood);
    }

    public List<DeduplicatedFinding> deduplicate(List<FindingCandidateContext> contexts) {
        Map<String, Accumulator> grouped = new LinkedHashMap<>();
        if (contexts == null) {
            return List.of();
        }
        for (FindingCandidateContext context : contexts) {
            String fingerprint = fingerprint(context.candidate(), context.lineNeighborhood());
            grouped.computeIfAbsent(fingerprint, ignored -> new Accumulator(context.candidate()))
                    .merge(context.candidate());
        }
        return grouped.entrySet().stream()
                .map(entry -> new DeduplicatedFinding(entry.getValue().candidate(), entry.getKey(), entry.getValue().count))
                .toList();
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.replace('\\', '/').replaceAll("^\\./+", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizeNeighborhood(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static final class Accumulator {
        private final FindingCandidate first;
        private final Map<String, FindingEvidence> evidence = new LinkedHashMap<>();
        private int count;

        private Accumulator(FindingCandidate first) {
            this.first = first;
        }

        private void merge(FindingCandidate candidate) {
            count++;
            candidate.evidence().forEach(item -> evidence.putIfAbsent(
                    item.evidenceType() + "|" + item.sourceVersion() + "|" + item.contentHash(), item));
        }

        private FindingCandidate candidate() {
            return new FindingCandidate(
                    first.severity(), first.category(), first.title(), first.description(), first.filePath(),
                    first.lineStart(), first.lineEnd(), first.symbol(), first.ruleId(), first.sourceVersion(),
                    new ArrayList<>(evidence.values()));
        }
    }
}
