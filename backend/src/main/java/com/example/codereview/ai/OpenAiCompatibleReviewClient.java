package com.example.codereview.ai;

import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.common.api.ErrorCode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleReviewClient implements AiReviewClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleReviewClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AgentPromptAssembler prompts;
    private final String model;

    public OpenAiCompatibleReviewClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            AgentPromptAssembler prompts,
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
        this.prompts = prompts;
        this.model = model;
    }

    @Override
    @Retry(name = "aiReview")
    @CircuitBreaker(name = "aiReview")
    public AiReviewResult review(String diffText, String ragContext) {
        // r8-R1 分层模板：系统/项目/任务层出自 PromptTemplateRegistry，组装与旧内联文本字节等价
        // （golden 测试钉死）。模板版本先落日志留痕；ai_call_log 列扩展留待内容性变更时一并评估。
        AgentPromptAssembler.ChatReviewPrompt prompt = prompts.assembleChatReview(diffText, ragContext);
        log.info("chat review prompt assembled: system={}, project={}, task={}",
                prompt.systemTemplateVersion(), prompt.projectTemplateVersion(), prompt.taskTemplateVersion());
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", prompt.systemMessage()),
                        Map.of("role", "user", "content", prompt.userMessage())
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
        } catch (Exception ex) {
            // 瞬时/永久的定性收敛在共享决策表(顶层类型分发,保留原 catch 阶梯语义与消息)。
            throw AiTransientFailureClassifier.classifyRestClientFailure(ex, describeFailure(ex));
        }
    }

    private JsonNode readResponseRoot(String responseText) {
        try {
            return objectMapper.readTree(responseText);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "AI 响应不是有效 JSON: " + abbreviate(responseText));
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
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "AI 响应不符合 OpenAI Chat 格式: " + abbreviate(responseText));
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
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "AI 输出解析失败: " + ex.getMessage());
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
            throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "AI 输出为空");
        }
        String trimmed = content.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }
}
