package com.example.codereview.model;

import java.util.Optional;

public interface ModelRiskClient {

    Optional<ModelRiskSignal> predict(Long projectId, Long taskId, String diffText);
}
