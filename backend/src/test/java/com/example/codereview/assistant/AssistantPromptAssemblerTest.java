package com.example.codereview.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.agent.prompt.PromptTemplateRegistry;
import com.example.codereview.context.ContextBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssistantPromptAssemblerTest {

    @Test
    void redactsUntrustedContextAndPublishesOnlyStableWhitelist() {
        AssistantPromptAssembler assembler = new AssistantPromptAssembler(new PromptTemplateRegistry(), 30000, 24000);
        ContextBuilder.ContextBundle bundle = new ContextBuilder.ContextBundle(
                List.of(new ContextBuilder.KnowledgeSnippet("KB:security:0", "security.md", "API_TOKEN=secret")),
                0,
                new ContextBuilder.RequirementSnapshot("REQ-7", "REQ-7", "登录", "", "Authorization: Bearer abc",
                        "READY", List.of(new ContextBuilder.AcceptanceCriterion("AC-1", 1, "不得泄露密码"))),
                List.of(),
                List.of(new ContextBuilder.Source("REQ-7", "REQUIREMENT", "登录", "REQ-7"),
                        new ContextBuilder.Source("AC-1", "AC", "验收标准 1", "AC-1"),
                        new ContextBuilder.Source("KB:security:0", "KNOWLEDGE", "security.md", "security.md")),
                List.of(), List.of());

        AssistantPrompt prompt = assembler.assemble(bundle,
                List.of(new AssistantDtos.HistoryMessage("USER", "password=hunter2")), "怎么实现？");

        assertThat(prompt.systemMessage()).contains("只读研发助手");
        assertThat(prompt.userMessage()).contains("REQ-7", "AC-1", "KB:security:0", "[REDACTED]")
                .doesNotContain("secret", "abc", "hunter2");
    }
    @Test
    void keepsAllRequiredSourcesAndDoesNotWhitelistOptionalSourcesOmittedByBudget() {
        AssistantPromptAssembler assembler = new AssistantPromptAssembler(new PromptTemplateRegistry(), 4096, 24000);
        String largeKnowledge = "x".repeat(10000);
        ContextBuilder.ContextBundle bundle = new ContextBuilder.ContextBundle(
                List.of(new ContextBuilder.KnowledgeSnippet("KB:55",
                        "ghp_12345678901234567890.md", largeKnowledge)),
                0,
                new ContextBuilder.RequirementSnapshot("REQ-7", "REQ-7", "登录", "背景", "描述",
                        "READY", List.of(
                        new ContextBuilder.AcceptanceCriterion("AC-1", 1, "第一条"),
                        new ContextBuilder.AcceptanceCriterion("AC-2", 2, "第二条"))),
                List.of(new ContextBuilder.CodeSlice("CODE:COMMIT:3:1", "COMMIT", "abc",
                        "src/App.java", "MODIFIED", "+change")),
                List.of(new ContextBuilder.Source("REQ-7", "REQUIREMENT", "登录", "REQ-7"),
                        new ContextBuilder.Source("AC-1", "AC", "验收标准 1", "AC-1"),
                        new ContextBuilder.Source("AC-2", "AC", "验收标准 2", "AC-2"),
                        new ContextBuilder.Source("KB:55", "KNOWLEDGE",
                                "ghp_12345678901234567890.md", "secret.md"),
                        new ContextBuilder.Source("CODE:COMMIT:3:1", "CODE", "src/App.java", "abc")),
                List.of(), List.of());

        AssistantPrompt prompt = assembler.assemble(bundle, List.of(), "怎么实现？");

        assertThat(prompt.sourceIds()).contains("REQ-7", "AC-1", "AC-2", "KB:55")
                .doesNotContain("CODE:COMMIT:3:1");
        assertThat(prompt.userMessage()).contains("[AC-1]", "[AC-2]")
                .doesNotContain("ghp_12345678901234567890");
        assertThat(prompt.truncatedSections()).contains("knowledge", "code");
    }

}
