package com.forgepilot.review;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectMember;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.review.ReviewViews.FindingEventView;
import com.forgepilot.review.ReviewViews.FindingStatusResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一条 Finding 的人工处理生命周期，以及每一步由谁执行。
 *
 * <p>下面那张表是 PRD.md 3 逐格抄录的结果，包括那两格看上去像笔误、
 * 但其实不是的：LEADER <strong>不能</strong>认领 finding，
 * 也<strong>不能</strong>把它标记为已修复。PRD.md 3 中
 * “Finding 认领、标记已修复”那一行的 LEADER 列就是 ❌，
 * 而这两步是开发者对自己工作的自我记录。因为“LEADER 理应什么都能干”
 * 就去放宽它，等于授予了规格明确保留的权限；
 * 收紧是安全方向，放宽不是。
 *
 * <p>有两行是**裁定**而非抄录，因为 PRD.md 3 的矩阵没有点到它们。
 * {@code VERIFIED -> CLOSED} 作为复核的第二步归 LEADER 与 REVIEWER；
 * {@code REJECTED -> OPEN} 与确认、驳回一样归同一对角色
 * （design.md 3.1、3.2）。这两条裁定都没有给任何人一个他本来在旁边
 * 还没有的步骤。
 *
 * <p>每一次变动都是条件更新，其 {@code from} 由数据库匹配，
 * 而审计行记录的正是那个被匹配到的值。实测表明，
 * 「先读状态再写入」会产生两个都声称自己从 {@code OPEN} 出发的事件，
 * 而其中只有一个描述了真正发生的事（research 7.5）。
 */
@Service
public class FindingLifecycleService {

    /**
     * 起点、终点、这一步叫什么，以及谁可以执行它。写成数据，
     * 使测试可以逐格断言而不必用散文再复述一遍；
     * 也使一次授权变更成为一次**表的**变更，而不是控制流的变更。
     *
     * <p>它的键集恰好就是 {@link FindingStatus} 允许的那些状态对；
     * 有一个测试把两处编码绑在一起对照，因为一个只存在于这里、
     * 却不存在于状态机里的流转，会成为一条根本没在状态机中出现过、
     * 却依然可达的路径。
     */
    private static final Map<FindingStatus, Map<FindingStatus, Move>> MOVES = Map.of(
            FindingStatus.OPEN, Map.of(
                    FindingStatus.CONFIRMED, Move.byReviewers(FindingAction.CONFIRM),
                    FindingStatus.REJECTED, Move.byReviewers(FindingAction.REJECT)),
            FindingStatus.CONFIRMED, Map.of(
                    FindingStatus.REJECTED, Move.byReviewers(FindingAction.REJECT),
                    // 认领：这是开发者自己的动作，LEADER 在这里会被拒绝。
                    FindingStatus.IN_PROGRESS, Move.byDeveloper(FindingAction.CLAIM)),
            FindingStatus.IN_PROGRESS, Map.of(
                    // 标记已修复：同样是开发者自己的动作，同样不属于 LEADER。
                    FindingStatus.FIXED, Move.byDeveloper(FindingAction.MARK_FIXED)),
            FindingStatus.FIXED, Map.of(
                    FindingStatus.VERIFIED, Move.byReviewers(FindingAction.VERIFY),
                    FindingStatus.IN_PROGRESS, Move.byReviewers(FindingAction.SEND_BACK)),
            FindingStatus.VERIFIED, Map.of(
                    FindingStatus.CLOSED, Move.byReviewers(FindingAction.CLOSE)),
            FindingStatus.REJECTED, Map.of(
                    FindingStatus.OPEN, Move.byReviewers(FindingAction.REOPEN)),
            FindingStatus.CLOSED, Map.<FindingStatus, Move>of());

    private final FindingRepository findings;
    private final FindingEventRepository events;
    private final DecisionRepository decisions;
    private final ProjectAccessService access;

    FindingLifecycleService(FindingRepository findings, FindingEventRepository events,
            DecisionRepository decisions, ProjectAccessService access) {
        this.findings = findings;
        this.events = events;
        this.decisions = decisions;
        this.access = access;
    }

    /**
     * 把一条 finding 移动到 {@code target}，并在同一个事务里审计这次移动
     * （api-contract.md 3.2）。
     *
     * <p>角色是对照「调用方读到的、他自称正在执行的那次流转」来检查的，
     * 而更新只有在那次流转**仍然可用**时才会真正发生。因此并发的移动
     * 不可能把一个已授权的请求变成一次未授权的执行：它只会把它变成 409。
     */
    @Transactional
    public FindingStatusResult move(long projectId, long actorId, long findingId, FindingStatus target,
            String comment) {
        ProjectMember member = access.requireMember(projectId, actorId);
        Finding finding = findings.findByProjectIdAndId(projectId, findingId)
                .orElseThrow(ApiException::notFound);

        FindingStatus from = finding.getStatus();
        Move move = MOVES.get(from).get(target);
        if (move == null) {
            throw ApiException.conflict("A finding cannot move from " + from + " to " + target + ".");
        }
        if (!move.allowed().contains(member.getRole())) {
            throw ApiException.forbidden();
        }

        int updated = switch (move.action()) {
            case CLAIM -> decisions.claimFinding(projectId, findingId, actorId);
            // 重开带着 PRD.md 5 附加给它的那个额外条件：只有被继承的抑制项
            // 才能回来。普通的驳回匹配不到任何行，对任何角色都会被拒绝，
            // 而这正是“不可逆”的含义。
            case REOPEN -> decisions.reopenSuppressedFinding(projectId, findingId);
            default -> decisions.moveFinding(projectId, findingId, from.name(), target.name());
        };
        if (updated != 1) {
            throw ApiException.conflict("This finding is no longer in " + from + ".");
        }

        // 与移动处于同一个事务：状态变了却没有审计行，或者审计行描述了一次
        // 并未提交的移动，两者都比完全没有审计更糟。`from` 取的是**更新所匹配到**
        // 的值，而不是此前读到的值。
        events.save(new FindingEvent(projectId, findingId, actorId, move.action(), from, target, comment));
        return new FindingStatusResult(target);
    }

    /** api-contract.md 3.4。项目内的任何成员都可以读取这条审计轨迹。 */
    @Transactional(readOnly = true)
    public List<FindingEventView> history(long projectId, long actorId, long findingId) {
        access.requireMember(projectId, actorId);
        // 先按项目限定，因此来自别处的 finding 会与一个从未存在过的
        // finding 答得一模一样，而不会暴露它的历史。
        findings.findByProjectIdAndId(projectId, findingId).orElseThrow(ApiException::notFound);
        return events.findByProjectIdAndFindingIdOrderByCreatedAtAscIdAsc(projectId, findingId).stream()
                .map(event -> new FindingEventView(event.getId(), event.getActorId(), event.getAction(),
                        event.getFromStatus(), event.getToStatus(), event.getComment(),
                        event.getCreatedAt()))
                .toList();
    }

    /** 对测试可见，以便逐格断言这张矩阵，而不是用文字去描述它。 */
    static Map<FindingStatus, Map<FindingStatus, Move>> moves() {
        return MOVES;
    }

    /** PRD.md 3 矩阵中的一格：这一步叫什么，以及谁可以执行它。 */
    record Move(FindingAction action, Set<ProjectRole> allowed) {

        private static Move byReviewers(FindingAction action) {
            return new Move(action, Set.of(ProjectRole.LEADER, ProjectRole.REVIEWER));
        }

        private static Move byDeveloper(FindingAction action) {
            return new Move(action, Set.of(ProjectRole.DEVELOPER));
        }
    }
}
