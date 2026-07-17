package com.example.codereview.ai.langchain4j;

import com.example.codereview.agent.model.AgentModelClient;
import com.example.codereview.agent.model.PromptEnvelope;
import com.example.codereview.ai.AiCallTransientException;
import com.example.codereview.common.exception.BusinessException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Objects;

public final class LangChain4jAgentModelClient implements AgentModelClient {

    private static final String GENERATE = "generate";
    private static final String REPAIR = "repair";

    private final ChatModel chatModel;
    private final String provider;
    private final String configuredModel;
    private final ObservationRegistry observationRegistry;

    public LangChain4jAgentModelClient(
            ChatModel chatModel,
            String provider,
            String configuredModel,
            ObservationRegistry observationRegistry
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.provider = requireText(provider, "provider");
        this.configuredModel = requireText(configuredModel, "configuredModel");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String model() {
        return configuredModel;
    }

    @Override
    public ModelResponse generate(PromptEnvelope prompt) {
        Objects.requireNonNull(prompt, "prompt");
        return call(
                List.of(
                        SystemMessage.from(prompt.systemMessage()),
                        UserMessage.from(prompt.userMessage())
                ),
                GENERATE
        );
    }

    @Override
    public ModelResponse repairJson(String invalidOutput, String validationError) {
        String systemMessage = """
                You repair invalid JSON for a typed Agent response.
                Return only the corrected JSON object. Do not return Markdown or explanations.
                Never add tools, permissions, repository actions, or fields absent from the required schema.
                """;
        String userMessage = """
                <validation_error>
                %s
                </validation_error>
                <invalid_output>
                %s
                </invalid_output>
                """.formatted(safe(validationError), safe(invalidOutput));
        return call(
                List.of(SystemMessage.from(systemMessage), UserMessage.from(userMessage)),
                REPAIR
        );
    }

    private ModelResponse call(List<ChatMessage> messages, String purpose) {
        Observation observation = Observation.createNotStarted(
                        "reposage.agent.model",
                        observationRegistry
                )
                .lowCardinalityKeyValue("provider", provider)
                .lowCardinalityKeyValue("model", configuredModel)
                .lowCardinalityKeyValue("purpose", purpose);
        return observation.observe(() -> invoke(messages));
    }

    private ModelResponse invoke(List<ChatMessage> messages) {
        long startedNanos = System.nanoTime();
        try {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .build());
            if (response == null || response.aiMessage() == null
                    || response.aiMessage().text() == null
                    || response.aiMessage().text().isBlank()) {
                throw new BusinessException(6004, "LangChain4j provider returned an invalid response");
            }
            TokenUsage usage = response.tokenUsage();
            long inputTokens = usage == null || usage.inputTokenCount() == null
                    ? 0 : usage.inputTokenCount();
            long outputTokens = usage == null || usage.outputTokenCount() == null
                    ? 0 : usage.outputTokenCount();
            String responseModel = response.modelName();
            String finishReason = response.finishReason() == null
                    ? "UNKNOWN" : response.finishReason().name();
            return new ModelResponse(
                    provider,
                    responseModel == null || responseModel.isBlank() ? configuredModel : responseModel,
                    response.aiMessage().text(),
                    inputTokens,
                    outputTokens,
                    finishReason,
                    elapsedMs(startedNanos)
            );
        } catch (BusinessException | AiCallTransientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw classify(ex);
        }
    }

    private RuntimeException classify(RuntimeException failure) {
        Throwable current = failure;
        Throwable providerFailure = failure;
        while (current != null) {
            providerFailure = current;
            if (current instanceof RetriableException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException) {
                return new AiCallTransientException(
                        "LangChain4j provider call failed transiently ("
                                + current.getClass().getSimpleName() + ")",
                        failure
                );
            }
            if (current instanceof HttpException http) {
                if (http.statusCode() == 429 || http.statusCode() >= 500) {
                    return new AiCallTransientException(
                            "LangChain4j provider call failed transiently (HTTP "
                                    + http.statusCode() + ")",
                            failure
                    );
                }
                return permanent(current);
            }
            if (current instanceof NonRetriableException) {
                return permanent(current);
            }
            current = current.getCause();
        }
        return new BusinessException(
                6004,
                "LangChain4j provider returned an invalid response ("
                        + providerFailure.getClass().getSimpleName() + ")"
        );
    }

    private BusinessException permanent(Throwable failure) {
        return new BusinessException(
                6004,
                "LangChain4j provider call failed permanently ("
                        + failure.getClass().getSimpleName() + ")"
        );
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String bounded = value.length() <= 65_536 ? value : value.substring(0, 65_536);
        return bounded.replace("</", "<\\/");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
