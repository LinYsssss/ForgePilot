package com.example.codereview.ai.langchain4j;

import com.example.codereview.context.ReviewContextService;
import com.example.codereview.context.ReviewRetrievalQuery;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class LangChain4jReviewContentRetriever implements ContentRetriever {

    private static final String SCOPE_PARAMETER = "reposage.review-retrieval-scope";

    private final ReviewContextService reviewContextService;

    public LangChain4jReviewContentRetriever(ReviewContextService reviewContextService) {
        this.reviewContextService = reviewContextService;
    }

    @Override
    public List<Content> retrieve(Query query) {
        ReviewRetrievalQuery scope = scopeFrom(query);
        return reviewContextService.retrieve(scope.toDomainRequest()).stream()
                .map(LangChain4jReviewContentRetriever::toContent)
                .toList();
    }

    public static Query toQuery(ReviewRetrievalQuery scope) {
        Objects.requireNonNull(scope, "scope");
        InvocationParameters parameters = InvocationParameters.from(SCOPE_PARAMETER, scope);
        InvocationContext context = InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName(LangChain4jReviewContentRetriever.class.getName())
                .methodName("retrieve")
                .methodArguments(List.of())
                .invocationParameters(parameters)
                .timestampNow()
                .build();
        dev.langchain4j.rag.query.Metadata metadata =
                dev.langchain4j.rag.query.Metadata.builder()
                        .chatMessage(UserMessage.from("bounded review context"))
                        .chatMemory(List.of())
                        .invocationContext(context)
                        .build();
        return Query.from("bounded review context", metadata);
    }

    private static ReviewRetrievalQuery scopeFrom(Query query) {
        if (query == null || query.metadata() == null
                || query.metadata().invocationParameters() == null) {
            throw new IllegalArgumentException("typed review retrieval scope is required");
        }
        Object value = query.metadata().invocationParameters().get(SCOPE_PARAMETER);
        if (!(value instanceof ReviewRetrievalQuery scope)) {
            throw new IllegalArgumentException("typed review retrieval scope is required");
        }
        return scope;
    }

    private static Content toContent(ReviewContextService.ContextEvidence evidence) {
        Metadata metadata = new Metadata()
                .put("citation", evidence.reference())
                .put("source_name", evidence.sourceName())
                .put("chunk_index", evidence.chunkIndex())
                .put("document_type", evidence.documentType())
                .put("source_version", evidence.sourceVersion())
                .put("trust", evidence.untrusted() ? "untrusted" : "trusted");
        TextSegment segment = TextSegment.from(evidence.content(), metadata);
        return Content.from(segment, Map.of(ContentMetadata.SCORE, evidence.score()));
    }
}
