package com.example.codereview.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * r8-R1 golden 快照测试(chat 审查路径):legacy* 生成器逐字复刻重构前
 * OpenAiCompatibleReviewClient.systemPrompt()/buildPrompt() 的文本块与分支(旧代码原文
 * 搬进本测试),三层模板组装对代表性输入必须逐字节复现——字节等价是硬验收。
 *
 * <p>旧代码没有截断/打码行为,故不发明:仅层缺失路径在 {@link PromptTemplateRegistryTest}
 * 覆盖,本测试只钉字节。
 */
class ChatReviewPromptGoldenTest {

    private final AgentPromptAssembler assembler = new AgentPromptAssembler(
            new PromptTemplateRegistry()
    );

    @Test
    void systemMessageStaysByteIdenticalToLegacySystemPrompt() {
        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview("diff", "ctx");

        assertThat(prompt.systemMessage()).isEqualTo(legacySystemPrompt());
    }

    @Test
    void userMessageMatchesLegacyWithKnowledgeAndDiff() {
        String rag = "[citation:arch.md#chunk-1]\n本项目的服务层禁止自调用事务方法。";
        String diff = "diff --git a/OrderService.java b/OrderService.java\n"
                + "@@ -10,6 +10,7 @@\n"
                + "+    orderMapper.update(order);";

        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(diff, rag);

        assertThat(prompt.userMessage()).isEqualTo(legacyBuildPrompt(diff, rag));
    }

    @Test
    void userMessageMatchesLegacyForNullAndBlankPostures() {
        // 旧代码分支:ragContext 为 null/空白 → 兜底文案;diffText 为 null → 空串。
        assertThat(assembler.assembleChatReview(null, null).userMessage())
                .isEqualTo(legacyBuildPrompt(null, null));
        assertThat(assembler.assembleChatReview("", "").userMessage())
                .isEqualTo(legacyBuildPrompt("", ""));
        assertThat(assembler.assembleChatReview("+x", "   ").userMessage())
                .isEqualTo(legacyBuildPrompt("+x", "   "));
        assertThat(assembler.assembleChatReview("+x", "   ").userMessage())
                .contains("未检索到项目上下文。");
    }

    @Test
    void userMessageMatchesLegacyForShardStyleDiff() {
        // 分片审查:ReviewProcessor/DiffSplitter 按分片逐次调用 review(),任务层按分片实例化,
        // 系统/项目层内容跨分片相同——对每个分片输入等价即可。
        String context = "项目技术栈:Spring Boot 3 + MyBatis。";
        String shard1 = "diff --git a/A.java b/A.java\n+int a = 1;";
        String shard2 = "diff --git a/B.java b/B.java\n+int b = 2;";

        AgentPromptAssembler.ChatReviewPrompt first = assembler.assembleChatReview(shard1, context);
        AgentPromptAssembler.ChatReviewPrompt second = assembler.assembleChatReview(shard2, context);

        assertThat(first.userMessage()).isEqualTo(legacyBuildPrompt(shard1, context));
        assertThat(second.userMessage()).isEqualTo(legacyBuildPrompt(shard2, context));
        assertThat(first.systemMessage()).isEqualTo(second.systemMessage());
    }

    @Test
    void formatSpecifiersInsideInjectedValuesAreNeverReinterpreted() {
        // 旧 .formatted 只解析模板字面量,不复扫已注入值;分层组装(项目层先渲染再作为任务层
        // 槽值)必须保持同一性质,否则含 % 的 diff/知识内容会炸格式化或被二次替换。
        String rag = "SQL 拼接如 \"select * from t where name like '%s'\" 属高危。";
        String diff = "+String url = base + \"?rate=100%\";\n+log.info(\"%d records\");";

        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview(diff, rag);

        assertThat(prompt.userMessage()).isEqualTo(legacyBuildPrompt(diff, rag));
        assertThat(prompt.userMessage()).contains("like '%s'").contains("rate=100%").contains("%d records");
    }

    @Test
    void templateVersionsAreExposedForCallSiteLogging() {
        // chat 路径版本记录先走日志(ai_call_log 列扩展是 r8 遗留项),版本字段是日志契约。
        AgentPromptAssembler.ChatReviewPrompt prompt = assembler.assembleChatReview("diff", "ctx");

        assertThat(prompt.systemTemplateVersion()).isEqualTo("chat-review-system-v1");
        assertThat(prompt.projectTemplateVersion()).isEqualTo("chat-review-project-v1");
        assertThat(prompt.taskTemplateVersion()).isEqualTo("chat-review-task-v1");
    }

    /** 旧 OpenAiCompatibleReviewClient.systemPrompt() 原文(golden 生成器,保存旧代码形状)。 */
    private static String legacySystemPrompt() {
        return """
                你是资深 Java 代码审查专家。
                你必须基于 Git Diff、静态分析结果和 RAG 项目上下文进行审查。
                你不能编造不存在的文件、行号或证据。
                你只能输出 JSON，不要输出 Markdown。
                """;
    }

    /** 旧 OpenAiCompatibleReviewClient.buildPrompt() 原文(golden 生成器,保存旧代码形状)。 */
    private static String legacyBuildPrompt(String diffText, String ragContext) {
        return """
                请审查下面的 Git Diff。

                审查重点：
                1. 空指针风险
                2. SQL 注入风险
                3. 权限校验缺失
                4. 事务一致性问题
                5. 性能问题
                6. 业务规则破坏

                输出 JSON Schema：
                {
                  "summary": "string",
                  "overallRisk": "HIGH|MEDIUM|LOW|NONE",
                  "issues": [
                    {
                      "severity": "HIGH|MEDIUM|LOW",
                      "category": "NULL_POINTER|SQL_INJECTION|AUTH_RISK|TRANSACTION_RISK|PERFORMANCE_RISK|BUSINESS_RULE_RISK|UNKNOWN",
                      "filePath": "string",
                      "lineStart": 1,
                      "lineEnd": 1,
                      "title": "string",
                      "description": "string",
                      "impact": "string",
                      "evidenceSources": [{"sourceName":"string","quote":"string"}],
                      "suggestion": "string",
                      "confidence": 0.0
                    }
                  ]
                }

                RAG 项目上下文：
                %s

                Git Diff：
                %s
                """.formatted(
                ragContext == null || ragContext.isBlank() ? "未检索到项目上下文。" : ragContext,
                diffText == null ? "" : diffText
        );
    }
}
