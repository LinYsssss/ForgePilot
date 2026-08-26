package com.forgepilot.review;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectMember;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.scm.ProjectScmIdentityAccess;
import com.forgepilot.review.ReviewClaimRepository.PullRequestIdentity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 进入 Review 引擎的**唯一**入口。自动投递、人工点击“审查”、失败后的重试，
 * 最终都汇入同一个 find-or-create（ARCHITECTURE.md 3.1）——
 * 这里没有第二个引擎，也没有第二条流水线。
 *
 * <p>构成身份的那四个列，每次都是从该 PR 的<em>当前</em>值读取的。
 * 这正是为什么新的 head、移动过的 base、重新同步的 diff，
 * 或一次新发布的需求修订，各自都会铸造出一行新记录，
 * 而此前那一行则原封不动地保持它当初被裁定时的样子。
 */
@Service
public class ReviewService {

    /**
     * 在那个把 Review 留在 PENDING 的事务**内部**发布，并在该事务提交**之后**消费。
     *
     * <p>它之所以存在，是因为触发的两半不可能合成一件事：这一行必须在调用方的
     * 事务里创建，才能在失败时把整件事一起回滚；而在那个事务提交之前，
     * 又绝不能把任何东西交给执行器，否则 worker 会去找一行 READ COMMITTED
     * 根本不会让它看见的记录。在这里把行号带上，意味着 after-commit 的那一半
     * 完全不需要访问数据库——而这正是「它不得触碰数据库」这条规则
     * 得以被遵守的前提。
     */
    public record ReviewReady(long projectId, long reviewId) {
    }

    private final ReviewRepository reviews;
    private final ReviewClaimRepository claims;
    private final ProjectAccessService access;
    private final ApplicationEventPublisher publisher;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectScmIdentityAccess scmIdentities;

    ReviewService(ReviewRepository reviews, ReviewClaimRepository claims, ProjectAccessService access,
            ApplicationEventPublisher publisher, JdbcTemplate jdbc, ObjectMapper json,
            ProjectScmIdentityAccess scmIdentities) {
        this.reviews = reviews;
        this.claims = claims;
        this.access = access;
        this.publisher = publisher;
        this.jdbc = jdbc;
        this.json = json;
        this.scmIdentities = scmIdentities;
    }

    /**
     * 人工路径：触发、需求修订后的重新触发，或失败后的重试。到底是哪一种，
     * 只取决于该 PR 当前身份之下已经存在什么，因此调用方没有任何东西要选。
     *
     * <p>已 COMPLETED 的行会答 409，而不是重新开始。这是它与投递路径唯一不同的
     * 地方，而且是刻意的：3.2 规定 COMPLETED 绝不重跑、绝不覆盖，
     * 而一个明确提出这个请求的人，理应被明确告知这一点。
     */
    @Transactional
    public Review requestReview(long projectId, long pullRequestId, long actorId) {
        PullRequestIdentity pullRequest = pullRequestIn(projectId, pullRequestId);
        authorize(projectId, actorId, pullRequest);
        Review review = openOrTake(projectId, pullRequestId, pullRequest);
        return switch (review.getStatus()) {
            case PENDING, RUNNING -> review;
            case COMPLETED -> throw ApiException.conflict(
                    "This review is already complete. A completed review is never re-run; "
                            + "push a new commit or publish a new requirement revision to review again.");
            case FAILED -> retry(projectId, review);
        };
    }

