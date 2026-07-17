package com.example.codereview.ai.langchain4j;

import com.example.codereview.ai.AiCallTransientException;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.rag.EmbeddingClient;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LangChain4jEmbeddingClient implements EmbeddingClient {

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
            throw new BusinessException(6003, "Embedding input must not be empty");
        }
        if (text.length() > maxInputChars) {
            throw new BusinessException(6003, "Embedding input exceeds maximum size");
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
                throw new BusinessException(6003, "Embedding dimension mismatch");
            }
            List<Double> vector = new ArrayList<>(raw.length);
            for (float value : raw) {
                if (!Float.isFinite(value)) {
                    throw new BusinessException(6003, "Embedding values must be finite");
                }
                vector.add((double) value);
            }
            return new EmbeddingResult(provider, model, version, raw.length, vector);
        } catch (BusinessException | AiCallTransientException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw classify(ex);
        }
    }

    private RuntimeException classify(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RetriableException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException) {
                return new AiCallTransientException(
                        "Embedding provider call failed transiently ("
                                + current.getClass().getSimpleName() + ")",
                        failure
                );
            }
            if (current instanceof HttpException http) {
                if (http.statusCode() == 429 || http.statusCode() >= 500) {
                    return new AiCallTransientException(
                            "Embedding provider call failed transiently (HTTP "
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
        return invalid(failure.getClass().getSimpleName());
    }

    private BusinessException permanent(Throwable failure) {
        return new BusinessException(
                6003,
                "Embedding provider call failed permanently ("
                        + failure.getClass().getSimpleName() + ")"
        );
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
