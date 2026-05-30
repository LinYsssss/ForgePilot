package com.example.codereview.ai;

import com.example.codereview.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleReviewClient implements AiReviewClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public OpenAiCompatibleReviewClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.api-key}") String apiKey,
            @Value("${app.ai.chat-model}") String model
    ) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AI_PROVIDER=openai-compatible requires LLM_BASE_URL and LLM_API_KEY");
        }
        this.restClient = restClientBuilder
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
    }

    @Override
    public AiReviewResult review(String diffText, String ragContext) {
        String prompt = buildPrompt(diffText, ragContext);
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt()),
                        Map.of("role", "user", "content", prompt)
                )
        );
        try {
            String response = restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .retrieve()
                    .body(String.class);
            String content = objectMapper.readTree(response)
                    .path("choices").path(0).path("message").path("content").asText();
            return parseContent(content);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(6004, "AI 调用失败: " + ex.getMessage());
        }
    }

    private AiReviewResult parseContent(String content) {
        try {
            String json = extractJson(content);
            JsonNode root = objectMapper.readTree(json);
            List<AiReviewResult.Issue> issues = new ArrayList<>();
            for (JsonNode node : root.path("issues")) {
                issues.add(new AiReviewResult.Issue(
                        node.path("severity").asText("LOW"),
                        node.path("category").asText("UNKNOWN"),
                        node.path("filePath").isMissingNode() ? null : node.path("filePath").asText(null),
                        node.path("lineStart").isNumber() ? node.path("lineStart").asInt() : null,
                        node.path("lineEnd").isNumber() ? node.path("lineEnd").asInt() : null,
                        node.path("title").asText("未命名问题"),
                        node.path("description").asText(""),
                        node.path("impact").asText(""),
                        serializeEvidence(node.path("evidenceSources")),
                        node.path("suggestion").asText(""),
                        node.path("confidence").isNumber() ? node.path("confidence").asDouble() : 0.5
                ));
            }
            return new AiReviewResult(
                    root.path("summary").asText("AI 审查完成"),
                    root.path("overallRisk").asText(issues.isEmpty() ? "NONE" : "MEDIUM"),
                    issues,
                    content
            );
        } catch (Exception ex) {
            throw new BusinessException(6005, "AI 输出解析失败: " + ex.getMessage());
        }
    }

    private String serializeEvidence(JsonNode evidenceSources) {
        if (evidenceSources == null || evidenceSources.isMissingNode() || evidenceSources.isNull()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(evidenceSources);
        } catch (Exception ex) {
            return evidenceSources.toString();
        }
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(6005, "AI 输出为空");
        }
        String trimmed = content.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    private String systemPrompt() {
        return """
                你是资深 Java 代码审查专家。
                你必须基于 Git Diff、静态分析结果和 RAG 项目上下文进行审查。
                你不能编造不存在的文件、行号或证据。
                你只能输出 JSON，不要输出 Markdown。
                """;
    }

    private String buildPrompt(String diffText, String ragContext) {
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
