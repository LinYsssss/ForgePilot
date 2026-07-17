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

    public AgentPromptAssembler(PromptTemplateRegistry templates) {
        this.templates = templates;
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
