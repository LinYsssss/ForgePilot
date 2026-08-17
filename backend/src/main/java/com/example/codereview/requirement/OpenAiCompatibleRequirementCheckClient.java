package com.example.codereview.requirement;

import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.ai.AiTransientFailureClassifier;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.requirement.RequirementCheckDtos.CheckDimension;
import com.example.codereview.requirement.RequirementCheckDtos.LlmCheckResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 体检 LLM 层的 OpenAI 兼容实现(P2)。模板 requirement-check-v1 经唯一组装入口注入
 * 六维/严重度枚举(服务端同源);传输栈与 {@code OpenAiCompatibleReviewClient} 同构,
 * 复用同一 aiReview 重试/熔断实例(同一上游,失败共担)。
 */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleRequirementCheckClient implements RequirementCheckClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleRequirementCheckClient.class);
    private static final String TEMPLATE_VERSION = "requirement-check-v1";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AgentPromptAssembler prompts;
    private final String model;
    private final double temperature;

    public OpenAiCompatibleRequirementCheckClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            AgentPromptAssembler prompts,
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.api-key}") String apiKey,
            @Value("${app.ai.chat-model}") String model,
            @Value("${app.ai.temperature:0.0}") double temperature,
            @Value("${app.http.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${app.ai.read-timeout-ms:300000}") int readTimeoutMs
    ) {
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
        this.temperature = temperature;
    }

    @Override
    @Retry(name = "aiReview")
    @CircuitBreaker(name = "aiReview")
    public LlmCheckResult analyze(String requirementBlock, String knowledgeBlock) {
        String prompt = prompts.instruction(TEMPLATE_VERSION,
                enumList(CheckDimension.values()),
                "HIGH / MEDIUM / LOW",
                requirementBlock,
                knowledgeBlock == null || knowledgeBlock.isBlank() ? "(无)" : knowledgeBlock);
        log.info("requirement check prompt assembled: template={}", TEMPLATE_VERSION);
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", temperature,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        try {
            byte[] response = restClient.post()
                    .uri("/chat/completions")
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM)
                    .body(request)
                    .retrieve()
                    .body(byte[].class);
            String responseText = response == null ? "" : new String(response, StandardCharsets.UTF_8);
            JsonNode root;
            try {
                root = objectMapper.readTree(responseText);
            } catch (Exception ex) {
                throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "AI 响应不是有效 JSON");
            }
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new BusinessException(ErrorCode.AI_RESPONSE_INVALID, "AI 响应不符合 OpenAI Chat 格式");
            }
            int totalTokens = root.path("usage").path("total_tokens").asInt(0);
            return new LlmCheckResult(RequirementCheckParser.parse(objectMapper, content), content, totalTokens);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw AiTransientFailureClassifier.classifyRestClientFailure(ex, ex.getMessage());
        }
    }

    private String enumList(CheckDimension[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(" / "));
    }
}
