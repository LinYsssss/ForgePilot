package com.forgepilot.review;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.review.ReviewViews.DecisionResult;
import com.forgepilot.review.ReviewViews.ProjectReviewRow;
import com.forgepilot.review.ReviewViews.FindingView;
import com.forgepilot.review.ReviewViews.ReviewDetail;
import com.forgepilot.review.ReviewViews.ReviewSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 对一次 Review 的一次性人工裁定，以及展示它的那些读取路径。
 *
 * <p>ARCHITECTURE.md 3.1 把这次写入规定得一字不差：锁住 {@code pull_request} 行、
 * 逐条检查六项前置条件，然后以 {@code decision = 'PENDING'} 为条件做更新，
 * 并把「影响行数不等于 1」一律当作冲突。它同时禁止了两种看上去等价的捷径——
 * 一次朴素的 {@code EXISTS} 检查，或者一次无条件保存。本类逐字遵循那句话，
 * 因为它的每一个从句都经实测证明是要紧的：
 *
 * <ul>
 * <li>没有 PR 行锁时，关于 head、指纹与需求修订的那几条前置条件会针对一份
 * 过期的 PR 快照求值，于是一个 SCM 早已越过的 head 拿到了 {@code APPROVE}。</li>
 * <li>没有条件更新时就没有影响行数，因而也就没有诚实的依据去给并发决策
 * 返回那个它必须收到的 409。</li>
 * </ul>
 *
 * <p>决策不可覆盖、不可撤销、不可改写，因此这里没有更新路径，
 * 也没有任何途径能走到一条更新路径上去。
 */
@Service
public class ReviewDecisionService {

    private final ReviewRepository reviews;
    private final FindingRepository findings;
    private final DecisionRepository decisions;
    private final ProjectAccessService access;
    private final ObjectMapper json;

    ReviewDecisionService(ReviewRepository reviews, FindingRepository findings,
            DecisionRepository decisions, ProjectAccessService access, ObjectMapper json) {
        this.reviews = reviews;
        this.findings = findings;
        this.decisions = decisions;
        this.access = access;
        this.json = json;
    }

