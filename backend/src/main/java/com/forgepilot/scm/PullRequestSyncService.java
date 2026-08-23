package com.forgepilot.scm;

import java.time.Instant;
import java.util.List;

import com.forgepilot.common.ApiException;
import com.forgepilot.scm.RequirementReferenceParser.RequirementReference;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 把「一次已认证的投递 + 一份权威的 provider 快照」变成 pull request 行。
 */
@Service
public class PullRequestSyncService {

    private final ScmRepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final PullRequestRequirementEventRepository events;
    private final RequirementReferenceParser references;
    private final WebhookSignatureVerifier verifier;
    private final ScmSecretCipher cipher;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper json;

    PullRequestSyncService(ScmRepositoryRepository repositories, PullRequestRepository pullRequests,
            PullRequestRequirementEventRepository events, RequirementReferenceParser references,
            WebhookSignatureVerifier verifier, ScmSecretCipher cipher,
            ApplicationEventPublisher publisher, ObjectMapper json) {
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.events = events;
        this.references = references;
        this.verifier = verifier;
        this.cipher = cipher;
        this.publisher = publisher;
        this.json = json;
    }

    /**
     * 把投递路由到它所属的仓库，并对**原始字节**验签；整个过程不写任何东西、
     * 也不发起任何出站调用。
     *
     * <p>查找必须先于验签，因为用来验签的密钥正是被查那一行的一个列。
     * 两种失败都以同一个响应体答 401：把「无此仓库」和「签名错误」区分开，
     * 会让任何人都能枚举本部署接入了哪些仓库——与批次 1 的
     * “不存在与无权限不可区分”是同一条规则。
     */
    @Transactional(readOnly = true)
    public ScmRepository authenticate(ScmProvider provider, String instanceIdentity, String externalId,
            byte[] body, String signatureHeader) {
        ScmRepository repository = repository(provider, instanceIdentity, externalId);
        if (!verifier.matches(body, cipher.decrypt(repository.getEncryptedSecret()), signatureHeader)) {
            throw unauthenticated();
        }
        return repository;
    }

    /** 认证当前签名式的 GitLab webhook，或它的旧版 token 形式。 */
    @Transactional(readOnly = true)
    public ScmRepository authenticateGitLab(String instanceIdentity, String externalId, byte[] body,
            String signatureHeader, String messageId, String timestampHeader, String tokenHeader) {
        ScmRepository repository = repository(ScmProvider.GITLAB, instanceIdentity, externalId);
        if (!verifier.matchesGitLab(body, cipher.decrypt(repository.getEncryptedSecret()),
                signatureHeader, messageId, timestampHeader, tokenHeader)) {
            throw unauthenticated();
        }
        return repository;
    }

    private ScmRepository repository(ScmProvider provider, String instanceIdentity, String externalId) {
        return repositories.findByProviderAndInstanceIdentityAndExternalId(provider, instanceIdentity, externalId)
                .orElseThrow(PullRequestSyncService::unauthenticated);
    }

