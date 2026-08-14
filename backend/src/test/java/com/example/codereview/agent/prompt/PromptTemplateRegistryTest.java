package com.example.codereview.agent.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * r8-R1 分层模板注册表的组装前提:层缺失快速失败、所有注册键可加载、头注释不泄漏进正文、
 * 结构性换行(layer 口径)保真。字节级等价由 golden 测试
 * ({@link StepInstructionGoldenTest}、{@link ChatReviewPromptGoldenTest})钉死。
 */
class PromptTemplateRegistryTest {

    /** require() 口径(strip)的模板:政策层 + 各步骤任务指令与分支片段。 */
    private static final List<String> INSTRUCTION_TEMPLATES = List.of(
            "review-v1",
            "planning-task-v1",
            "executing-tools-receipt-task-v1",
            "verifying-findings-task-v1",
            "verifying-findings-citation-required-v1",
            "verifying-findings-citation-empty-v1",
            "generating-patch-task-v1",
            "chat-review-project-empty-v1"
    );

    /** layer() 口径(逐字节)的模板:chat 三层(任务层 v1/v2 双版本在册)+ r8-R2 清单槽模板。 */
    private static final List<String> LAYER_TEMPLATES = List.of(
            "chat-review-system-v1",
            "chat-review-project-v1",
            "chat-review-task-v1",
            "chat-review-task-v2",
            "checklist-java-v1",
            "checklist-ts-v1",
            "checklist-generic-v1"
    );

    private final PromptTemplateRegistry registry = new PromptTemplateRegistry();

    @Test
    void missingLayerFailsFastOnBothLoadPaths() {
        assertThatThrownBy(() -> registry.require("no-such-template-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown prompt version");
        assertThatThrownBy(() -> registry.layer("no-such-template-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown prompt version");
    }

    @Test
    void everyRegisteredTemplateLoadsWithHeaderCommentStripped() {
        for (String version : INSTRUCTION_TEMPLATES) {
            assertThat(registry.require(version))
                    .as("instruction template %s", version)
                    .isNotBlank()
                    .doesNotStartWith("#");
        }
        for (String version : LAYER_TEMPLATES) {
            assertThat(registry.layer(version))
                    .as("layer template %s", version)
                    .isNotBlank()
                    .doesNotStartWith("#");
        }
    }

    @Test
    void layerLoadingPreservesStructuralTrailingNewlines() {
        // chat 层与清单槽模板的末尾换行是最终 prompt 的结构字节(字节等价/拼接约定),strip 类归一化禁止。
        assertThat(registry.layer("chat-review-system-v1")).endsWith("。\n");
        assertThat(registry.layer("chat-review-project-v1")).endsWith("%s\n");
        assertThat(registry.layer("chat-review-task-v1")).endsWith("%s\n");
        assertThat(registry.layer("chat-review-task-v2")).endsWith("%s\n");
        assertThat(registry.layer("checklist-java-v1")).endsWith("线索\n");
        assertThat(registry.layer("checklist-ts-v1")).endsWith("链式访问\n");
        assertThat(registry.layer("checklist-generic-v1")).endsWith("业务规则破坏\n");
    }

    @Test
    void instructionLoadingNormalizesEdgeWhitespace() {
        for (String version : INSTRUCTION_TEMPLATES) {
            String content = registry.require(version);
            assertThat(content).as("instruction template %s", version)
                    .isEqualTo(content.strip());
        }
    }
}
