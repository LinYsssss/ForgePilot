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
 * The single technical entry point to an OpenAI-compatible provider
 * (ARCHITECTURE.md 4.1). It owns HTTP, authentication, the timeout, the one
 * bounded retry, and the call record — and it knows no business type: which
 * requirement or review a call belongs to arrives as opaque ids in
 * {@link AiCallContext}, so {@code ai} still depends on {@code common} alone
 * (ARCHITECTURE.md 1.3). Business prompts belong to their own features; there is
 * no prompt registry and no generic context builder here (ARCHITECTURE.md 4).
 *
 * <p>The base URI comes from {@code forgepilot.ai.base-url} and no host is
 * hardcoded anywhere (D015.8). That is a production requirement — D010 requires
 * supporting a self-hosted provider — and it is also the seam that lets the
 * whole slice be tested against a local stub with no credential in existence.
 *
 * <p>An unconfigured deployment fails closed at the call instead of at startup:
 * batch 2 must not stop the application from booting for features nobody has
 * configured yet, and a default host or a default key would be exactly the
 * fallback credential {@code quality-guidelines.md} forbids.
 */
@Service
public class AiGateway {

    private static final String CHAT_PATH = "/chat/completions";
    private static final String EMBEDDING_PATH = "/embeddings";

    private final AiCallLogRepository callLogs;
    private final ObjectMapper json;
    private final TransactionTemplate ownTransaction;
    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String chatModel;
    private final Duration timeout;
    private final int promptCharBudget;

    AiGateway(AiCallLogRepository callLogs, ObjectMapper json, PlatformTransactionManager transactions,
            @Value("${forgepilot.ai.base-url:}") String baseUrl,
            @Value("${forgepilot.ai.api-key:}") String apiKey,
            @Value("${forgepilot.ai.chat-model:}") String chatModel,
            // ARCHITECTURE.md 7.2, "LLM 单次调用超时 | 120 s".
            @Value("${forgepilot.ai.timeout:120s}") Duration timeout,
            // 7.2 states one character budget, 60000, for the batcher that will
            // build review prompts. This reuses that number rather than inventing
            // a second one; nothing else in the authoritative documents sizes a
            // payload in characters.
            @Value("${forgepilot.ai.prompt-char-budget:60000}") int promptCharBudget) {
        this.callLogs = callLogs;
        this.json = json;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.chatModel = chatModel;
        this.timeout = timeout;
        this.promptCharBudget = promptCharBudget;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * One completion. {@code schema} is the caller's JSON schema for a structured
     * answer, or {@code null} for free text: quality and guidance share this
     * gateway and differ only by their schema (ARCHITECTURE.md 4.1).
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
            format.putObject("json_schema").put("name", "result").set("schema", parseSchema(schema));
        }
        return call(CHAT_PATH, request, chatModel, useCase, context, AiGateway::content);
    }

    /**
     * One embedding per text, in the order they were given. The model is the
     * caller's because the embedding profile lives with the rows that record it
     * ({@code knowledge_chunk.provider/model/version/dimension}); {@code ai} only
     * carries it to the provider and into the call record.
     */
    public List<float[]> embed(List<String> texts, String model, AiCallContext context) {
        ObjectNode request = json.createObjectNode();
        request.put("model", model);
        ArrayNode input = request.putArray("input");
        for (String text : texts) {
            input.add(PromptSanitizer.sanitize(text, promptCharBudget));
        }
        return call(EMBEDDING_PATH, request, model, AiUseCase.EMBEDDING, context,
                answer -> vectors(answer, texts.size()));
    }

    /**
     * Sends the request, and on a transient failure sends it exactly once more.
     * Every attempt writes its own {@code ai_call_log} row before this method
     * decides anything, so a retried call is visible as two rows and a permanent
     * failure as one — the retry is auditable rather than asserted.
     */
    private <T> T call(String path, ObjectNode request, String model, AiUseCase useCase,
            AiCallContext context, Function<JsonNode, T> reader) {
        HttpRequest post = post(endpoint(path), request.toString());
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
                // 7.2: "超时按瞬时失败处理", so this falls through to the retry —
                // recorded as TIMEOUT because "answered badly" and "never
                // answered" are different facts for whoever reads the log later.
                recordAttempt(AiCallLog.failure(context, useCase, model, elapsedMs(startedAt),
                        AiCallStatus.TIMEOUT, "no answer within " + timeout));
            } catch (JacksonException notJson) {
                // A body that will not parse cannot be fixed by asking again, and
                // it must never become a silent empty answer. The one format
                // repair ARCHITECTURE.md 3.5 allows is a different budget owned by
                // review; merging the two would quietly permit four calls.
                recordAttempt(failed(context, useCase, model, startedAt, "the answer was not JSON"));
                throw unavailable();
            } catch (MalformedAnswerException malformed) {
                recordAttempt(failed(context, useCase, model, startedAt, malformed.getMessage()));
                throw unavailable();
            } catch (IOException transport) {
                // 7.2's "网络" class: refused, reset, or cut off mid-answer.
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

    private HttpRequest post(URI endpoint, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) {
            // There is no default key anywhere in this codebase. An unconfigured
            // deployment sends no header and the provider's own 401 is the honest
            // answer, which is also why the tests need no credential.
            request.header("Authorization", "Bearer " + apiKey);
        }
        return request.build();
    }

    private URI endpoint(String path) {
        if (baseUrl.isBlank()) {
            throw unconfigured();
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(base + path);
    }

    /**
     * Each attempt lands in its own transaction. The purpose of the table is
     * "故障定位" (ARCHITECTURE.md 2.1), and a caller that rolls back — a failed
     * review, a failed ingestion — would otherwise erase the record of why it
     * failed. The cost is that a caller must not already hold a row lock on the
     * requirement it names here, or this insert's foreign key check would wait
     * on the very transaction that is waiting for it.
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
                // Reading it anyway would yield 0.0 and a vector that looks fine.
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
            // The schema is the caller's own constant, not user input.
            throw new IllegalArgumentException("The response schema is not valid JSON.", notJson);
        }
    }

    /**
     * Says nothing about the request or the answer on purpose: a 5xx reaches
     * {@code ApiExceptionHandler}, which logs this exception with its stack
     * trace, and prompt or response payloads must never get there.
     */
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable",
                "The AI provider call did not succeed.");
    }

    private static ApiException unconfigured() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_unconfigured",
                "The AI provider is not configured.");
    }

    /** A 2xx whose body does not carry what was asked for. Never retried, never silently empty. */
    private static final class MalformedAnswerException extends RuntimeException {

        private MalformedAnswerException(String message) {
            super(message);
        }
    }
}
