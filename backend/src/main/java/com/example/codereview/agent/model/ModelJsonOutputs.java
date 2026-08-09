package com.example.codereview.agent.model;

/**
 * 模型 JSON 输出的共享预处理工具。
 *
 * <p>围栏剥离原是 {@link ModelOutputValidator} 的私有实现,第二条解析路径
 * {@link AgentFindingModelService} 缺同等防线——MiMo 等真实模型常以 {@code ```json}
 * 围栏包裹输出,VERIFYING_FINDINGS 首次真实运行即在解析层失败(run15,2026-08-09)。
 * 提取为单一事实源,两处调用同一实现,防止防线在解析路径之间漂移。
 */
final class ModelJsonOutputs {

    private ModelJsonOutputs() {
    }

    /** 剥离 ``` / ```json 围栏;非围栏输入仅做 trim 后原样返回。 */
    static String unwrapMarkdown(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int closing = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || closing <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, closing).trim();
    }
}