    /**
     * 自动路径，由那个加入 SCM 事务的监听器调用。
     *
     * <p>用 {@code MANDATORY} 而不是 {@code REQUIRED}：它必须加入调用方的事务，
     * 绝不能自己开一个。一旦它在事务之外运行，就可能出现「PR 提交了、
     * 而它所隐含的 Review 没有提交」——正是 3.1 明令禁止的那个状态——
     * 而且这次失败会是静默的。用 MANDATORY，它就是响亮的。
     *
     * <p>与 {@link #requestReview} 不同，这里遇到已存在的 COMPLETED 行只是直接接管。
     * 同一个 webhook 的重复投递必须是无害的；在这里抛冲突，
     * 会因为一次重复的 GitHub 投递而把 PR 的更新整个回滚掉。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Review openForDelivery(long pullRequestId) {
        PullRequestIdentity pullRequest = claims.findPullRequestIdentity(pullRequestId)
                .orElseThrow(ApiException::notFound);
        return openOrTake(pullRequest.getProjectId(), pullRequestId, pullRequest);
    }

    /**
     * 以四元组为准的幂等。这次查找是 NULL 安全的：没有关联需求的 PR
     * 两侧的修订都是 null，而朴素的 {@code =} 会求值为 unknown、
     * 错过已存在的那一行，于是每一次投递都会去尝试插入一条重复记录。
     */
    private Review openOrTake(long projectId, long pullRequestId, PullRequestIdentity pullRequest) {
        // 一条尚未发布首个修订的需求，会给出「有 requirement id、却没有修订」
        // 的组合，而 review 的配对 CHECK 会拒绝这种半设状态——这是对的，
        // 因为「针对一条没有内容的需求做审查」本就不成立。
        // 这样的 PR 会被当作完全没有关联需求来审查。
        Long revisionId = pullRequest.getRequirementRevisionId();
        Long requirementId = revisionId == null ? null : pullRequest.getRequirementId();
        return reviews.findByIdentity(pullRequestId, pullRequest.getHeadSha(),
                        pullRequest.getReviewInputFingerprint(), revisionId)
                .orElseGet(() -> create(projectId, pullRequestId, pullRequest, requirementId, revisionId));
    }

    private Review create(long projectId, long pullRequestId, PullRequestIdentity pullRequest,
            Long requirementId, Long revisionId) {
        Review review = new Review(projectId, pullRequestId, pullRequest.getHeadSha(),
                pullRequest.getReviewInputFingerprint(), requirementId, revisionId);
        // pull_request 行是一个**可变的当前快照**。因此在这里、在创建该 Review
        // 的同一个事务里把它捕获下来，并不是可有可无的审计雅趣：
        // 没有这份拷贝，一个排队中的 worker 会用这一行较旧的指纹去审查一份更新的 diff。
        // 需求修订本身是不可变的，但把它们一并拷贝下来，能让所有历史读取
        // 都落在同一个事实源上，而不必从活表里去重建当初的含义。
        review.recordContextSnapshot(contextSnapshot(projectId, pullRequestId, revisionId));
        review = reviews.saveAndFlush(review);
        publisher.publishEvent(new ReviewReady(projectId, review.getId()));
        return review;
    }

