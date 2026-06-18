package com.example.codereview.ai;

import com.example.codereview.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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
            @Value("${app.ai.chat-model}") String model,
            @Value("${app.http.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${app.ai.read-timeout-ms:300000}") int readTimeoutMs
    ) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AI_PROVIDER=openai-compatible requires LLM_BASE_URL and LLM_API_KEY");
        }
        // Chat review prompts are large and reasoning models are slow, so this call needs a far
        // longer read timeout than the shared 60s factory. Override the request factory for this
        // client only; embedding / model-risk clients keep the default timeout.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restClient = restClientBuilder
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
        this.model = model;
    }

    @Override
    @Retry(name = "aiReview")
    @CircuitBreaker(name = "aiReview")
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
            byte[] response = restClient.post()
                    .uri("/chat/completions")
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM)
                    .body(request)
                    .retrieve()
                    .body(byte[].class);
            String responseText = response == null ? "" : new String(response, StandardCharsets.UTF_8);
            JsonNode root = readResponseRoot(responseText);
            String content = extractMessageContent(root, responseText);
            return parseContent(content, extractUsage(root));
        } catch (BusinessException ex) {
            throw ex;
        } catch (HttpClientErrorException ex) {
            // 4xx is a deterministic client problem (bad key/request) — retrying won't help.
            // 429 is the exception: it is a transient rate-limit, so let it retry / trip the breaker.
            if (ex.getStatusCode().value() == 429) {
                throw new AiCallTransientException("AI 调用被限流(429): " + describeFailure(ex), ex);
            }
            throw new BusinessException(6004, "AI 调用失败: " + describeFailure(ex));
        } catch (HttpServerErrorException ex) {
            throw new AiCallTransientException("AI 服务端错误(5xx): " + describeFailure(ex), ex);
        } catch (ResourceAccessException ex) {
            // Connect/read timeout or connection reset — transient, worth retrying.
            throw new AiCallTransientException("AI 调用网络异常: " + describeFailure(ex), ex);
        } catch (Exception ex) {
            throw new BusinessException(6004, "AI 调用失败: " + describeFailure(ex));
        }
    }

    private JsonNode readResponseRoot(String responseText) {
        try {
            return objectMapper.readTree(responseText);
        } catch (Exception ex) {
            throw new BusinessException(6004, "AI 响应不是有效 JSON: " + abbreviate(responseText));
        }
    }

    /** Reads OpenAI-compatible {@code usage} so we can record real token cost, not just char counts. */
    private TokenUsage extractUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return TokenUsage.none();
        }
        return new TokenUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0)
        );
    }

    /**
     * Flattens the exception cause chain into one message. {@code RestClientException.getMessage()}
     * only carries "Error while extracting response for type [...]"; the real cause (e.g.
     * {@code SocketTimeoutException: Read timed out} or a connection reset) lives in {@code getCause()}
     * and was previously dropped, making failures look like an opaque content-type problem.
     */
    private String describeFailure(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        boolean readTimeout = false;
        Throwable current = ex;
        Throwable previous = null;
        while (current != null && current != previous) {
            if (sb.length() > 0) {
                sb.append(" | caused by ");
            }
            sb.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                sb.append(": ").append(current.getMessage());
            }
            if (current instanceof SocketTimeoutException) {
                readTimeout = true;
            }
            previous = current;
            current = current.getCause();
        }
        if (readTimeout) {
            sb.append(" —— 读超时，请增大 AI_READ_TIMEOUT_MS 或减小 diff / RAG 上下文");
        }
        return sb.toString();
    }

    private String extractMessageContent(JsonNode root, String responseText) {
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (content == null || content.isBlank()) {
            throw new BusinessException(6004, "AI 响应不符合 OpenAI Chat 格式: " + abbreviate(responseText));
        }
        return content;
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private AiReviewResult parseContent(String content, TokenUsage usage) {
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
                    content,
                    usage
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