    @Transactional
    public void apply(ScmRepository detached, PullRequestSnapshot snapshot) {
        // 在本事务里重新读取，而不是信任 authenticate() 读到并已提交的那一行。
        // 两者之间夹着对 provider 的拉取，而身份更新可以在这个窗口里提交：
        // 三元组冻结只在「已经存在 PR」时才拒绝变更，因此一次先于本次插入
        // 发生的更新是合法的——于是这次投递会把 PR 挂到一个身份刚刚迁移过的
        // 仓库上，并存下一个用**旧**三元组算出来的指纹，
        // 而这正是 design.md 3.7 所要防止的那种不可复现。
        //
        // 是「加锁」而不仅仅是「重读」。不加锁的重读只能缩小窗口而无法关闭它：
        // 实测表明，身份更新仍可能在这次读取与下面的插入之间提交——因为插入
        // 对本行只取 FOR KEY SHARE，而更新方的冻结检查运行时尚不存在任何 PR。
        // 其结果是一个永远无法从它所属的那一行重算出来的指纹。
        // project_id 取自 authenticate() 已经解析出的那一行：仓库不会在项目之间迁移。
        ScmRepository repository = repositories
                .findWithLockByProjectIdAndId(detached.getProjectId(), detached.getId())
                .orElseThrow(PullRequestSyncService::unauthenticated);
        long projectId = repository.getProjectId();
        String fingerprint = ReviewInputFingerprint.of(repository.getProvider().name(),
                repository.getInstanceIdentity(), repository.getExternalId(), snapshot);
        String manifest = manifest(snapshot.changedFiles());

        PullRequest existing = pullRequests.findWithLockByProjectIdAndRepositoryIdAndExternalNumber(
                projectId, repository.getId(), snapshot.externalNumber()).orElse(null);
        if (existing != null) {
            // 按 ARCHITECTURE.md 3.1 是「不更旧」：时间戳相等仍然写入，
            // 因为 provider 的时间精度只有一秒，两次 push 可能落在同一秒，
            // 而且这些值本来就来自一次全新的读取。
            if (isOlderThan(snapshot.sourceUpdatedAt(), existing.getSourceUpdatedAt())) {
                return;
            }
            existing.applySnapshot(snapshot.baseSha(), snapshot.headSha(), snapshot.title(), fingerprint,
                    manifest, snapshot.sourceRevision(), snapshot.sourceUpdatedAt());
            pullRequests.flush();
            publish(existing);
            return;
        }

        PullRequest created = new PullRequest(projectId, repository.getId(), snapshot.externalNumber(),
                snapshot.title(), snapshot.authorExternalUserId(), snapshot.authorUsername());
        created.applySnapshot(snapshot.baseSha(), snapshot.headSha(), snapshot.title(), fingerprint,
                manifest, snapshot.sourceRevision(), snapshot.sourceUpdatedAt());
        // 只在创建该行时解析一次。后续投递绝不能覆盖人工纠正去重新解析，
        // D007 把关联的最终话语权交给了页面；解析不出来的引用只会让它保持 null，
        // 永远不会阻塞入库。
        RequirementReference reference = references
                .resolve(projectId, snapshot.headRef(), snapshot.title()).orElse(null);
        created.linkRequirement(reference == null ? null : reference.requirementId());
        pullRequests.saveAndFlush(created);
        if (reference != null) {
            events.save(PullRequestRequirementEvent.systemLink(projectId, created.getId(),
                    reference.requirementId(),
                    "Linked from REQ-" + reference.requirementId() + " in the pull request " + reference.source()));
        }
        publish(created);
    }

    /**
     * 投递不带定序值时选择拒绝，而不是去猜。在那里返回 false 会让这道护栏
     * 悄无声息地消失，从而允许乱序投递把 head、base 和 patch 往回滚——
     * 而这正是 R4 唯一明令禁止的事。GitHub 总会发 updated_at，
     * 因此这条路径今天是潜伏的，一旦接入某个不发它的 provider 就会立刻生效。
     */
    private static boolean isOlderThan(Instant incoming, Instant current) {
        if (current == null) {
            return false;
        }
        if (incoming == null) {
            throw ApiException.unprocessable(
                    "The delivery carries no source timestamp, so it cannot be ordered "
                            + "against what is already stored.");
        }
        return incoming.isBefore(current);
    }

    /**
     * 先 flush 行再发事件，这样加入本事务的监听器才真的能读到那条
     * 它被告知的行。
     */
    private void publish(PullRequest pullRequest) {
        publisher.publishEvent(new PullRequestChanged(pullRequest.getId(), pullRequest.getHeadSha(),
                pullRequest.getReviewInputFingerprint()));
    }

    /**
     * 清单连同每个 patch，作为**一个** JSONB 值（D015.7）。超限时入库显式失败，
     * 而不是存下一份被悄悄缩短的 diff，再告诉 Review 说它是完整的。
     * {@code pull_request} 上没有任何列可以标记这样一次投递，
     * 所以干脆什么都不写。
     */
    private String manifest(List<ChangedFile> changedFiles) {
        String manifest = json.writeValueAsString(ChangedFile.canonicalOrder(changedFiles));
        if (manifest.length() > ChangedFile.MAX_TOTAL_CHARS) {
            throw ApiException.unprocessable("This pull request's diff is larger than this deployment stores.");
        }
        return manifest;
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "The delivery could not be verified.");
    }
}