    /**
     * ARCHITECTURE.md 4.2 中 {@code ReviewContext} 的不可变、且不涉及 provider 的
     * 那一部分。知识证据由 worker 在完成时补进摘要里，因为检索它需要一次外部
     * embedding 调用，而那只能发生在本事务提交之后。读取 API 会把这两半
     * 不可变的存储内容合并起来；而引擎读取需求、AC 与 diff 输入时，
     * 始终读的是这一半。
     */
    private String contextSnapshot(long projectId, long pullRequestId, Long revisionId) {
        PullRequestSnapshot pullRequest = jdbc.queryForObject("""
                SELECT p.external_number, p.base_sha, p.head_sha, p.review_input_fingerprint,
                       p.title, p.changed_files, r.provider, r.instance_identity, r.external_id
                  FROM pull_request p
                  JOIN scm_repository r ON r.project_id = p.project_id AND r.id = p.repository_id
                 WHERE p.project_id = ? AND p.id = ?
                """, (rs, row) -> new PullRequestSnapshot(
                        rs.getInt("external_number"), rs.getString("base_sha"),
                        rs.getString("head_sha"), rs.getString("review_input_fingerprint"),
                        rs.getString("title"), rs.getString("changed_files"),
                        rs.getString("provider"), rs.getString("instance_identity"),
                        rs.getString("external_id")), projectId, pullRequestId);

        ObjectNode snapshot = json.createObjectNode();
        if (revisionId == null) {
            snapshot.putNull("requirement");
        } else {
            jdbc.queryForObject("""
                    SELECT rr.requirement_id, rr.id, rr.title, rr.background, rr.description
                      FROM requirement_revision rr
                     WHERE rr.project_id = ? AND rr.id = ?
                    """, (rs, row) -> {
                        snapshot.putObject("requirement")
                                .put("id", rs.getLong("requirement_id"))
                                .put("revisionId", rs.getLong("id"))
                                .put("title", rs.getString("title"))
                                .put("background", rs.getString("background"))
                                .put("description", rs.getString("description"));
                        return Boolean.TRUE;
                    }, projectId, revisionId);
        }

        ArrayNode criteria = snapshot.putArray("acceptanceCriteria");
        if (revisionId != null) {
            jdbc.query("""
                    SELECT id, ac_key, text
                      FROM acceptance_criterion
                     WHERE project_id = ? AND requirement_revision_id = ?
                     ORDER BY sort_order, id
                    """, rs -> {
                        criteria.addObject()
                                .put("id", rs.getLong("id"))
                                .put("acKey", rs.getString("ac_key"))
                                .put("text", rs.getString("text"));
                    }, projectId, revisionId);
        }

        snapshot.putObject("pullRequest")
                .put("provider", pullRequest.provider())
                .put("instance", pullRequest.instanceIdentity())
                .put("repository", pullRequest.repositoryExternalId())
                .put("number", pullRequest.externalNumber())
                .put("baseSha", pullRequest.baseSha())
                .put("headSha", pullRequest.headSha())
                .put("inputFingerprint", pullRequest.inputFingerprint())
                .put("title", pullRequest.title());
        JsonNode changedFiles = json.readTree(pullRequest.changedFilesJson());
        snapshot.set("changedFiles", changedFiles);
        return json.writeValueAsString(snapshot);
    }

    private record PullRequestSnapshot(int externalNumber, String baseSha, String headSha,
            String inputFingerprint, String title, String changedFilesJson, String provider,
            String instanceIdentity, String repositoryExternalId) {
    }

    /**
     * 重试复用同一行，因此同一个身份的每一次尝试都留在同一行上（3.2）。
     * 被放弃 attempt 的 finding 要先删除：它们在外键里持有旧的 attempt 编号，
     * 不删掉它们，worker 随后的抢占就无法递增那个编号。
     */
    private Review retry(long projectId, Review failed) {
        claims.discardAbandonedFindings(projectId, failed.getId());
        if (claims.retryFailed(projectId, failed.getId()) != 1) {
            throw ApiException.conflict("This review is no longer failed; somebody else retried it.");
        }
        Review reset = reviews.findByProjectIdAndId(projectId, failed.getId())
                .orElseThrow(ApiException::notFound);
        publisher.publishEvent(new ReviewReady(projectId, reset.getId()));
        return reset;
    }

    /** 属于其他项目的 PR，会与一个从未存在过的 PR 答得一模一样。 */
    private PullRequestIdentity pullRequestIn(long projectId, long pullRequestId) {
        return claims.findPullRequestIdentity(pullRequestId)
                .filter(pullRequest -> pullRequest.getProjectId() == projectId)
                .orElseThrow(ApiException::notFound);
    }

    /**
     * LEADER 与 REVIEWER 可以审查本项目内的任何东西；DEVELOPER 只能触发
     * 属于自己的 PR（PRD 3）。
     *
     * <p>“属于自己”是拿 provider 的外部 user id 与该成员已核验的 SCM 身份比对
     * 判定的，绝不按用户名：用户名可以被重新分配，
     * 因此按用户名匹配会把别人的 PR 交给一个改过名的账号。
     * 没有已核验 SCM 身份的成员，匹配不到任何东西。
     */
    private void authorize(long projectId, long actorId, PullRequestIdentity pullRequest) {
        ProjectMember member = access.requireMember(projectId, actorId);
        if (member.hasRole(ProjectRole.LEADER) || member.hasRole(ProjectRole.REVIEWER)) {
            return;
        }
        if (!member.hasRole(ProjectRole.DEVELOPER)
                || !scmIdentities.isActiveAuthor(projectId, actorId,
                        pullRequest.getAuthorExternalUserId())) {
            throw ApiException.forbidden();
        }
    }
}
