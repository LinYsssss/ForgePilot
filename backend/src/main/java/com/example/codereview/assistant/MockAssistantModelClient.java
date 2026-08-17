package com.example.codereview.assistant;

import com.example.codereview.ai.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockAssistantModelClient implements AssistantModelClient {

    private static final Pattern SOURCE = Pattern.compile("(?:REQ-\\d+|AC-\\d+|KB:[A-Za-z0-9._:/-]+|CODE:[A-Za-z0-9._:/-]+)");

    @Override
    public TokenUsage stream(AssistantPrompt prompt, Consumer<String> onDelta, BooleanSupplier cancelled) {
        List<String> sources = new ArrayList<>();
        Matcher matcher = SOURCE.matcher(prompt.userMessage());
        while (matcher.find() && sources.size() < 3) {
            String source = matcher.group();
            if (!sources.contains(source)) {
                sources.add(source);
            }
        }
        String primary = sources.isEmpty() ? "需求上下文" : "[" + sources.get(0) + "]";
        String secondary = sources.size() < 2 ? primary : "[" + sources.get(1) + "]";
        List<String> chunks = List.of(
                "建议先把实现拆成可验证的最小路径。",
                "首先以 " + primary + " 明确状态、输入与边界；",
                "再逐条用 " + secondary + " 建立测试，优先覆盖失败和降级分支。",
                "当前助手是只读的，不会修改仓库；若代码来源不足，请先补充关联 commit 或 PR。"
        );
        for (String chunk : chunks) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            onDelta.accept(chunk);
        }
        return TokenUsage.none();
    }
}
