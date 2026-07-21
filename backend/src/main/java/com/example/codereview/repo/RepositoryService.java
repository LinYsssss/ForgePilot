package com.example.codereview.repo;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.git.GitCliService;
import com.example.codereview.git.GitInputValidator;
import com.example.codereview.project.ProjectService;
import com.example.codereview.repo.RepositoryDtos.BindRepositoryRequest;
import com.example.codereview.repo.RepositoryDtos.CommitDiffResponse;
import com.example.codereview.repo.RepositoryDtos.CommitResponse;
import com.example.codereview.repo.RepositoryDtos.RepositoryResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryService {

    private final ProjectService projectService;
    private final CodeRepositoryJpaRepository repositories;
    private final GitCliService gitCliService;
    private final CryptoService cryptoService;
    private final boolean allowLocalRepoPath;

    public RepositoryService(ProjectService projectService, CodeRepositoryJpaRepository repositories,
                             GitCliService gitCliService, CryptoService cryptoService,
                             @Value("${app.git.allow-local-path:false}") boolean allowLocalRepoPath) {
        this.projectService = projectService;
        this.repositories = repositories;
        this.gitCliService = gitCliService;
        this.cryptoService = cryptoService;
        this.allowLocalRepoPath = allowLocalRepoPath;
    }

    @Transactional
    public RepositoryResponse bind(Long projectId, Long userId, BindRepositoryRequest request) {
        projectService.getRequired(projectId, userId);
        GitInputValidator.requireSafeRepoUrl(request.repoUrl(), allowLocalRepoPath);
        if (request.defaultBranch() != null && !request.defaultBranch().isBlank()) {
            GitInputValidator.requireSafeRef(request.defaultBranch(), "默认分支");
        }
        CodeRepositoryEntity entity = repositories.findByProjectId(projectId).orElse(null);
        boolean repoUrlChanged = entity != null && !request.repoUrl().equals(entity.getRepoUrl());
        String requestToken = request.accessToken();
        String encryptedToken;
        if ((requestToken == null || requestToken.isBlank())
                && entity != null
                && entity.getAccessTokenCiphertext() != null
                && !entity.getAccessTokenCiphertext().isBlank()) {
            // 已绑定且本次令牌留空:保留原有令牌,避免被覆盖成空(否则回填表单后再次绑定会清掉私有库令牌)。
            encryptedToken = entity.getAccessTokenCiphertext();
        } else {
            encryptedToken = cryptoService.encrypt(requestToken == null ? "" : requestToken);
        }
        if (entity == null) {
            entity = new CodeRepositoryEntity(projectId, request.repoUrl(), request.provider(),
                    request.defaultBranch(), encryptedToken);
        } else {
            entity.update(request.repoUrl(), request.provider(), request.defaultBranch(), encryptedToken);
        }
        repositories.save(entity);
        if (repoUrlChanged) {
            // 改绑到新的仓库地址时丢弃旧的本地克隆,避免下次读提交时命中旧仓库。
            gitCliService.deleteWorkingCopy(entity.getId());
        }
        return RepositoryResponse.from(entity);
    }

    public RepositoryResponse detail(Long projectId, Long userId) {
        return RepositoryResponse.from(getRequired(projectId, userId));
    }

    public List<CommitResponse> commits(Long projectId, Long userId, int limit) {
        return gitCliService.listCommits(getRequired(projectId, userId), limit);
    }

    public CommitDiffResponse diff(Long projectId, Long userId, String commitId, String baseCommitId) {
        return gitCliService.diff(getRequired(projectId, userId), commitId, baseCommitId);
    }

    public CodeRepositoryEntity getRequired(Long projectId, Long userId) {
        projectService.getRequired(projectId, userId);
        return repositories.findByProjectId(projectId)
                .orElseThrow(() -> new BusinessException(404, "项目尚未绑定仓库"));
    }

    @Transactional
    public void unbind(Long projectId, Long userId) {
        CodeRepositoryEntity repository = getRequired(projectId, userId);
        repositories.delete(repository);
    }
}
