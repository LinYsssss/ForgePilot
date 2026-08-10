package com.example.codereview.ai.langchain4j;

import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.ai.AiTransientFailureClassifier;
import com.example.codereview.common.exception.AiCallTransientException;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.rag.EmbeddingClient;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LangChain4jEmbeddingClient implements EmbeddingClient {

    /** 兜底命名沿用本类既有行为:点名抛出的顶层异常(与 Agent 模型客户端不同,写实保留)。 */
    private static final AiTransientFailureClassifier CLASSIFIER = AiTransientFailureClassifier.causalChain(
            "Embedding provider",
            6003,
            AiTransientFailureClassifier.UnrecognizedNaming.THROWN_FAILURE
    );

    private final EmbeddingModel embeddingModel;
    private final String provider;
    private final String model;
    private final String version;
    private final int expectedDimension;
    private final int maxInputChars;
    private final ObservationRegistry observationRegistry;

    public LangChain4jEmbeddingClient(
            EmbeddingModel embeddingModel,
            String provider,
            String model,
            String version,
            int expectedDimension,
            int maxInputChars,
            ObservationRegistry observationRegistry
    ) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.provider = requireText(provider, "provider");
        this.model = requireText(model, "model");
        this.version = requireText(version, "version");
        this.expectedDimension = Math.max(0, expectedDimension);
        this.maxInputChars = Math.max(1, maxInputChars);
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    @Override
    public EmbeddingDescriptor descriptor() {
        return new EmbeddingDescriptor(provider, model, version, expectedDimension);
    }

    @Override
    public EmbeddingResult embed(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(ErrorCode.AI_EMBEDDING_FAILED, "Embedding input must not be empty");
        }
        if (text.length() > maxInputChars) {
            throw new BusinessException(ErrorCode.AI_EMBEDDING_FAILED, "Embedding input exceeds maximum size");
        }
        Observation observation = Observation.createNotStarted(
                        "reposage.embedding.provider",
                        observationRegistry
                )
                .lowCardinalityKeyValue("provider", provider)
                .lowCardinalityKeyValue("model", model);
        return observation.observe(() -> invoke(text));
    }

    private EmbeddingResult invoke(String text) {
        try {
            Response<Embedding> response = embeddingModel.embed(text);
            if (response == null || response.content() == null) {
                throw invalid("missing embedding");
            }
            float[] raw = response.content().vector();
            if (raw == null || raw.length == 0) {
                throw invalid("empty embedding");
            }
            if (expectedDimension > 0 && raw.length != expectedDimension) {
                throw new BusinessException(ErrorCode.AI_EMBEDDING_FAILED, "Embedding dimension mismatch");
            }
            List<Double> vector = new ArrayList<>(raw.length);
            for (float value : raw) {
                if (!Float.isFinite(value)) {
                    throw new BusinessException(ErrorCode.AI_EMBEDDING_FAILED, "Embedding values must be finite");
                }
                vector.add((double) value);
            }
            return new EmbeddingResult(provider, model, version, raw.length, vector);
        } catch (BusinessException | AiCallTransientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw CLASSIFIER.classify(ex);
        }
    }

    private BusinessException invalid(String reason) {
        return new BusinessException(
                6003,
                "Embedding provider returned an invalid response (" + reason + ")"
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
