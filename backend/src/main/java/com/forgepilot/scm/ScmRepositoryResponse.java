package com.forgepilot.scm;

import java.time.Instant;

/** 项目成员可读的安全连接信息。token 与 webhook 密钥都不出现在这里。 */
record ScmRepositoryResponse(
        Long id,
        Long projectId,
        ScmProvider provider,
        String instanceIdentity,
        String externalId,
        String apiBase,
        boolean identityApprovalRequired,
        Instant createdAt,
        Instant updatedAt) {

    static ScmRepositoryResponse of(ScmRepository repository) {
        return new ScmRepositoryResponse(repository.getId(), repository.getProjectId(),
                repository.getProvider(), repository.getInstanceIdentity(), repository.getExternalId(),
                repository.getApiBase(), repository.isIdentityApprovalRequired(),
                repository.getCreatedAt(), repository.getUpdatedAt());
    }
}
