package com.forgepilot.scm;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.project.ProjectRole;
import com.forgepilot.requirement.RequirementDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human correction of a pull request's requirement association (PRD P1).
 *
 * <p>The automatic {@code REQ-<n>} link is a guess made once at ingestion, and
 * D007 gives the page the last word over it. Every correction therefore writes
 * one {@code pull_request_requirement_event} row <em>in the same transaction</em>
 * as the change: an association that moved without an audit row, or an audit row
 * describing a move that did not commit, are both worse than no audit at all.
 *
 * <p>Only a LEADER reaches this. PRD P1 also lets a DEVELOPER correct their own
 * pull request "while the current head has no final human Decision", and that
 * half is deliberately not implemented: {@code review} does not exist in this
 * batch, so "no final decision" cannot be evaluated, and a check that always
 * answers "no decision exists" would silently grant more than P1 allows.
 */
@Service
class PullRequestAssociationService {

    private final PullRequestRepository pullRequests;
    private final PullRequestRequirementEventRepository events;
    private final ProjectAccessService access;
    private final RequirementDirectory requirements;

    PullRequestAssociationService(PullRequestRepository pullRequests,
            PullRequestRequirementEventRepository events, ProjectAccessService access,
            RequirementDirectory requirements) {
        this.pullRequests = pullRequests;
        this.events = events;
        this.access = access;
        this.requirements = requirements;
    }

    /**
     * Points the pull request at {@code requirementId}, or at nothing when it is
     * null — clearing the link is a legal correction and is audited exactly like a
     * move between two requirements.
     *
     * <p>Setting the association to the value it already has is refused by
     * {@code ck_pr_requirement_event_is_a_change} rather than pre-checked here:
     * the audit table records changes only, and a request that produces no row is
     * not a correction. That conflict rolls the whole transaction back, which is
     * the point — nothing is written either way (D013.11).
     */
    @Transactional
    PullRequestResponse correct(long projectId, long actorId, long pullRequestId, Long requirementId,
            String reason) {
        access.requireRole(projectId, actorId, ProjectRole.LEADER);
        PullRequest pullRequest = pullRequests.findWithLockByProjectIdAndId(projectId, pullRequestId)
                .orElseThrow(ApiException::notFound);
        // Resolved through requirement's read-only facade and filtered by this pull
        // request's own project (D015.6, D013.2). The composite foreign key would
        // also refuse a foreign id, but only by failing the insert with a message
        // naming a constraint; asking first turns it into an answer the caller can
        // act on. An id from another project and an id that was never issued are
        // indistinguishable here, so neither confirms the other project's contents.
        if (requirementId != null && !requirements.existsInProject(projectId, requirementId)) {
            throw ApiException.unprocessable("That requirement does not belong to this project.");
        }

        Long previous = pullRequest.getRequirementId();
        pullRequest.linkRequirement(requirementId);
        events.save(PullRequestRequirementEvent.userCorrection(projectId, pullRequest.getId(),
                previous, requirementId, actorId, reason));
        return PullRequestResponse.of(pullRequest);
    }
}
