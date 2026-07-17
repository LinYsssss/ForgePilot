package com.example.codereview.context;

import com.example.codereview.knowledge.KnowledgeDtos.SearchMatch;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class HybridContextRanker {

    public List<SearchMatch> rank(List<SearchMatch> matches, List<String> symbols, List<String> lexicalTerms) {
        return matches.stream()
                .sorted(Comparator.comparingDouble((SearchMatch match) -> score(match, symbols, lexicalTerms))
                        .reversed()
                        .thenComparing(SearchMatch::sourceName)
                        .thenComparingInt(SearchMatch::chunkIndex))
                .toList();
    }

    public double score(SearchMatch match, List<String> symbols, List<String> lexicalTerms) {
        String searchable = (match.sourceName() + "\n" + match.content()).toLowerCase(Locale.ROOT);
        double vector = clamp(match.score()) * 0.40;
        double lexical = ratio(searchable, lexicalTerms) * 0.25;
        double symbol = ratio(searchable, symbols) * 0.20;
        double documentType = documentTypeWeight(match.docType()) * 0.15;
        return vector + lexical + symbol + documentType;
    }

    private double ratio(String searchable, List<String> terms) {
        List<String> useful = terms == null ? List.of() : terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .map(term -> term.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        if (useful.isEmpty()) {
            return 0;
        }
        return useful.stream().filter(searchable::contains).count() / (double) useful.size();
    }

    private double documentTypeWeight(String docType) {
        if (docType == null) {
            return 0;
        }
        return switch (docType.toUpperCase(Locale.ROOT)) {
            case "SECURITY", "STANDARD", "POLICY", "ARCHITECTURE" -> 1.0;
            case "RUNBOOK", "DESIGN", "API" -> 0.75;
            default -> 0.25;
        };
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
