package com.forgepilot.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.forgepilot.review.ReviewOutput.FindingCandidate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 判定本轮每一条 finding 是从哪来的（ARCHITECTURE.md 3.6、D009）。
 *
 * <p>三条规则，按此顺序，且顺序固定为
 * {@code SUPPRESSED > PERSISTING > NEW}：
 *
 * <ol>
 * <li><strong>SUPPRESSED</strong>——在本 PR 中针对这个 {@code finding_key}
 * 的最近一次人工判断是驳回，且两个哈希都没有变化。只有此时驳回才被继承，
 * 因此一个抑制项无法比它当初所针对的代码或需求活得更久。</li>
 * <li><strong>PERSISTING</strong>——本 PR 紧邻的上一次 COMPLETED Review
 * 报告过同一个 key。它会重新从 {@code OPEN} 开始：问题持续存在，
 * 并不意味着有人对它作出过判断。</li>
 * <li><strong>NEW</strong>——两者皆非。</li>
 * </ol>
 *
 * <p>反方向**刻意**不对称。上一轮报告过、而本轮没有报告的 key，
 * 会在输出时被推导为 {@link #notReported(long, long, long) NOT_REPORTED}
 * 且从不存储：“模型这次没提它”不能作为“有人修好了它”的证据，
 * 而存下一个 FIXED 等于让机器去关闭一条人的 finding。
 */
@Service
public class FindingContinuityCalculator {

    private final FindingLineageRepository lineage;

    FindingContinuityCalculator(FindingLineageRepository lineage) {
        this.lineage = lineage;
    }

    /**
     * 一次 Review 中每个候选项的血缘，以 {@code finding_key} 为键
     * （它在单个 Review 内唯一——{@code uq_finding_review_key}）。
     *
     * <p>在 finding 被写入**之前**调用，因为 continuity 与
     * {@code carried_from_finding_id} 正是那些待写入行上的列。
     */
    @Transactional(readOnly = true)
    public Map<String, Lineage> lineageOf(long projectId, long pullRequestId, long reviewId,
            List<FindingCandidate> candidates) {
        Map<String, Finding> previousRound = previousRound(projectId, pullRequestId, reviewId).stream()
                .collect(Collectors.toMap(Finding::getFindingKey, finding -> finding));
        Map<String, Lineage> byKey = new LinkedHashMap<>();
        for (FindingCandidate candidate : candidates) {
            byKey.put(candidate.findingKey(), decide(projectId, pullRequestId, candidate,
                    previousRound.get(candidate.findingKey())));
        }
        return byKey;
    }

    /**
     * 上一轮报告过、而本轮没有报告的那些。读取时推导，从不存储：
     * {@code ck_finding_status} 里没有 {@code NOT_REPORTED} 这个取值，
     * 而 3.6.3 禁止把它变成一次自动修复。
     *
     * <p>它从数据库读取本次 Review 自己的 finding，
     * 因此回答的是一个**已经写入**的 Review，而不是一个正在计算中的 Review。
     */
    @Transactional(readOnly = true)
    public List<Finding> notReported(long projectId, long pullRequestId, long reviewId) {
        Set<String> reportedNow = lineage.findFindingsOfReview(projectId, reviewId).stream()
                .map(Finding::getFindingKey)
                .collect(Collectors.toSet());
        return previousRound(projectId, pullRequestId, reviewId).stream()
                .filter(previous -> !reportedNow.contains(previous.getFindingKey()))
                .toList();
    }

    private Lineage decide(long projectId, long pullRequestId, FindingCandidate candidate, Finding matched) {
        Optional<Finding> rejected = lineage
                .findMostRecentlyRejectedFindingId(projectId, pullRequestId, candidate.findingKey())
                .flatMap(findingId -> lineage.findFinding(projectId, findingId));
        if (rejected.isPresent() && inheritable(rejected.get(), candidate)) {
            return new Lineage(FindingContinuity.SUPPRESSED, rejected.get().getId(), FindingStatus.REJECTED);
        }
        if (matched != null) {
            return new Lineage(FindingContinuity.PERSISTING, matched.getId(), FindingStatus.OPEN);
        }
        return new Lineage(FindingContinuity.NEW, null, FindingStatus.OPEN);
    }

    /**
     * 两个哈希都要，而不是任选其一。只看 {@code evidence_hash}，
     * 会在它当初所对照的验收条件被改写之后仍继续抑制；
     * 只看 {@code basis_hash}，则会在代码发生变动之后仍继续抑制。
     */
    private static boolean inheritable(Finding rejected, FindingCandidate candidate) {
        return rejected.getEvidenceHash().equals(candidate.evidenceHash())
                && rejected.getBasisHash().equals(candidate.basisHash());
    }

    /** 上一次 COMPLETED 轮次的 finding，按行序返回；若这是第一轮则为空。 */
    private List<Finding> previousRound(long projectId, long pullRequestId, long reviewId) {
        return lineage.findPreviousCompletedReviewId(projectId, pullRequestId, reviewId)
                .map(previousId -> lineage.findFindingsOfReview(projectId, previousId))
                .orElseGet(List::of);
    }

    /**
     * 一条 finding 从哪来，以及它以什么状态起步。
     *
     * <p>构造器声明了与数据库同样声明的那两条不变式：
     * 当且仅当确有继承发生时血缘才存在
     * （{@code ck_finding_carried_from_matches_continuity}），
     * 且只有被继承的抑制项才会一出生就是被驳回状态。
     */
    public record Lineage(FindingContinuity continuity, Long carriedFromFindingId, FindingStatus initialStatus) {

        public Lineage {
            if ((continuity == FindingContinuity.NEW) != (carriedFromFindingId == null)) {
                throw new IllegalArgumentException("Only an inherited finding carries a source finding.");
            }
            if ((continuity == FindingContinuity.SUPPRESSED) != (initialStatus == FindingStatus.REJECTED)) {
                throw new IllegalArgumentException("Only an inherited suppression starts rejected.");
            }
        }
    }
}
