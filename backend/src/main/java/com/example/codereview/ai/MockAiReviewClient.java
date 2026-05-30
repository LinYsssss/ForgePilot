package com.example.codereview.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockAiReviewClient implements AiReviewClient {

    @Override
    public AiReviewResult review(String diffText, String ragContext) {
        String lower = (diffText == null ? "" : diffText).toLowerCase(Locale.ROOT);
        List<AiReviewResult.Issue> issues = new ArrayList<>();

        if (lower.contains("/admin") && !lower.contains("preauthorize") && !lower.contains("hasrole")) {
            issues.add(new AiReviewResult.Issue(
                    "HIGH",
                    "AUTH_RISK",
                    guessFile(diffText),
                    null,
                    null,
                    "管理接口可能缺少权限校验",
                    "Diff 中出现管理接口相关变更，但未发现明确的角色校验注解或权限判断。",
                    "普通用户可能越权访问管理能力。",
                    evidence(ragContext, "security-policy.md"),
                    "为管理接口增加 @PreAuthorize(\"hasRole('ADMIN')\") 或统一权限拦截。",
                    0.84
            ));
        }

        if (lower.contains("select") && (lower.contains("\" +") || lower.contains("+ keyword") || lower.contains("concat"))) {
            issues.add(new AiReviewResult.Issue(
                    "HIGH",
                    "SQL_INJECTION",
                    guessFile(diffText),
                    null,
                    null,
                    "SQL 查询可能存在字符串拼接风险",
                    "Diff 中出现 SQL 和字符串拼接特征，可能导致 SQL 注入。",
                    "攻击者可能通过输入参数拼接恶意 SQL。",
                    "静态特征: SQL + 字符串拼接。",
                    "使用参数绑定，例如 MyBatis #{keyword} 或 JPA 参数化查询。",
                    0.80
            ));
        }

        if ((lower.contains("findbyid") || lower.contains("getbyid")) && lower.contains(".get") && !lower.contains("null")) {
            issues.add(new AiReviewResult.Issue(
                    "MEDIUM",
                    "NULL_POINTER",
                    guessFile(diffText),
                    null,
                    null,
                    "查询结果使用前可能缺少判空",
                    "Diff 中出现按 ID 查询后直接调用对象方法的模式，未发现判空逻辑。",
                    "当数据不存在时可能触发 NullPointerException。",
                    "静态特征: findById/getById 后直接调用 getter。",
                    "使用 Optional、显式判空或抛出业务异常。",
                    0.68
            ));
        }

        if (lower.contains("paystatus") && lower.contains("-") && lower.contains("paid")) {
            issues.add(new AiReviewResult.Issue(
                    "HIGH",
                    "BUSINESS_RULE_RISK",
                    guessFile(diffText),
                    null,
                    null,
                    "发货流程可能删除了支付状态校验",
                    "Diff 中出现支付状态 PAID 校验相关删除，可能破坏订单发货规则。",
                    "可能导致未支付订单被发货。",
                    evidence(ragContext, "order-flow.md"),
                    "恢复发货前 payStatus = PAID 校验，并补充单元测试。",
                    0.90
            ));
        }

        String overallRisk = issues.stream().anyMatch(i -> "HIGH".equals(i.severity())) ? "HIGH"
                : issues.isEmpty() ? "NONE" : "MEDIUM";
        String summary = issues.isEmpty()
                ? "未发现明显高风险问题。"
                : "本次提交发现 " + issues.size() + " 个潜在风险，请结合证据和建议复核。";
        return new AiReviewResult(summary, overallRisk, issues, summary);
    }

    private String guessFile(String diffText) {
        if (diffText == null) {
            return null;
        }
        for (String line : diffText.split("\\R")) {
            if (line.startsWith("+++ b/")) {
                return line.substring("+++ b/".length());
            }
        }
        return null;
    }

    private String evidence(String ragContext, String preferredSource) {
        if (ragContext != null && !ragContext.isBlank()) {
            if (ragContext.contains(preferredSource)) {
                return "RAG 证据: " + preferredSource;
            }
            return "RAG 证据: " + ragContext.lines().findFirst().orElse("项目知识库上下文");
        }
        return "未检索到明确文档证据，建议人工复核。";
    }
}
