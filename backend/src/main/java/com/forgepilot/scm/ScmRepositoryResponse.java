package com.forgepilot.scm;

import java.time.Instant;

/** What a LEADER may see of the connection. Neither the token nor the webhook secret appears here. */
record ScmRepositoryResponse(
        Long id,
        Long projectId,
        ScmProvider provider,
        String instanceIdentity,
        String externalId,
        String apiBase,
        Instant createdAt,
        Instant updatedAt) {

    static ScmRepositoryResponse of(ScmRepository repository) {
        return new ScmRepositoryResponse(repository.getId(), repository.getProjectId(),
                repository.getProvider(), repository.getInstanceIdentity(), repository.getExternalId(),
                repository.getApiBase(), repository.getCreatedAt(), repository.getUpdatedAt());
    }
}
