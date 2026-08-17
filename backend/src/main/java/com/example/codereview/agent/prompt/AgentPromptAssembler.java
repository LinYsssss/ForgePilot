package com.example.codereview.agent.prompt;

import com.example.codereview.agent.model.PromptEnvelope;
import com.example.codereview.ai.PromptSanitizer;
import com.example.codereview.context.ReviewContextService;
import com.example.codereview.review.DiffSplitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class AgentPromptAssembler {

    private final PromptTemplateRegistry templates;

    private static final String CHAT_SYSTEM_TEMPLATE = "chat-review-system-v1";
    private static final String CHAT_PROJECT_TEMPLATE = "chat-review-project-v1";
    private static final String CHAT_PROJECT_EMPTY_TEMPLATE = "chat-review-project-empty-v1";
    private static final String CHAT_TASK_TEMPLATE = "chat-review-task-v2";
    private static final String CHAT_CHECKLIST_JAVA_TEMPLATE = "checklist-java-v1";
    private static final String CHAT_CHECKLIST_TS_TEMPLATE = "checklist-ts-v1";
    private static final String CHAT_CHECKLIST_GENERIC_TEMPLATE = "checklist-generic-v1";

    /**
     * r8-R2 frontend-ts 类型键的扩展名族（r2-checklist-evidence.md §3.2）。java 键仅 .java；
     * sql-migration/config 两类语料弹药为零不建清单（建了也永不触发，必进规则四退役候选），
     * 对应扩展名与其余未识别类型一律回落 generic。
     */
    private static final Set<String> FRONTEND_TS_EXTENSIONS = Set.of("vue", "ts", "tsx", "js", "jsx", "mjs");

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
     * 审查纪律）/ 项目层（知识库上下文槽）/ 任务层（清单槽 + JSON Schema + 项目层槽 + diff/分片槽）。
     *
     * <p>r8-R2 类型化清单（首个内容性变更，评测门禁按 prompt 规范规则三在服务器执行）：任务层
     * bump {@code chat-review-task-v2}，原六大类硬编码块换为清单槽，按当前分片 diff 的文件类型
     * 注入定制清单（{@link #chatChecklistVersions(String)}）；无类型命中回落 generic，该路径的
     * 产出与 v1（即重构前内联文本）逐字节相同（golden 测试钉死——R1 字节等价保证在该路径延续）。
     * 清单是任务层指令，不占 RAG 上下文预算、不计入 promptChars 记账（与旧六大类一致，§3.4）。
     * 其余沿用旧行为——不套用信封的打码/截断。分片由 ReviewProcessor 上游完成：任务层按分片
     * 逐次实例化，系统/项目层内容跨分片复用，清单按各分片自身的文件类型逐片选取。
     */
    public ChatReviewPrompt assembleChatReview(String diffText, String ragContext) {
        String knowledge = ragContext == null || ragContext.isBlank()
                ? templates.require(CHAT_PROJECT_EMPTY_TEMPLATE)
                : ragContext;
        String projectLayer = templates.layer(CHAT_PROJECT_TEMPLATE).formatted(knowledge);
        List<String> checklistVersions = chatChecklistVersions(diffText);
        StringBuilder checklistBlock = new StringBuilder();
        for (String version : checklistVersions) {
            if (checklistBlock.length() > 0) {
                checklistBlock.append('\n');
            }
            checklistBlock.append(templates.layer(version));
        }
        String userMessage = templates.layer(CHAT_TASK_TEMPLATE)
                .formatted(checklistBlock.toString(), projectLayer, diffText == null ? "" : diffText);
        return new ChatReviewPrompt(
                templates.layer(CHAT_SYSTEM_TEMPLATE),
                userMessage,
                CHAT_SYSTEM_TEMPLATE,
                CHAT_PROJECT_TEMPLATE,
                CHAT_TASK_TEMPLATE,
                checklistVersions
        );
    }

    /**
     * r8-R2 清单选择（r2-checklist-evidence.md §3.2/§3.3 方案 A）：对当前分片 diff 复用
     * {@link DiffSplitter#splitByFile(String)} 提取路径（不新写 diff 解析），按扩展名映射取
     * 固定顺序并集（java &gt; frontend-ts，与文件在 diff 中的出现顺序无关，保证确定性）；
     * 并集为空（.py/.md/.sql/.yml/未识别扩展名/无文件头的 fallback 分片）回落 generic，
     * 永不返回空列表——对齐「不留空段」的降级原则。
     */
    private List<String> chatChecklistVersions(String diffText) {
        boolean java = false;
        boolean frontendTs = false;
        for (DiffSplitter.FileDiff file : DiffSplitter.splitByFile(diffText)) {
            String extension = extensionOf(file.path());
            if ("java".equals(extension)) {
                java = true;
            } else if (extension != null && FRONTEND_TS_EXTENSIONS.contains(extension)) {
                frontendTs = true;
            }
        }
        List<String> versions = new ArrayList<>();
        if (java) {
            versions.add(CHAT_CHECKLIST_JAVA_TEMPLATE);
        }
        if (frontendTs) {
            versions.add(CHAT_CHECKLIST_TS_TEMPLATE);
        }
        if (versions.isEmpty()) {
            versions.add(CHAT_CHECKLIST_GENERIC_TEMPLATE);
        }
        return List.copyOf(versions);
    }

    /** 取路径末段的扩展名（小写；无 {@code .} 或以 {@code .} 结尾返回 null）。 */
    private static String extensionOf(String path) {
        if (path == null) {
            return null;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * chat 审查提示词：两条消息正文 + 三层模板版本 + 注入的清单模板版本（r8-R2：按分片文件类型
     * 的有序并集，或 generic 回落，永非空）。版本当前记入调用方日志（ai_call_log 列扩展留待
     * 后续内容性变更时一并评估，见 r8 任务遗留项）。
     */
    public record ChatReviewPrompt(
            String systemMessage,
            String userMessage,
            String systemTemplateVersion,
            String projectTemplateVersion,
            String taskTemplateVersion,
            List<String> checklistTemplateVersions
    ) {
    }

    public PromptEnvelope assemble(Input input) {
        Objects.requireNonNull(input, "input");
        List<String> truncated = new ArrayList<>();
        String diff = bounded("changed_diff", PromptSanitizer.redact(input.changedDiff()), input.diffBudget(), truncated);
        String code = bounded("code_context", PromptSanitizer.redact(input.codeContext()), input.codeBudget(), truncated);
        String tool = bounded("tool_evidence", PromptSanitizer.redact(input.toolEvidence()), input.toolBudget(), truncated);
        KnowledgeSection knowledge = knowledge(input.knowledge(), input.ragBudget(), truncated);
        String policy = PromptSanitizer.redact(templates.require(input.promptVersion()));
        String task = PromptSanitizer.redact(input.taskInstruction());
        String schema = PromptSanitizer.redact(input.outputSchema());
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
            int usedBytes = PromptSanitizer.bytes(content.toString());
            int usedTokenChars = content.codePointCount(0, content.length());
            int remainingBytes = budget.maxBytes() - usedBytes - PromptSanitizer.bytes(separator) - PromptSanitizer.bytes(header);
            int remainingTokenChars = budget.maxTokens() * 4 - usedTokenChars
                    - separator.codePointCount(0, separator.length())
                    - header.codePointCount(0, header.length());
            if (remainingBytes <= 0 || remainingTokenChars <= 0) {
                wasTruncated = true;
                break;
            }
            String redacted = PromptSanitizer.redact(item.content());
            String bounded = PromptSanitizer.truncate(redacted, remainingBytes, remainingTokenChars);
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
        String result = PromptSanitizer.truncate(value, budget.maxBytes(), budget.maxTokens() * 4);
        if (!result.equals(value)) {
            truncated.add(section);
        }
        return result;
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