    /**
     * 记录最终的人工裁定（API.md）。
     *
     * <p>仅限 LEADER 与 REVIEWER。DEVELOPER 可以触发审查、也可以修复它发现的问题，
     * 但 PRD.md 3 不允许他们把它关掉——即便是在自己的 PR 上也不行。
     */
    @Transactional
    public DecisionResult decide(long projectId, long actorId, long reviewId, ReviewDecision decision,
            String comment) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER, ProjectRole.REVIEWER);
        // 放在鉴权之后，使项目外的调用方无从得知任何信息，
        // 连「你的请求体格式是对的」这件事都不会泄露。
        if (decision != ReviewDecision.APPROVE && decision != ReviewDecision.REQUEST_CHANGES) {
            throw ApiException.unprocessable("A decision is either APPROVE or REQUEST_CHANGES.");
        }

        // 单独读出父级 id，以便在加载 Review 本身之前就把锁拿到手。
        // 它是不可变的，因此提前读取是安全的。
        long pullRequestId = decisions.pullRequestIdOf(projectId, reviewId)
                .orElseThrow(ApiException::notFound);
        String currentHead = decisions.lockPullRequestAndReadHead(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        String currentFingerprint = decisions.currentReviewInputFingerprint(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        Long currentRevisionId = decisions.currentRequirementRevisionId(projectId, pullRequestId)
                .orElse(null);

        // 到这一步才加载：在 READ COMMITTED 之下，这是本事务对该行的首次读取，
        // 因此它是一次**在加锁之后**取得的新鲜读取。若在加锁之前就读，
        // 竞争中的失败方仍然会看到 PENDING。
        Review review = reviews.findByProjectIdAndId(projectId, reviewId)
                .orElseThrow(ApiException::notFound);

        checkPreconditions(review, currentHead, currentFingerprint, currentRevisionId);

        int updated = decisions.decideIfStillPending(projectId, reviewId, decision.name(), actorId, comment);
        if (updated != 1) {
            // 那六项条件在片刻之前还成立，现在不成立了。这里既不写入也不重试：
            // 一次最终的人工裁定，不是可以替调用方去重新尝试的东西。
            throw ApiException.conflict("This review was decided or moved on concurrently.");
        }

        Review decided = reviews.findByProjectIdAndId(projectId, reviewId)
                .orElseThrow(ApiException::notFound);
        return new DecisionResult(decided.getDecision(), decided.getDecisionBy(), decided.getDecisionAt());
    }

    /** ARCHITECTURE.md 3.1 的六项前置条件，按其原有顺序，使拒绝能够自报家门。 */
    private void checkPreconditions(Review review, String currentHead, String currentFingerprint,
            Long currentRevisionId) {
        if (review.getStatus() != ReviewStatus.COMPLETED) {
            throw ApiException.conflict("Only a completed review can be decided.");
        }
        if (review.getDecision() != ReviewDecision.PENDING) {
            throw ApiException.conflict("This review already carries a final decision.");
        }
        if (!currentHead.equals(review.getHeadSha())) {
            throw ApiException.conflict("The pull request has moved to a different head since this review.");
        }
        if (!currentFingerprint.equals(review.getReviewInputFingerprint())) {
            throw ApiException.conflict("The pull request's review inputs have changed since this review.");
        }
        // 有意做成 NULL 安全的：「两侧都没有需求修订」算作匹配，
        // 这里用 equals() 比较，与 SQL 里 IS NOT DISTINCT FROM 表达的是同一条规则。
        if (!Objects.equals(currentRevisionId, review.getRequirementRevisionId())) {
            throw ApiException.conflict("The pull request now points at a different requirement revision.");
        }
        // 从数据行中推导，对照该 PR 当前的 head。改 base、改关联、改需求修订
        // 或重新同步 diff 都解不开它；只有一个新的 head SHA 才能。
        if (reviews.existsByProjectIdAndPullRequestIdAndHeadShaAndDecision(
                review.getProjectId(), review.getPullRequestId(), currentHead,
                ReviewDecision.REQUEST_CHANGES)) {
            throw ApiException.conflict("This head already has changes requested; only a new head clears it.");
        }
    }

    /** 项目内的任何成员都可以读取一次审查。 */
    @Transactional(readOnly = true)
    public ReviewDetail detail(long projectId, long actorId, long reviewId) {
        access.requireMember(projectId, actorId);
        Review review = reviews.findByProjectIdAndId(projectId, reviewId)
                .orElseThrow(ApiException::notFound);
        PullRequestInputs inputs = inputsOf(projectId, review.getPullRequestId());

        List<Finding> rows = findings.findByProjectIdAndReviewIdOrderByIdAsc(projectId, reviewId);
        Map<Long, String> acKeys = acKeysOf(projectId, rows);
        JsonNode summary = parse(review.getSummaryJson());

        return new ReviewDetail(review.getId(), review.getPullRequestId(), review.getHeadSha(),
                review.getReviewInputFingerprint(), review.getRequirementId(),
                review.getRequirementRevisionId(), review.getStatus(), review.getDecision(),
                review.getDecisionBy(), review.getDecisionAt(), review.getDecisionComment(),
                inputs.matches(review), contextSnapshot(review, summary),
                summary == null ? null : summary.get("coverage"),
                summary == null ? null : summary.get("acVerdicts"),
                rows.stream().map(finding -> view(finding, acKeys)).toList(),
                review.getEngine(), review.getPromptVersion(), review.getModel(),
                review.getExecutionAttempt());
    }

    /**
     * 本 PR 的全部审查，按 {@code (created_at, id)}
     * 从旧到新排列，使每次运行得到的顺序都相同。
     */
    @Transactional(readOnly = true)
    public List<ReviewSummary> history(long projectId, long actorId, long pullRequestId) {
        access.requireMember(projectId, actorId);
        PullRequestInputs inputs = inputsOf(projectId, pullRequestId);
        return reviews.findByProjectIdAndPullRequestIdOrderByCreatedAtAscIdAsc(projectId, pullRequestId)
                .stream()
                .map(review -> new ReviewSummary(review.getId(), review.getHeadSha(),
                        review.getRequirementRevisionId(), review.getStatus(), review.getDecision(),
                        inputs.matches(review), review.getCreatedAt()))
                .toList();
    }

    /**
     * 项目内的全部审查，最新在前——也就是代码审查页面的那份列表。
     *
     * <p>{@code isCurrent} 是**按 PR** 而不是按 review 推导的，
     * 因此一个「审查很多、PR 很少」的项目，每个 PR 只付出一次输入读取，
     * 而不是每一行都付出一次。
     */
    @Transactional(readOnly = true)
    public List<ProjectReviewRow> listForProject(long projectId, long actorId) {
        access.requireMember(projectId, actorId);
        Map<Long, Integer> numbers = new HashMap<>();
        for (Object[] row : decisions.pullRequestNumbers(projectId)) {
            numbers.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        Map<Long, PullRequestInputs> inputs = new HashMap<>();
        return reviews.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
                .map(review -> new ProjectReviewRow(review.getId(), review.getPullRequestId(),
                        numbers.getOrDefault(review.getPullRequestId(), 0), review.getHeadSha(),
                        review.getRequirementId(), review.getStatus(), review.getDecision(),
                        inputs.computeIfAbsent(review.getPullRequestId(),
                                pullRequest -> inputsOf(projectId, pullRequest)).matches(review),
                        review.getCreatedAt()))
                .toList();
    }

    private PullRequestInputs inputsOf(long projectId, long pullRequestId) {
        String head = decisions.currentHeadSha(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        String fingerprint = decisions.currentReviewInputFingerprint(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        return new PullRequestInputs(head, fingerprint,
                decisions.currentRequirementRevisionId(projectId, pullRequestId).orElse(null));
    }

    private Map<Long, String> acKeysOf(long projectId, List<Finding> rows) {
        Collection<Long> acIds = rows.stream().map(Finding::getAcId).filter(Objects::nonNull).distinct().toList();
        if (acIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> keys = new HashMap<>();
        for (Object[] row : decisions.acceptanceCriterionKeys(projectId, acIds)) {
            keys.put(((Number) row[0]).longValue(), (String) row[1]);
        }
        return keys;
    }

    private static FindingView view(Finding finding, Map<Long, String> acKeys) {
        return new FindingView(finding.getId(), finding.getFindingType(), finding.getPath(),
                finding.getLine(), finding.getEvidence(),
                finding.getCategory(), finding.getExplanation(), finding.getSuggestion(),
                finding.getConfidence(),
                finding.getStatus(), finding.getContinuity(),
                finding.getRequirementId(), finding.getRequirementRevisionId(), finding.getAcId(),
                finding.getAcId() == null ? null : acKeys.get(finding.getAcId()),
                finding.getAssigneeId(), finding.getCarriedFromFindingId(), finding.getFindingKey(),
                finding.getEvidenceHash(), finding.getBasisHash());
    }

    /** 缺席就保持缺席：一份空快照与一份不存在的快照不是同一个答案。 */
    private JsonNode parse(String stored) {
        return stored == null ? null : json.readTree(stored);
    }

    /**
     * Review 的上下文分成「完成时即不可变」的两半存储，理由是因果性的：
     * 需求/AC/PR/diff 这些输入是在创建该 Review 的那个 SCM 事务里复制下来的，
     * 而知识证据与截断计划要等提交之后的 worker 完成检索与分批才存在。
     * 对外的上下文是这两半的并集，**绝不是**从该 PR 当前的关联关系
     * 或变更文件行重新拼出来的。
     */
    private JsonNode contextSnapshot(Review review, JsonNode summary) {
        JsonNode stored = parse(review.getContextSnapshotJson());
        if (stored == null || !stored.isObject()) {
            return stored;
        }
        ObjectNode combined = (ObjectNode) stored.deepCopy();
        if (summary == null) {
            combined.set("knowledgeEvidence", json.createArrayNode());
            combined.putNull("truncation");
        } else {
            JsonNode evidence = summary.get("knowledgeEvidence");
            combined.set("knowledgeEvidence",
                    evidence == null ? json.createArrayNode() : evidence.deepCopy());
            JsonNode coverage = summary.get("coverage");
            if (coverage == null) {
                combined.putNull("truncation");
            } else {
                combined.set("truncation", coverage.deepCopy());
            }
        }
        return combined;
    }

    /**
     * 该 PR 当下的输入，以及据此推导 {@code isCurrent} 的过程。
     * 需求 <em>id</em> 刻意不在其中：决定一次审查是否仍然适用的，
     * 只有 head、diff 指纹与需求修订三者。
     */
    private record PullRequestInputs(String headSha, String fingerprint, Long requirementRevisionId) {

        boolean matches(Review review) {
            return headSha.equals(review.getHeadSha())
                    && fingerprint.equals(review.getReviewInputFingerprint())
                    && Objects.equals(requirementRevisionId, review.getRequirementRevisionId());
        }
    }
}
