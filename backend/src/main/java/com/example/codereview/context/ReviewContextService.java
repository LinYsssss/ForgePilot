package com.example.codereview.context;

import com.example.codereview.knowledge.KnowledgeDtos.SearchMatch;
import com.example.codereview.rag.RagService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ReviewContextService {

    private static final int CANDIDATES_PER_QUERY = 24;

    private final RagService ragService;
    private final HybridContextRanker ranker;

    public ReviewContextService(RagService ragService, HybridContextRanker ranker) {
        this.ragService = ragService;
        this.ranker = ranker;
    }

    public List<ContextEvidence> retrieve(Request request) {
        Objects.requireNonNull(request, "request");
        List<String> queries = buildQueries(request);
        List<SearchMatch> candidates = queries.stream()
                .flatMap(query -> ragService.search(request.projectId(), query, CANDIDATES_PER_QUERY,
                        request.documentIds()).stream())
                .toList();
        List<String> lexicalTerms = queries.stream().map(ReviewContextService::queryValue).toList();
        Map<String, SearchMatch> unique = new LinkedHashMap<>();
        for (SearchMatch match : ranker.rank(candidates, request.symbols(), lexicalTerms)) {
            if (ranker.score(match, request.symbols(), lexicalTerms) >= request.scoreThreshold()) {
                unique.putIfAbsent(normalize(match.content()), match);
            }
        }
        List<ContextEvidence> result = new ArrayList<>();
        int usedBytes = 0;
        for (SearchMatch match : unique.values()) {
            ContextEvidence evidence = new ContextEvidence(
                    match.content(), match.sourceName() + "#chunk-" + match.chunkIndex(),
                    request.sourceVersion(), match.docType(), match.score(), true);
            int bytes = evidence.content().getBytes(StandardCharsets.UTF_8).length;
            if (request.contextByteBudget() > 0 && usedBytes + bytes > request.contextByteBudget()) {
                continue;
            }
            result.add(evidence);
            usedBytes += bytes;
        }
        return List.copyOf(result);
    }

    public static List<String> buildQueries(Request request) {
        List<String> queries = new ArrayList<>();
        addQueries(queries, "path", request.changedPaths());
        addQueries(queries, "symbol", request.symbols());
        addQueries(queries, "import", request.imports());
        addQueries(queries, "annotation", request.annotations());
        addQueries(queries, "string", request.strings());
        addQueries(queries, "rule", request.toolRuleIds());
        return List.copyOf(queries);
    }

    private static void addQueries(List<String> target, String prefix, List<String> values) {
        values.stream().filter(value -> value != null && !value.isBlank()).distinct()
                .map(value -> prefix + ":" + value.trim()).forEach(target::add);
    }

    private static String queryValue(String query) {
        int separator = query.indexOf(':');
        return separator < 0 ? query : query.substring(separator + 1);
    }

    private static String normalize(String content) {
        return content == null ? "" : content.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public record Request(Long projectId, List<Long> documentIds, String sourceVersion,
                          int contextByteBudget, double scoreThreshold, List<String> changedPaths,
                          List<String> symbols, List<String> imports, List<String> annotations,
                          List<String> strings, List<String> toolRuleIds) {
        public Request {
            Objects.requireNonNull(projectId, "projectId");
            sourceVersion = requireText(sourceVersion, "sourceVersion");
            documentIds = copy(documentIds);
            changedPaths = copy(changedPaths);
            symbols = copy(symbols);
            imports = copy(imports);
            annotations = copy(annotations);
            strings = copy(strings);
            toolRuleIds = copy(toolRuleIds);
            if (contextByteBudget < 0 || scoreThreshold < 0 || scoreThreshold > 1) {
                throw new IllegalArgumentException("invalid context retrieval limits");
            }
        }
    }

    public record ContextEvidence(String content, String reference, String sourceVersion,
                                  String documentType, double vectorScore, boolean untrusted) {
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
