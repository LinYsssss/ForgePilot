package com.example.codereview.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.common.security.SecurityAuditLogger;
import com.example.codereview.project.ProjectRepository;
import com.example.codereview.repo.CodeRepositoryEntity;
import com.example.codereview.repo.CodeRepositoryJpaRepository;
import com.example.codereview.scm.ScmInstallationDtos.InstallationResponse;
import com.example.codereview.scm.ScmInstallationDtos.RegisterInstallationRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScmInstallationAdminServiceTest {

    private static final Long PROJECT_ID = 2L;

    private final ScmInstallationRepository installations = mock(ScmInstallationRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final CodeRepositoryJpaRepository repositories = mock(CodeRepositoryJpaRepository.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private ScmInstallationAdminService service;

    @BeforeEach
    void setUp() {
        service = new ScmInstallationAdminService(installations, projects, repositories, cryptoService,
                new SecurityAuditLogger("test-audit-salt"));
        when(projects.existsById(PROJECT_ID)).thenReturn(true);
        when(cryptoService.encrypt(anyString())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");
        when(installations.save(any(ScmInstallation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private RegisterInstallationRequest request(String installationRef, String credential, Long repositoryId) {
        return new RegisterInstallationRequest(ScmProviderType.GITHUB, installationRef,
                "hook-secret", credential, PROJECT_ID, repositoryId, null, null, "demo");
    }

    @Test
    void registerEncryptsSecretAndAutoFillsRepositoryFromProjectBinding() {
        when(installations.findByProviderAndExternalInstallationId(ScmProviderType.GITHUB, "42"))
                .thenReturn(Optional.empty());
        CodeRepositoryEntity boundRepo = mock(CodeRepositoryEntity.class);
        when(boundRepo.getId()).thenReturn(77L);
        when(repositories.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(boundRepo));

        InstallationResponse resp = service.register(request("42", null, null));

        verify(cryptoService).encrypt("hook-secret");
        assertThat(resp.repositoryId()).isEqualTo(77L);
        assertThat(resp.secretConfigured()).isTrue();
        assertThat(resp.credentialConfigured()).isFalse();
        assertThat(resp.active()).isTrue();
    }

    @Test
    void upsertUpdatesExistingRowReactivatesAndKeepsCredentialWhenBlank() {
        ScmInstallation existing = new ScmInstallation();
        existing.setProvider(ScmProviderType.GITHUB);
        existing.setExternalInstallationId("42");
        existing.setEncryptedCredential("enc(old-cred)");
        existing.setActive(false);
        when(installations.findByProviderAndExternalInstallationId(ScmProviderType.GITHUB, "42"))
                .thenReturn(Optional.of(existing));

        InstallationResponse resp = service.register(request("42", "", 9L));

        assertThat(existing.getEncryptedCredential()).isEqualTo("enc(old-cred)"); // 留空不覆盖
        assertThat(resp.credentialConfigured()).isTrue();
        assertThat(resp.active()).isTrue();
        assertThat(resp.repositoryId()).isEqualTo(9L);
        verify(repositories, never()).findByProjectId(any()); // 显式给了 repositoryId 就不自动补
    }

    @Test
    void registerWithCredentialEncryptsIt() {
        when(installations.findByProviderAndExternalInstallationId(ScmProviderType.GITHUB, "42"))
                .thenReturn(Optional.empty());
        when(repositories.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        InstallationResponse resp = service.register(request("42", "glpat-token", null));

        verify(cryptoService).encrypt("glpat-token");
        assertThat(resp.credentialConfigured()).isTrue();
    }

    @Test
    void installationIdIsTrimmedBeforeLookupSoPastedWhitespaceStillUpserts() {
        ScmInstallation existing = new ScmInstallation();
        existing.setProvider(ScmProviderType.GITHUB);
        existing.setExternalInstallationId("42");
        when(installations.findByProviderAndExternalInstallationId(ScmProviderType.GITHUB, "42"))
                .thenReturn(Optional.of(existing));

        // 粘贴带首尾空格的 id:必须命中同一条记录(否则会撞唯一约束报 500)
        InstallationResponse resp = service.register(request("  42 ", null, 9L));

        assertThat(resp.externalInstallationId()).isEqualTo("42");
        verify(installations, never()).findByProviderAndExternalInstallationId(ScmProviderType.GITHUB, "  42 ");
    }

    @Test
    void unknownProjectIsNotFound() {
        when(projects.existsById(404L)).thenReturn(false);

        assertThatThrownBy(() -> service.register(new RegisterInstallationRequest(
                ScmProviderType.GITHUB, "42", "hook-secret", null, 404L, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(404));
    }

    @Test
    void deactivateFlipsActiveAndUnknownIdThrows() {
        ScmInstallation existing = new ScmInstallation();
        existing.setActive(true);
        when(installations.findById(5L)).thenReturn(Optional.of(existing));

        service.deactivate(5L);
        assertThat(existing.getActive()).isFalse();

        when(installations.findById(6L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivate(6L)).isInstanceOf(BusinessException.class);
    }
}
