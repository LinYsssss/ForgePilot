package com.example.codereview.context;

import com.example.codereview.knowledge.KnowledgeDtos.SearchMatch;
import com.example.codereview.rag.RagService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 场景化上下文组装的统一入口(父 design §5):{@code build(scene, projectId, refs) → ContextBundle}。
 *
 * <p>P2 只落地 REQUIREMENT_CHECK(需求文本驱动的知识检索);PR_REVIEW 在 P4 收编现有
 * {@code ReviewContextService} 路径,ASSISTANT 在 P6 接入。未实现场景显式抛错,
 * 不做静默空返回——调用方拿到空上下文会误判为"知识库无相关内容"。
 */
@Component
public class ContextBuilder {

    private final RagService ragService;
    private final int knowledgeTopK;
    private final int maxSnippetChars;

    public ContextBuilder(RagService ragService,
                          @Value("${app.context.knowledge-top-k:5}") int knowledgeTopK,
                          @Value("${app.context.max-snippet-chars:1200}") int maxSnippetChars) {
        this.ragService = ragService;
        this.knowledgeTopK = knowledgeTopK;
        this.maxSnippetChars = maxSnippetChars;
    }

    /** REQUIREMENT_CHECK 场景的检索请求:query 由需求标题+描述拼成,调用方负责鉴权。 */
    public record Refs(String query) {
    }

    public record KnowledgeSnippet(String sourceName, String content) {
    }

    public record ContextBundle(List<KnowledgeSnippet> knowledgeSnippets, int truncatedSnippets) {
    }

    public ContextBundle build(ContextScene scene, Long projectId, Refs refs) {
        if (scene != ContextScene.REQUIREMENT_CHECK) {
            throw new UnsupportedOperationException("scene " + scene + " 尚未接入 ContextBuilder");
        }
        String query = refs == null || refs.query() == null ? "" : refs.query().strip();
        if (query.isEmpty()) {
            return new ContextBundle(List.of(), 0);
        }
        List<SearchMatch> matches = ragService.search(projectId, query, knowledgeTopK);
        int truncated = 0;
        List<KnowledgeSnippet> snippets = new java.util.ArrayList<>();
        for (SearchMatch match : matches) {
            String content = match.content() == null ? "" : match.content();
            if (content.length() > maxSnippetChars) {
                content = content.substring(0, maxSnippetChars);
                truncated++;
            }
            snippets.add(new KnowledgeSnippet(match.sourceName(), content));
        }
        return new ContextBundle(List.copyOf(snippets), truncated);
    }
}
