package com.forgepilot.scm;

import java.net.URI;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and reconfiguration of a project's active repository. Only a LEADER
 * reaches any of it, and a non-member gets the same answer as for a project that
 * does not exist.
 *
 * <p>The service pre-checks nothing the database already refuses: one repository
 * per project and one globally unique stable identity are unique constraints, and
 * their conflicts arrive as 409 on their own.
 */
@Service
class ScmRepositoryService {

    private final ScmRepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final ProjectAccessService access;
    private final OutboundUrlPolicy outbound;
    private final ScmSecretCipher cipher;

    ScmRepositoryService(ScmRepositoryRepository repositories, PullRequestRepository pullRequests,
            ProjectAccessService access, OutboundUrlPolicy outbound, ScmSecretCipher cipher) {
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.access = access;
        this.outbound = outbound;
        this.cipher = cipher;
    }

    @Transactional
    ScmRepositoryResponse register(long projectId, long actorId, ScmProvider provider, String externalId,
            String apiBase, String token, String webhookSecret) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        URI base = outbound.requireAllowed(apiBase);
        ScmRepository repository = new ScmRepository(projectId, provider, InstanceIdentity.of(base),
                externalId, base.toString(), cipher.encrypt(token), cipher.encrypt(webhookSecret));
        return ScmRepositoryResponse.of(repositories.save(repository));
    }

    /**
     * Credentials may always be replaced. The stable identity may not, once the
     * repository has a pull request: everything already recorded — fingerprints,
     * associations, audit rows — was recorded about <em>that</em> repository, and
     * re-pointing the row would silently reattribute all of it. Changing repository
     * or instance for real means a new project (PRD 8).
     *
     * <p>This is a cross-row rule — whether this row's columns may change depends on
     * another table having rows — so no immediate constraint can express it and
     * ARCHITECTURE.md 2.1 authorizes a constraint trigger only for {@code finding}.
     * It is therefore a per-commit service invariant of the same kind as "at least
     * one LEADER" (D013.9), enforced here under the row lock taken first, and it is
     * <strong>not database-enforced</strong>.
     */
    @Transactional
    ScmRepositoryResponse update(long projectId, long actorId, long repositoryId, ScmProvider provider,
            String externalId, String apiBase, String token, String webhookSecret) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        ScmRepository repository = repositories.findWithLockByProjectIdAndId(projectId, repositoryId)
                .orElseThrow(ApiException::notFound);

        ScmProvider targetProvider = provider == null ? repository.getProvider() : provider;
        String targetExternalId = externalId == null ? repository.getExternalId() : externalId;
        URI base = apiBase == null ? null : outbound.requireAllowed(apiBase);
        // api_base is free to move as long as it still normalizes onto the same
        // instance: an API host can change between provider versions, an instance
        // cannot change underneath a repository.
        String targetInstance = base == null ? repository.getInstanceIdentity() : InstanceIdentity.of(base);

        boolean identityMoves = targetProvider != repository.getProvider()
                || !targetExternalId.equals(repository.getExternalId())
                || !targetInstance.equals(repository.getInstanceIdentity());
        if (identityMoves && pullRequests.existsByProjectIdAndRepositoryId(projectId, repositoryId)) {
            throw ApiException.conflict(
                    "This repository already has pull requests, so its identity can no longer change.");
        }

        if (identityMoves) {
            repository.reidentify(targetProvider, targetInstance, targetExternalId);
        }
        if (base != null) {
            repository.changeApiBase(base.toString());
        }
        if (token != null || webhookSecret != null) {
            repository.rotateCredentials(
                    token == null ? repository.getEncryptedToken() : cipher.encrypt(token),
                    webhookSecret == null ? repository.getEncryptedSecret() : cipher.encrypt(webhookSecret));
        }
        return ScmRepositoryResponse.of(repository);
    }

    @Transactional(readOnly = true)
    PullRequestResponse pullRequest(long projectId, long actorId, long pullRequestId) {
        access.requireMember(projectId, actorId);
        return pullRequests.findByProjectIdAndId(projectId, pullRequestId)
                .map(PullRequestResponse::of)
                .orElseThrow(ApiException::notFound);
    }
}
