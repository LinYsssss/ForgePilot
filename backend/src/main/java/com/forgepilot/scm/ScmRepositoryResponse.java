package com.forgepilot.scm;

import java.time.Instant;

/** LEADER 能看到的连接信息。token 与 webhook 密钥都不出现在这里。 */
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
