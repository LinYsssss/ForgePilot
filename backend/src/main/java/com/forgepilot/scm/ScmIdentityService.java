package com.forgepilot.scm;

import java.time.Instant;
import java.util.List;

import com.forgepilot.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户全局 SCM 身份的验证、标注与吊销（不隶属任何项目）。
 *
 * <p>身份由 {@code (provider, instance_identity, external_user_id)} 唯一标识；同一远端身份
 * 被第二个账号验证时以 409 拒绝，而同一账号重复验证只刷新用户名与时间。吊销身份会
 * 连带撤销它在各项目的 ACTIVE / PENDING 绑定，并逐项目重算 PR 作者映射。
 */
@Service
class ScmIdentityService {
    private final ScmIdentityRepository identities;
    private final ProjectMemberScmBindingRepository bindings;
    private final ScmIdentityVerifier verifier;
    private final PullRequestAuthorMapper authors;

    ScmIdentityService(ScmIdentityRepository identities, ProjectMemberScmBindingRepository bindings,
            ScmIdentityVerifier verifier, PullRequestAuthorMapper authors) {
        this.identities = identities;
        this.bindings = bindings;
        this.verifier = verifier;
        this.authors = authors;
    }

    @Transactional(readOnly = true)
    List<ScmIdentityResponse> list(long userId) {
        return identities.findByUserIdOrderByIdAsc(userId).stream().map(ScmIdentityResponse::of).toList();
    }

    @Transactional
    ScmIdentityResponse verify(long userId, ScmProvider provider, String apiBase, String token,
            String label, ScmIdentityUsage usageType) {
        VerifiedScmUser verified = verifier.currentUser(provider, apiBase, token);
        Instant now = Instant.now();
        ScmIdentity identity = identities.findProvenIdentity(provider, verified.instanceIdentity(),
                verified.externalUserId()).map(existing -> {
                    if (existing.getUserId() != userId) {
                        throw ApiException.conflict("该 SCM 身份已被其他账号认领。");
                    }
                    existing.refresh(verified, now);
                    existing.rename(label, usageType);
                    return existing;
                }).orElseGet(() -> identities.save(new ScmIdentity(userId, verified, label, usageType, now)));
        return ScmIdentityResponse.of(identity);
    }

    @Transactional
    ScmIdentityResponse update(long userId, long identityId, String label, ScmIdentityUsage usageType) {
        ScmIdentity identity = identities.findByUserIdAndId(userId, identityId)
                .orElseThrow(ApiException::notFound);
        identity.rename(label, usageType);
        return ScmIdentityResponse.of(identity);
    }

    @Transactional
    void revoke(long userId, long identityId) {
        ScmIdentity identity = identities.findByUserIdAndId(userId, identityId)
                .orElseThrow(ApiException::notFound);
        Instant now = Instant.now();
        identity.revoke();
        List<ProjectMemberScmBinding> affected = bindings.findByScmIdentityIdAndStatusIn(identityId, List.of(
                ProjectMemberScmBinding.Status.ACTIVE,
                ProjectMemberScmBinding.Status.PENDING_APPROVAL));
        affected.forEach(row -> row.revoke(now));
        bindings.flush();
        affected.stream().map(ProjectMemberScmBinding::getProjectId).distinct()
                .forEach(authors::remapProject);
    }
}
