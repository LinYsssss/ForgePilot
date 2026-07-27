package com.example.codereview.scm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ScmInstallationDtos {

    private ScmInstallationDtos() {
    }

    public record RegisterInstallationRequest(
            @NotNull ScmProviderType provider,
            @NotBlank @Size(max = 255) String externalInstallationId,
            @NotBlank @Size(max = 512) String webhookSecret,
            @Size(max = 4096) String credential,
            @NotNull Long projectId,
            Long repositoryId,
            @Size(max = 512) String apiBaseUrl,
            @Size(max = 128) String appId,
            @Size(max = 255) String displayName
    ) {
    }

    public record InstallationResponse(
            Long installationId,
            String provider,
            String externalInstallationId,
            String displayName,
            String apiBaseUrl,
            Long projectId,
            Long repositoryId,
            boolean secretConfigured,
            boolean credentialConfigured,
            boolean active
    ) {
        public static InstallationResponse from(ScmInstallation entity) {
            return new InstallationResponse(
                    entity.getId(),
                    entity.getProvider() == null ? null : entity.getProvider().name(),
                    entity.getExternalInstallationId(),
                    entity.getDisplayName(),
                    entity.getApiBaseUrl(),
                    entity.getProjectId(),
                    entity.getRepositoryId(),
                    entity.getEncryptedWebhookSecret() != null && !entity.getEncryptedWebhookSecret().isBlank(),
                    entity.getEncryptedCredential() != null && !entity.getEncryptedCredential().isBlank(),
                    Boolean.TRUE.equals(entity.getActive())
            );
        }
    }
}
