package com.example.codereview.scm;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CryptoService;
import com.example.codereview.project.ProjectRepository;
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
 *
 * <p>授权边界是 SecurityConfig 上的 {@code hasRole("ADMIN")}:三个方法一致地按
 * 平台管理员作用域工作,不再要求管理员同时是目标项目的 owner。
 */
@Service
public class ScmInstallationAdminService {

    private final ScmInstallationRepository installations;
    private final ProjectRepository projects;
    private final CodeRepositoryJpaRepository repositories;
    private final CryptoService cryptoService;

    public ScmInstallationAdminService(ScmInstallationRepository installations,
                                       ProjectRepository projects,
                                       CodeRepositoryJpaRepository repositories,
                                       CryptoService cryptoService) {
        this.installations = installations;
        this.projects = projects;
        this.repositories = repositories;
        this.cryptoService = cryptoService;
    }

    @Transactional
    public InstallationResponse register(RegisterInstallationRequest request) {
        // 该端点整体由 SecurityConfig 的 hasRole("ADMIN") 把关,平台管理员即授权边界,
        // 因此这里只校验项目存在(不要求管理员同时是项目 owner,否则无法为他人的项目接入 webhook)。
        if (!projects.existsById(request.projectId())) {
            throw new BusinessException(404, "项目不存在");
        }
        // 复制粘贴 installation id 常带首尾空格:先归一化再查,否则查不到旧记录却又以
        // 去空格后的值写入,会撞上 (provider, external_installation_id) 唯一约束。
        String externalId = request.externalInstallationId().trim();
        Long repositoryId = request.repositoryId();
        if (repositoryId == null) {
            // 未显式指定时,自动补当前项目绑定的仓库,webhook 流程靠它定位代码库。
            repositoryId = repositories.findByProjectId(request.projectId())
                    .map(repo -> repo.getId())
                    .orElse(null);
        }

        // (provider, externalInstallationId) 有唯一约束:存在即整体更新并重新激活。
        ScmInstallation entity = installations
                .findByProviderAndExternalInstallationId(request.provider(), externalId)
                .orElseGet(ScmInstallation::new);
        entity.setProvider(request.provider());
        entity.setExternalInstallationId(externalId);
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

    @Transactional(readOnly = true)
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
