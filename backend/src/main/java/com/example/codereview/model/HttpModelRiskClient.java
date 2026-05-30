package com.example.codereview.model;

import com.example.codereview.ai.AiCallLogService;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(prefix = "app.model-service", name = "enabled", havingValue = "true")
public class HttpModelRiskClient implements ModelRiskClient {

    private final RestClient restClient;
    private final AiCallLogService aiCallLogService;

    public HttpModelRiskClient(
            RestClient.Builder restClientBuilder,
            AiCallLogService aiCallLogService,
            @Value("${app.model-service.base-url}") String baseUrl
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("MODEL_SERVICE_ENABLED=true requires MODEL_SERVICE_URL");
        }
        this.restClient = restClientBuilder.baseUrl(baseUrl.replaceAll("/+$", "")).build();
        this.aiCallLogService = aiCallLogService;
    }

    @Override
    public Optional<ModelRiskSignal> predict(Long projectId, Long taskId, String diffText) {
        if (diffText == null || diffText.isBlank()) {
            return Optional.empty();
        }
        long start = System.nanoTime();
        try {
            PredictResponse response = restClient.post()
                    .uri("/predict")
                    .body(new PredictRequest(diffText, null, "MODIFIED"))
                    .retrieve()
                    .body(PredictResponse.class);
            if (response == null) {
                return Optional.empty();
            }
            int responseChars = chars(response.riskType()) + chars(response.severity()) + chars(response.source());
            aiCallLogService.modelRiskSuccess(
                    projectId,
                    taskId,
                    chars(diffText),
                    responseChars,
                    elapsedMs(start),
                    response.modelVersion()
            );
            return Optional.of(new ModelRiskSignal(
                    response.riskType(),
                    response.severity(),
                    response.confidence(),
                    response.modelVersion(),
                    response.source()
            ));
        } catch (RuntimeException ex) {
            aiCallLogService.modelRiskFailed(projectId, taskId, chars(diffText), elapsedMs(start), ex.getMessage());
            return Optional.empty();
        }
    }

    private int chars(String value) {
        return value == null ? 0 : value.length();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record PredictRequest(String diffText, String filePath, String changeType) {
    }

    private record PredictResponse(
            String riskType,
            String severity,
            double confidence,
            String modelVersion,
            String source
    ) {
    }
}
