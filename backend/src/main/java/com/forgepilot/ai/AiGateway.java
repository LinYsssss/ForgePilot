package com.forgepilot.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.forgepilot.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 通往 OpenAI 兼容 provider 的唯一技术入口（ARCHITECTURE.md 4.1）。它负责 HTTP、
 * 鉴权、超时、那**一次**有界重试以及调用记录——并且不认识任何业务类型：一次调用
 * 属于哪个需求或哪次审查，是以不透明 id 的形式经 {@link AiCallContext} 传入的，
 * 因此 {@code ai} 仍然只依赖 {@code common}（ARCHITECTURE.md 1.3）。业务 Prompt
 * 归各自的功能模块所有；这里没有 Prompt 注册表，也没有通用上下文构造器
 * （ARCHITECTURE.md 4）。
 *
 * <p>chat 的 URI 来自 {@code forgepilot.ai.base-url}；embedding 可以有自己的
 * {@code embedding-base-url} 与凭据，未配置时回落到 chat 的路由。代码中不硬编码
 * 任何 host（D015.8）。这使得**一个**技术网关就能接到两个 OpenAI 兼容 provider，
 * 而不必新建第二个 AI runtime，也不会把 provider 路由逻辑泄漏进业务服务。
 *
 * <p>未配置的部署在**调用时**失败，而不是在启动时失败：批次 2 不应该因为某些
 * 还没人配置的功能而阻止应用启动；而给一个默认 host 或默认密钥，恰恰就是
 * {@code quality-guidelines.md} 明令禁止的“兜底凭据”。
 */
@Service
public class AiGateway {

    private static final String CHAT_PATH = "/chat/completions";
    private static final String EMBEDDING_PATH = "/embeddings";

    private final AiCallLogRepository callLogs;
    private final ObjectMapper json;
    private final TransactionTemplate ownTransaction;
    private final HttpClient http;
    private final ProviderRoute chatRoute;
    private final ProviderRoute embeddingRoute;
    private final String chatModel;
    private final Duration timeout;
    private final int promptCharBudget;

