package com.example.codereview.scm;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.project.ProjectService;
import com.example.codereview.repo.CodeRepositoryJpaRepository;
import com.example.codereview.scm.ScmInstallationDtos.InstallationResponse;
import com.example.codereview.scm.ScmInstallationDtos.RegisterInstallationRequest;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员侧的 SCM installation onboarding:webhook 端点只消费 scm_installation 记录
 * (按 provider+externalInstallationId 认领事件、用其中加密的 secret 验签、
 * 经 projectId/repositoryId 落到平台项目),此前没有任何入口能创建这条记录。
 * 密钥一律经 CryptoService 加密后入库,响应中仅回布尔位、绝不回显。
 */
@Service
public class ScmInstallationAdminService {

    private final ScmInstallationRepository installations;
    private final ProjectService projectService;
    private final CodeRepositoryJpaRepository repositories;
    private final CryptoService cryptoService;

    public ScmInstallationAdminService(ScmInstallationRepository installations,
                                       ProjectService projectService,
                                       CodeRepositoryJpaRepository repositories,
                                       CryptoService cryptoService) {
        this.installations = installations;
        this.projectService = projectService;
        this.repositories = repositories;
        this.cryptoService = cryptoService;
    }

    @Transactional
    public InstallationResponse register(Long userId, RegisterInstallationRequest request) {
        // 绑定目标必须是注册者名下存在的项目(getRequired 同时做归属校验)。
        projectService.getRequired(request.projectId(), userId);
        Long repositoryId = request.repositoryId();
        if (repositoryId == null) {
            // 未显式指定时,自动补当前项目绑定的仓库,webhook 流程靠它定位代码库。
            repositoryId = repositories.findByProjectId(request.projectId())
                    .map(repo -> repo.getId())
                    .orElse(null);
        }

        // (provider, externalInstallationId) 有唯一约束:存在即整体更新并重新激活。
        ScmInstallation entity = installations
                .findByProviderAndExternalInstallationId(request.provider(), request.externalInstallationId())
                .orElseGet(ScmInstallation::new);
        entity.setProvider(request.provider());
        entity.setExternalInstallationId(request.externalInstallationId().trim());
        entity.setDisplayName(blankToNull(request.displayName()));
        entity.setAppId(blankToNull(request.appId()));
        entity.setApiBaseUrl(blankToNull(request.apiBaseUrl()));
        entity.setProjectId(request.projectId());
        entity.setRepositoryId(repositoryId);
        entity.setEncryptedWebhookSecret(cryptoService.encrypt(request.webhookSecret()));
        if (request.credential() != null && !request.credential().isBlank()) {
            // 回写 PR(评论/状态)才需要 credential;留空表示保持原值(仅审、不回写时可始终不填)。
            entity.setEncryptedCredential(cryptoService.encrypt(request.credential()));
        }
        entity.setActive(true);
        return InstallationResponse.from(installations.save(entity));
    }

    public List<InstallationResponse> list() {
        return installations.findAll().stream().map(InstallationResponse::from).toList();
    }

    @Transactional
    public void deactivate(Long installationId) {
        ScmInstallation entity = installations.findById(installationId)
                .orElseThrow(() -> new BusinessException(404, "SCM 安装不存在"));
        entity.setActive(false);
        installations.save(entity);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
