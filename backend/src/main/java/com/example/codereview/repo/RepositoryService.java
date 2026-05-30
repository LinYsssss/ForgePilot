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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryService {

    private final ProjectService projectService;
    private final CodeRepositoryJpaRepository repositories;
    private final GitCliService gitCliService;
    private final CryptoService cryptoService;

    public RepositoryService(ProjectService projectService, CodeRepositoryJpaRepository repositories,
                             GitCliService gitCliService, CryptoService cryptoService) {
        this.projectService = projectService;
        this.repositories = repositories;
        this.gitCliService = gitCliService;
        this.cryptoService = cryptoService;
    }

    @Transactional
    public RepositoryResponse bind(Long projectId, Long userId, BindRepositoryRequest request) {
        projectService.getRequired(projectId, userId);
        GitInputValidator.requireSafeRepoUrl(request.repoUrl());
        if (request.defaultBranch() != null && !request.defaultBranch().isBlank()) {
            GitInputValidator.requireSafeRef(request.defaultBranch(), "默认分支");
        }
        String encryptedToken = cryptoService.encrypt(request.accessToken());
        CodeRepositoryEntity entity = repositories.findByProjectId(projectId)
                .orElseGet(() -> new CodeRepositoryEntity(projectId, request.repoUrl(), request.provider(), request.defaultBranch(), encryptedToken));
        if (entity.getId() != null) {
            entity.update(request.repoUrl(), request.provider(), request.defaultBranch(), encryptedToken);
        }
        repositories.save(entity);
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