    AiGateway(AiCallLogRepository callLogs, ObjectMapper json, PlatformTransactionManager transactions,
            @Value("${forgepilot.ai.base-url:}") String baseUrl,
            @Value("${forgepilot.ai.api-key:}") String apiKey,
            @Value("${forgepilot.ai.embedding-base-url:${forgepilot.ai.base-url:}}") String embeddingBaseUrl,
            @Value("${forgepilot.ai.embedding-api-key:${forgepilot.ai.api-key:}}") String embeddingApiKey,
            @Value("${forgepilot.ai.chat-model:}") String chatModel,
            // ARCHITECTURE.md 7.2：“LLM 单次调用超时 | 120 s”。
            @Value("${forgepilot.ai.timeout:120s}") Duration timeout,
            // 7.2 为构造审查 Prompt 的分批器规定了唯一的字符预算 60000。
            // 这里复用该数字而不是另造一个；权威文档中再没有第二处以字符为
            // 单位约束载荷大小。
            @Value("${forgepilot.ai.prompt-char-budget:60000}") int promptCharBudget) {
        this.callLogs = callLogs;
        this.json = json;
        this.chatRoute = new ProviderRoute(baseUrl, apiKey);
        this.embeddingRoute = new ProviderRoute(embeddingBaseUrl, embeddingApiKey);
        this.chatModel = chatModel;
        this.timeout = timeout;
        this.promptCharBudget = promptCharBudget;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 一次补全。{@code schema} 是调用方为结构化回答提供的 JSON Schema；
     * 传 {@code null} 仍可用于明确要求自由文本的调用。Quality、Guidance 与 Review
     * 共享本网关，各自拥有业务侧 schema（ARCHITECTURE.md 4.1）。
     */
    public String chat(String prompt, String schema, AiUseCase useCase, AiCallContext context) {
        if (chatModel.isBlank()) {
            throw unconfigured();
        }
        ObjectNode request = json.createObjectNode();
        request.put("model", chatModel);
        request.putArray("messages").addObject()
                .put("role", "user")
                .put("content", PromptSanitizer.sanitize(prompt, promptCharBudget));
        if (schema != null) {
            ObjectNode format = request.putObject("response_format");
            format.put("type", "json_schema");
            format.putObject("json_schema").put("name", "result").put("strict", true)
                    .set("schema", parseSchema(schema));
        }
        return call(chatRoute, CHAT_PATH, request, chatModel, useCase, context, AiGateway::content);
    }

    /**
     * 每段文本一个向量，顺序与传入一致。模型由调用方指定，因为 embedding 档案
     * 是与记录它的行存在一起的（{@code knowledge_chunk.provider/model/version/dimension}）；
     * {@code ai} 只负责把它带给 provider 并写进调用记录。
     */
    public List<float[]> embed(List<String> texts, String model, AiCallContext context) {
        ObjectNode request = json.createObjectNode();
        request.put("model", model);
        ArrayNode input = request.putArray("input");
        for (String text : texts) {
            input.add(PromptSanitizer.sanitize(text, promptCharBudget));
        }
        return call(embeddingRoute, EMBEDDING_PATH, request, model, AiUseCase.EMBEDDING, context,
                answer -> vectors(answer, texts.size()));
    }

    /**
     * 发出请求；遇到瞬时失败时**恰好**再发一次。每次尝试都会在本方法作出任何
     * 判断之前先写入自己的 {@code ai_call_log} 行，因此重试过的调用表现为两行、
     * 永久失败表现为一行——重试是可审计的事实，而不是一句声明。
     */
    private <T> T call(ProviderRoute route, String path, ObjectNode request, String model, AiUseCase useCase,
            AiCallContext context, Function<JsonNode, T> reader) {
        HttpRequest post = post(endpoint(route.baseUrl(), path), route.apiKey(), request.toString());
        for (int attempt = 1; attempt <= AiFailurePolicy.MAX_ATTEMPTS; attempt++) {
            long startedAt = System.nanoTime();
            try {
                HttpResponse<String> response = http.send(post,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() / 100 == 2) {
                    JsonNode answer = json.readTree(response.body());
                    T value = reader.apply(answer);
                    recordAttempt(AiCallLog.success(context, useCase, model, elapsedMs(startedAt),
                            token(answer, "prompt_tokens"), token(answer, "completion_tokens"),
                            token(answer, "total_tokens")));
                    return value;
                }
                recordAttempt(failed(context, useCase, model, startedAt, "HTTP " + response.statusCode()));
                if (!AiFailurePolicy.isTransient(response.statusCode())) {
                    throw unavailable();
                }
            } catch (HttpTimeoutException noAnswer) {
                // 7.2：“超时按瞬时失败处理”，因此这里落到重试分支——
                // 但状态记为 TIMEOUT，因为对日后查日志的人来说，
                // “答得不对”和“根本没答”是两个不同的事实。
                recordAttempt(AiCallLog.failure(context, useCase, model, elapsedMs(startedAt),
                        AiCallStatus.TIMEOUT, "no answer within " + timeout));
            } catch (JacksonException notJson) {
                // 解析不了的响应体，再问一遍也修不好，而且绝不能变成一个
                // 静默的空回答。ARCHITECTURE.md 3.5 允许的那一次格式修复是
                // 另一笔预算、归 review 所有；把两者合并会悄悄允许四次调用。
                recordAttempt(failed(context, useCase, model, startedAt, "the answer was not JSON"));
                throw unavailable();
            } catch (MalformedAnswerException malformed) {
                recordAttempt(failed(context, useCase, model, startedAt, malformed.getMessage()));
                throw unavailable();
            } catch (IOException transport) {
                // 7.2 所说的“网络”类失败：连接被拒、被重置，或应答中途断开。
                recordAttempt(failed(context, useCase, model, startedAt,
                        "transport failure: " + transport.getClass().getSimpleName()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recordAttempt(failed(context, useCase, model, startedAt, "the calling thread was interrupted"));
                throw unavailable();
            }
        }
        throw unavailable();
    }

    private HttpRequest post(URI endpoint, String apiKey, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "ForgePilot/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) {
            // 本代码库任何地方都没有默认密钥。未配置的部署不发这个 header，
            // provider 自己返回的 401 就是最诚实的答案——这也是测试无需
            // 任何凭据就能跑通的原因。
            request.header("Authorization", "Bearer " + apiKey);
        }
        return request.build();
    }

    private static URI endpoint(String baseUrl, String path) {
        if (baseUrl.isBlank()) {
            throw unconfigured();
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(base + path);
    }

    /**
     * 每次尝试都落在自己**独立的**事务里。这张表的用途是“故障定位”
     * （ARCHITECTURE.md 2.1），而一旦调用方回滚——审查失败、入库失败——
     * 就会连带抹掉“为什么失败”的记录。代价是：调用方不得已持有它在这里
     * 所引用的那个需求行的行锁，否则本次插入的外键检查会去等待那个
     * 正在等待自己的事务。
     */
    private void recordAttempt(AiCallLog attempt) {
        ownTransaction.executeWithoutResult(status -> callLogs.save(attempt));
    }

    private static AiCallLog failed(AiCallContext context, AiUseCase useCase, String model,
            long startedAt, String error) {
        return AiCallLog.failure(context, useCase, model, elapsedMs(startedAt), AiCallStatus.FAILED, error);
    }

    private static int elapsedMs(long startedAt) {
        return (int) ((System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static String content(JsonNode answer) {
        JsonNode content = answer.path("choices").path(0).path("message").path("content");
        if (!content.isString()) {
            throw new MalformedAnswerException("the answer carried no message content");
        }
        return content.stringValue();
    }

    private static List<float[]> vectors(JsonNode answer, int expected) {
        JsonNode data = answer.path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new MalformedAnswerException("the answer carried " + data.size()
                    + " embeddings for " + expected + " inputs");
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode item : data) {
            vectors.add(vector(item.path("embedding")));
        }
        return vectors;
    }

    private static float[] vector(JsonNode embedding) {
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new MalformedAnswerException("the answer carried an empty embedding");
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < vector.length; i++) {
            JsonNode component = embedding.path(i);
            if (!component.isNumber()) {
                // 照读不误的话会得到 0.0，以及一个看上去毫无问题的向量。
                throw new MalformedAnswerException("the answer carried a non-numeric embedding component");
            }
            vector[i] = (float) component.doubleValue();
        }
        return vector;
    }

    private static Integer token(JsonNode answer, String field) {
        JsonNode count = answer.path("usage").path(field);
        return count.isIntegralNumber() ? count.intValue() : null;
    }

    private JsonNode parseSchema(String schema) {
        try {
            return json.readTree(schema);
        } catch (JacksonException notJson) {
            // 这个 schema 是调用方自己的常量，不是用户输入。
            throw new IllegalArgumentException("The response schema is not valid JSON.", notJson);
        }
    }

    /**
     * 有意不透露请求与回答的任何内容：5xx 会走到 {@code ApiExceptionHandler}，
     * 后者会连同堆栈把本异常写进日志，而 Prompt 或响应载荷绝不能出现在那里。
     */
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable",
                "The AI provider call did not succeed.");
    }

    private static ApiException unconfigured() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_unconfigured",
                "The AI provider is not configured.");
    }

    /** 2xx 但响应体里没有所要的内容。从不重试，也绝不静默当成空结果。 */
    private static final class MalformedAnswerException extends RuntimeException {

        private MalformedAnswerException(String message) {
            super(message);
        }
    }

    private record ProviderRoute(String baseUrl, String apiKey) {

        private ProviderRoute {
            baseUrl = baseUrl == null ? "" : baseUrl;
            apiKey = apiKey == null ? "" : apiKey;
        }
    }
}
