package com.example.codereview.agent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 版本化 classpath 提示词模板注册表——分层模板机制的唯一模板来源（r8-R1）。
 *
 * <p>文件约定：每个模板文件头部为以 {@code #} 起始的版本注释块（逐行），注释块与正文之间以
 * 一个空行分隔；加载时去掉头注释与该分隔空行。槽位使用 {@code %s}，由调用方以同源取值注入
 * （校验器数值、枚举清单、服务端事实集合——agent-model-contracts.md 纪律），模板文件内禁止
 * 写死数值/枚举字面量；正文如需字面 {@code %} 必须写作 {@code %%}。
 *
 * <p>两种加载口径：
 * <ul>
 *   <li>{@link #require(String)}：去头注释后再 {@code strip()}，用于单段式政策/任务指令
 *       （首尾空白无语义）；</li>
 *   <li>{@link #layer(String)}：去头注释后逐字节返回，用于 chat 分层模板等首尾换行参与
 *       最终 prompt 字节的场景（r8-R1 字节等价硬验收，golden 测试钉死）。</li>
 * </ul>
 */
@Component
public final class PromptTemplateRegistry {

    private static final Map<String, String> RESOURCES = Map.ofEntries(
            Map.entry("review-v1", "prompts/agent/review-v1.txt"),
            Map.entry("planning-task-v1", "prompts/agent/task/planning-task-v1.txt"),
            Map.entry("executing-tools-receipt-task-v1",
                    "prompts/agent/task/executing-tools-receipt-task-v1.txt"),
            Map.entry("verifying-findings-task-v1",
                    "prompts/agent/task/verifying-findings-task-v1.txt"),
            Map.entry("verifying-findings-citation-required-v1",
                    "prompts/agent/task/verifying-findings-citation-required-v1.txt"),
            Map.entry("verifying-findings-citation-empty-v1",
                    "prompts/agent/task/verifying-findings-citation-empty-v1.txt"),
            Map.entry("generating-patch-task-v1", "prompts/agent/task/generating-patch-task-v1.txt"),
            Map.entry("chat-review-system-v1", "prompts/chat/review-system-v1.txt"),
            Map.entry("chat-review-project-v1", "prompts/chat/review-project-v1.txt"),
            Map.entry("chat-review-project-empty-v1", "prompts/chat/review-project-empty-v1.txt"),
            Map.entry("chat-review-task-v1", "prompts/chat/review-task-v1.txt"),
            // r8-R2 类型化清单：任务层 bump v2（清单槽），v1 是行为资产原样保留（防不可比）。
            Map.entry("chat-review-task-v2", "prompts/chat/review-task-v2.txt"),
            Map.entry("checklist-java-v1", "prompts/chat/checklist-java-v1.txt"),
            Map.entry("checklist-ts-v1", "prompts/chat/checklist-ts-v1.txt"),
            Map.entry("checklist-generic-v1", "prompts/chat/checklist-generic-v1.txt"),
            // ForgePilot P2:需求体检场景(REQUIREMENT_CHECK),经 instruction() 单段组装。
            Map.entry("requirement-check-v1", "prompts/chat/requirement-check-v1.txt"),
            // ForgePilot P4a:AC 覆盖判定(合并阶段单独一次调用),经 instruction() 单段组装。
            Map.entry("coverage-judge-v1", "prompts/chat/coverage-judge-v1.txt"),
            // ForgePilot P6:只读研发助手流式文本回答。
            Map.entry("assistant-v1", "prompts/chat/assistant-v1.txt")
    );

    public String require(String version) {
        return load(version).strip();
    }

    /** 去头注释后逐字节返回正文（含首尾换行），供分层组装按结构字节拼接。 */
    public String layer(String version) {
        return load(version);
    }

    private String load(String version) {
        String path = RESOURCES.get(version);
        if (path == null) {
            throw new IllegalArgumentException("unknown prompt version");
        }
        try {
            String content = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            return stripHeaderComment(content);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load prompt template " + version, ex);
        }
    }

    /** 去掉文件头部的 {@code #} 注释块与其后的一个空行分隔符，其余内容逐字节保留。 */
    private static String stripHeaderComment(String content) {
        String remaining = content;
        boolean hadHeader = false;
        while (remaining.startsWith("#")) {
            hadHeader = true;
            int newline = remaining.indexOf('\n');
            if (newline < 0) {
                return "";
            }
            remaining = remaining.substring(newline + 1);
        }
        if (hadHeader && remaining.startsWith("\n")) {
            remaining = remaining.substring(1);
        }
        return remaining;
    }
}
