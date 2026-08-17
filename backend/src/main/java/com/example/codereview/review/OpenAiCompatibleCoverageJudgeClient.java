package com.example.codereview.review;

import com.example.codereview.agent.prompt.AgentPromptAssembler;
import com.example.codereview.ai.AiTransientFailureClassifier;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.review.CoverageDtos.AcRef;
import com.example.codereview.review.CoverageDtos.CoverageInput;
import com.example.codereview.review.CoverageDtos.CoverageResult;
import com.example.codereview.review.CoverageDtos.CoverageVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 覆盖判定的 OpenAI 兼容实现(P4a)。模板 coverage-judge-v1 经唯一组装入口注入 verdict 枚举;
 * 传输栈与其余 chat 客户端同构,复用 aiReview 重试/熔断实例。
 */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleCoverageJudgeClient implements CoverageJudgeClient {

    private static final String TEMPLATE_VERSION = "coverage-judge-v1";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AgentPromptAssembler prompts;
    private final String model;
    private final double temperature;

    public OpenAiCompatibleCoverageJudgeClient(
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
    public CoverageResult judge(CoverageInput input, String shardSummaries, String diffExcerpt) {
        String verdicts = Arrays.stream(CoverageVerdict.values())
                .map(Enum::name).collect(Collectors.joining(" / "));
        String requirementBlock = input.requirementCode() + " " + input.requirementTitle()
                + "\n" + (input.requirementDescription() == null ? "" : input.requirementDescription());
        String acList = input.acs().stream()
                .map(ac -> ac.acId() + ": " + ac.text())
                .collect(Collectors.joining("\n"));
        String prompt = prompts.instruction(TEMPLATE_VERSION,
                verdicts, requirementBlock, acList,
                shardSummaries == null || shardSummaries.isBlank() ? "(无)" : shardSummaries,
                diffExcerpt == null ? "" : diffExcerpt);
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
            return new CoverageResult(CoverageJudgeParser.parse(objectMapper, input, content), content, totalTokens);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw AiTransientFailureClassifier.classifyRestClientFailure(ex, ex.getMessage());
        }
    }
}
