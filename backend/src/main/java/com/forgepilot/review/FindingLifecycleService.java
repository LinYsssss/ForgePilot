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
 * The human handling lifecycle of a Finding, and who may perform each step.
 *
 * <p>The table below is PRD.md 3 transcribed cell by cell, including the two cells
 * that read like mistakes and are not: a LEADER may <strong>not</strong> claim a
 * finding and may <strong>not</strong> mark one fixed. PRD.md 3's LEADER column on
 * the "Finding 认领、标记已修复" row is ❌, and those two steps are the developer's
 * own record of their own work. Widening them because a LEADER "should be able to
 * do everything" would be granting a permission the specification withholds;
 * narrowing is the safe direction, widening is not.
 *
 * <p>Two rows are rulings rather than transcription, because PRD.md 3's matrix does
 * not name them. {@code VERIFIED -> CLOSED} goes to LEADER and REVIEWER as the
 * second step of verifying, and {@code REJECTED -> OPEN} goes to the same pair as
 * confirming and rejecting (design.md 3.1, 3.2). Neither ruling gives anybody a
 * step they did not already have next to it.
 *
 * <p>Every move is a conditional update whose {@code from} is matched by the
 * database, and the audit row records that matched value. Reading the status and
 * then writing was measured to produce two events that both claim to start from
 * {@code OPEN} while only one of them describes what happened (research 7.5).
 */
@Service
public class FindingLifecycleService {

    /**
     * From, to, the action it is called and who may perform it. Encoded as data so
     * that the tests can assert it cell by cell instead of restating it in prose,
     * and so that a change to authorization is a change to a table rather than to
     * control flow.
     *
     * <p>The key set is exactly the pairs {@link FindingStatus} allows; a test holds
     * the two encodings together, because a transition that existed here but not
     * there would be reachable without ever appearing in the state machine.
     */
    private static final Map<FindingStatus, Map<FindingStatus, Move>> MOVES = Map.of(
            FindingStatus.OPEN, Map.of(
                    FindingStatus.CONFIRMED, Move.byReviewers(FindingAction.CONFIRM),
                    FindingStatus.REJECTED, Move.byReviewers(FindingAction.REJECT)),
            FindingStatus.CONFIRMED, Map.of(
                    FindingStatus.REJECTED, Move.byReviewers(FindingAction.REJECT),
                    // Claiming: the developer's own, and a LEADER is refused here.
                    FindingStatus.IN_PROGRESS, Move.byDeveloper(FindingAction.CLAIM)),
            FindingStatus.IN_PROGRESS, Map.of(
                    // Marking fixed: likewise the developer's own, and likewise not a LEADER's.
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
     * Moves a finding to {@code target} and audits the move in the same transaction
     * (api-contract.md 3.2).
     *
     * <p>The role is checked against the transition the caller's read says they are
     * performing, and the update then only fires if that is still the transition
     * available. So a concurrent move cannot turn an authorized request into an
     * unauthorized one being carried out: it turns it into a 409.
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
            // Reopening carries the extra condition PRD.md 5 attaches to it: only an
            // inherited suppression may come back. A plain rejection matches nothing
            // and is refused for every role, which is what "irreversible" means.
            case REOPEN -> decisions.reopenSuppressedFinding(projectId, findingId);
            default -> decisions.moveFinding(projectId, findingId, from.name(), target.name());
        };
        if (updated != 1) {
            throw ApiException.conflict("This finding is no longer in " + from + ".");
        }

        // Same transaction as the move: a status that changed without an audit row,
        // or an audit row describing a move that did not commit, are both worse than
        // no audit at all. `from` is the value the update matched, not the one read.
        events.save(new FindingEvent(projectId, findingId, actorId, move.action(), from, target, comment));
        return new FindingStatusResult(target);
    }

    /** api-contract.md 3.4. Any member of the project may read the trail. */
    @Transactional(readOnly = true)
    public List<FindingEventView> history(long projectId, long actorId, long findingId) {
        access.requireMember(projectId, actorId);
        // Scoped by project first, so a finding from elsewhere answers exactly like
        // one that never existed rather than exposing its history.
        findings.findByProjectIdAndId(projectId, findingId).orElseThrow(ApiException::notFound);
        return events.findByProjectIdAndFindingIdOrderByCreatedAtAscIdAsc(projectId, findingId).stream()
                .map(event -> new FindingEventView(event.getId(), event.getActorId(), event.getAction(),
                        event.getFromStatus(), event.getToStatus(), event.getComment(),
                        event.getCreatedAt()))
                .toList();
    }

    /** Visible to the tests so the matrix can be asserted cell by cell rather than described. */
    static Map<FindingStatus, Map<FindingStatus, Move>> moves() {
        return MOVES;
    }

    /** One cell of PRD.md 3's matrix: what the step is called and who may take it. */
    record Move(FindingAction action, Set<ProjectRole> allowed) {

        private static Move byReviewers(FindingAction action) {
            return new Move(action, Set.of(ProjectRole.LEADER, ProjectRole.REVIEWER));
        }

        private static Move byDeveloper(FindingAction action) {
            return new Move(action, Set.of(ProjectRole.DEVELOPER));
        }
    }
}
