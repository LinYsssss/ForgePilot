package com.example.codereview.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.codereview.common.security.CryptoService;
import com.example.codereview.common.security.ProjectAuthorization;
import com.example.codereview.git.GitCliService;
import com.example.codereview.project.ProjectService;
import com.example.codereview.repo.RepositoryDtos.BindRepositoryRequest;
import com.example.codereview.repo.RepositoryDtos.RepositoryResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepositoryServiceTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long USER_ID = 3L;
    private static final String URL = "https://github.com/acme/app";

    private final ProjectService projectService = mock(ProjectService.class);
    private final ProjectAuthorization projectAuthorization = mock(ProjectAuthorization.class);
    private final CodeRepositoryJpaRepository repositories = mock(CodeRepositoryJpaRepository.class);
    private final GitCliService gitCliService = mock(GitCliService.class);
    private final CryptoService cryptoService = mock(CryptoService.class);
    private RepositoryService service;

    @BeforeEach
    void setUp() {
        service = new RepositoryService(projectService, projectAuthorization, repositories, gitCliService, cryptoService, false);
        when(cryptoService.encrypt(anyString())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");
        when(repositories.save(any(CodeRepositoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private BindRepositoryRequest req(String url, String token) {
        return new BindRepositoryRequest(url, "GITHUB", "main", token);
    }

    private CodeRepositoryEntity existing(String url, String encryptedToken) {
        return new CodeRepositoryEntity(PROJECT_ID, url, "GITHUB", "main", encryptedToken);
    }

    @Test
    void newBindEncryptsTokenAndDoesNotTouchWorkingCopy() {
        when(repositories.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        RepositoryResponse resp = service.bind(PROJECT_ID, USER_ID, req(URL, "ghp_secret"));

        verify(cryptoService).encrypt("ghp_secret");
        verify(repositories).save(any(CodeRepositoryEntity.class));
        verify(gitCliService, never()).deleteWorkingCopy(any());
        assertThat(resp.repoUrl()).isEqualTo(URL);
        assertThat(resp.tokenConfigured()).isTrue();
    }

    @Test
    void rebindSameUrlWithBlankTokenKeepsExistingToken() {
        when(repositories.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(existing(URL, "enc(old)")));

        RepositoryResponse resp = service.bind(PROJECT_ID, USER_ID, req(URL, ""));

        verify(cryptoService, never()).encrypt(anyString());     // 未重新加密 -> 复用原密文
        verify(gitCliService, never()).deleteWorkingCopy(any());  // 同一仓库不失效克隆
        assertThat(resp.tokenConfigured()).isTrue();
    }

    @Test
    void rebindChangedUrlWithBlankTokenDropsOldTokenAndInvalidatesClone() {
        when(repositories.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(existing(URL, "enc(old)")));

        service.bind(PROJECT_ID, USER_ID, req("https://github.com/acme/other", ""));

        verify(cryptoService).encrypt("");            // 改绑 -> 不保留旧令牌,按空串重新加密
        verify(gitCliService).deleteWorkingCopy(any()); // 作废旧克隆
    }

    @Test
    void rebindWithNewTokenReplacesToken() {
        when(repositories.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(existing(URL, "enc(old)")));

        service.bind(PROJECT_ID, USER_ID, req(URL, "ghp_new"));

        verify(cryptoService).encrypt("ghp_new");
        verify(gitCliService, never()).deleteWorkingCopy(any());
    }
}
