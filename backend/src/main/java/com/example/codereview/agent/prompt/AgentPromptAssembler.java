package com.example.codereview.agent.prompt;

import com.example.codereview.agent.model.PromptEnvelope;
import com.example.codereview.context.ReviewContextService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class AgentPromptAssembler {

    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----"
    );
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)Authorization\\s*:\\s*(?:Bearer|Basic)\\s+[^\\s]+"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b([A-Z0-9_]*(?:TOKEN|PASSWORD|SECRET|API_KEY|ACCESS_KEY)[A-Z0-9_]*)"
                    + "\\s*[:=]\\s*([^\\s,;]+)"
    );
    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b");

    private final PromptTemplateRegistry templates;

    private static final String CHAT_SYSTEM_TEMPLATE = "chat-review-system-v1";
    private static final String CHAT_PROJECT_TEMPLATE = "chat-review-project-v1";
    private static final String CHAT_PROJECT_EMPTY_TEMPLATE = "chat-review-project-empty-v1";
    private static final String CHAT_TASK_TEMPLATE = "chat-review-task-v1";

    public AgentPromptAssembler(PromptTemplateRegistry templates) {
        this.templates = templates;
    }

    /**
     * 从注册表取任务指令模板并按 {@code %s} 槽注入取值（r8-R1：步骤执行器内联拼接清零）。
     * 数值/枚举等约束值必须由调用方从校验器/枚举同源传入（agent-model-contracts.md 纪律），
     * 模板文件内不落字面量。
     */
    public String instruction(String templateVersion, Object... values) {
        return templates.require(templateVersion).formatted(values);
    }

    /**
     * chat 审查路径（OpenAiCompatibleReviewClient）的三层组装：系统层（角色 + JSON 输出契约 +
     * 审查纪律）/ 项目层（知识库上下文槽）/ 任务层（审查任务 + diff/分片槽）。
     *
     * <p>r8-R1 为严格字节等价搬迁：产出与旧 systemPrompt()/buildPrompt() 内联文本逐字节相同
     * （golden 测试钉死），因此本路径沿用旧行为——不套用信封的打码/截断（内容性变更留待后续
     * 步骤在评测门禁下进行）。分片由 ReviewProcessor 上游完成：任务层按分片逐次实例化，
     * 系统/项目层内容跨分片复用。
     */
    public ChatReviewPrompt assembleChatReview(String diffText, String ragContext) {
        String knowledge = ragContext == null || ragContext.isBlank()
                ? templates.require(CHAT_PROJECT_EMPTY_TEMPLATE)
                : ragContext;
        String projectLayer = templates.layer(CHAT_PROJECT_TEMPLATE).formatted(knowledge);
        String userMessage = templates.layer(CHAT_TASK_TEMPLATE)
                .formatted(projectLayer, diffText == null ? "" : diffText);
        return new ChatReviewPrompt(
                templates.layer(CHAT_SYSTEM_TEMPLATE),
                userMessage,
                CHAT_SYSTEM_TEMPLATE,
                CHAT_PROJECT_TEMPLATE,
                CHAT_TASK_TEMPLATE
        );
    }

    /**
     * chat 审查提示词：两条消息正文 + 三层模板版本。版本当前记入调用方日志
     * （ai_call_log 列扩展留待后续内容性变更时一并评估，见 r8 任务遗留项）。
     */
    public record ChatReviewPrompt(
            String systemMessage,
            String userMessage,
            String systemTemplateVersion,
            String projectTemplateVersion,
            String taskTemplateVersion
    ) {
    }

    public PromptEnvelope assemble(Input input) {
        Objects.requireNonNull(input, "input");
        List<String> truncated = new ArrayList<>();
        String diff = bounded("changed_diff", redact(input.changedDiff()), input.diffBudget(), truncated);
        String code = bounded("code_context", redact(input.codeContext()), input.codeBudget(), truncated);
        String tool = bounded("tool_evidence", redact(input.toolEvidence()), input.toolBudget(), truncated);
        KnowledgeSection knowledge = knowledge(input.knowledge(), input.ragBudget(), truncated);
        String policy = redact(templates.require(input.promptVersion()));
        String task = redact(input.taskInstruction());
        String schema = redact(input.outputSchema());
        return new PromptEnvelope(
                policy,
                task,
                diff,
                code,
                tool,
                knowledge.content(),
                schema,
                input.promptVersion(),
                null,
                input.schemaVersion(),
                truncated,
                knowledge.citationIds()
        );
    }

    private KnowledgeSection knowledge(
            List<ReviewContextService.ContextEvidence> evidence,
            SectionBudget budget,
            List<String> truncated
    ) {
        StringBuilder content = new StringBuilder();
        List<String> citations = new ArrayList<>();
        boolean wasTruncated = false;
        for (ReviewContextService.ContextEvidence item : evidence) {
            String separator = content.isEmpty() ? "" : "\n\n";
            String header = "[citation:" + item.reference() + "]\n";
            int usedBytes = bytes(content.toString());
            int usedTokenChars = content.codePointCount(0, content.length());
            int remainingBytes = budget.maxBytes() - usedBytes - bytes(separator) - bytes(header);
            int remainingTokenChars = budget.maxTokens() * 4 - usedTokenChars
                    - separator.codePointCount(0, separator.length())
                    - header.codePointCount(0, header.length());
            if (remainingBytes <= 0 || remainingTokenChars <= 0) {
                wasTruncated = true;
                break;
            }
            String redacted = redact(item.content());
            String bounded = truncate(redacted, remainingBytes, remainingTokenChars);
            content.append(separator).append(header).append(bounded);
            citations.add(item.reference());
            if (!bounded.equals(redacted)) {
                wasTruncated = true;
                break;
            }
        }
        if (wasTruncated) {
            truncated.add("retrieved_knowledge");
        }
        return new KnowledgeSection(content.toString(), List.copyOf(citations));
    }

    private String bounded(
            String section,
            String value,
            SectionBudget budget,
            List<String> truncated
    ) {
        String result = truncate(value, budget.maxBytes(), budget.maxTokens() * 4);
        if (!result.equals(value)) {
            truncated.add(section);
        }
        return result;
    }

    private String truncate(String value, int maxBytes, int maxCodePoints) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        int points = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String next = new String(Character.toChars(codePoint));
            int nextBytes = bytes(next);
            if (bytes + nextBytes > maxBytes || points + 1 > maxCodePoints) {
                break;
            }
            result.append(next);
            bytes += nextBytes;
            points++;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private String redact(String value) {
        String result = value == null ? "" : value;
        result = PRIVATE_KEY.matcher(result).replaceAll("[REDACTED]");
        result = AUTHORIZATION.matcher(result).replaceAll("Authorization: [REDACTED]");
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1=[REDACTED]");
        return GITHUB_TOKEN.matcher(result).replaceAll("[REDACTED]");
    }

    private int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public record SectionBudget(int maxBytes, int maxTokens) {
        public SectionBudget {
            if (maxBytes <= 0 || maxTokens <= 0) {
                throw new IllegalArgumentException("section budgets must be positive");
            }
        }
    }

    public record Input(
            String promptVersion,
            String taskInstruction,
            String changedDiff,
            String codeContext,
            String toolEvidence,
            List<ReviewContextService.ContextEvidence> knowledge,
            String outputSchema,
            String schemaVersion,
            SectionBudget diffBudget,
            SectionBudget codeBudget,
            SectionBudget toolBudget,
            SectionBudget ragBudget
    ) {
        public Input {
            promptVersion = requireText(promptVersion, "promptVersion");
            taskInstruction = Objects.requireNonNull(taskInstruction, "taskInstruction");
            changedDiff = Objects.requireNonNull(changedDiff, "changedDiff");
            codeContext = Objects.requireNonNull(codeContext, "codeContext");
            toolEvidence = Objects.requireNonNull(toolEvidence, "toolEvidence");
            knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
            outputSchema = requireText(outputSchema, "outputSchema");
            schemaVersion = requireText(schemaVersion, "schemaVersion");
            Objects.requireNonNull(diffBudget, "diffBudget");
            Objects.requireNonNull(codeBudget, "codeBudget");
            Objects.requireNonNull(toolBudget, "toolBudget");
            Objects.requireNonNull(ragBudget, "ragBudget");
        }
    }

    private record KnowledgeSection(String content, List<String> citationIds) {
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
