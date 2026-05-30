package com.example.codereview.model;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "app.model-service", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopModelRiskClient implements ModelRiskClient {

    @Override
    public Optional<ModelRiskSignal> predict(Long projectId, Long taskId, String diffText) {
        return Optional.empty();
    }
}
