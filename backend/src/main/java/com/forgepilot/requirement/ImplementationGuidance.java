package com.forgepilot.requirement;

import java.util.List;

/**
 * 针对某条需求某一次修订的一次性实现建议。
 *
 * <p>响应点名生成时读取的修订，建议版本另标识指令/schema。草稿可在同一修订上
 * 原地编辑，因此修订 id 不能证明内容未变，页面保存后仍须使旧建议失效。
 * 这些标识用于判断适用范围，不证明建议的语义正确。
 */
public record ImplementationGuidance(long requirementId, long revisionId, int revisionSeq,
        List<String> checklist, List<String> rules, List<String> risks,
        List<KnowledgeSource> knowledgeSources, String summary, List<String> questions,
        String guidanceVersion) {

    /** 实际进入本次 Guidance Prompt 的知识片段，分数是余弦向量语义召回相似度。 */
    public record KnowledgeSource(long documentId, String title, int chunkSeq, String excerpt,
            double similarity) {
    }
}
