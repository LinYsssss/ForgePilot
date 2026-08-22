package com.forgepilot.review;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.forgepilot.common.ApiException;
import com.forgepilot.project.ProjectAccessService;
import com.forgepilot.requirement.RequirementDirectory;
import com.forgepilot.review.ReviewActivityRepository.CurrentReview;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Review activity, the read-only derived quantity of PRD.md 5. It is computed on
 * every read and stored nowhere: there is no {@code IN_REVIEW} requirement status
 * and no {@code INVALIDATED} review status, because a stored copy would have to
 * be invalidated by every head push, diff change and published revision, and the
 * one that was missed would be a lie the UI could not detect.
 *
 * <p>It lives in {@code review} rather than in {@code requirement} because it
 * needs {@code pull_request} and {@code review} together, and ARCHITECTURE.md 1.1
 * runs the dependency arrow {@code review -> requirement}. Having
 * {@code requirement} ask {@code review} would close a cycle in the feature graph
 * (design.md 2.1).
 */
@Service
@Transactional(readOnly = true)
public class ReviewActivityService {

    /**
     * PRD.md 5's six-row mapping table, as data rather than an if-chain, in the
     * same shape as {@code RequirementService.ALLOWED_TARGETS}.
     *
     * <p>Only the reachable pairs are listed. A decision may only be written to a
     * COMPLETED Review (ARCHITECTURE.md 3.1 precondition 1) and
     * {@code ck_review_decision_needs_completion} makes the other six pairs
     * unstorable, so a miss below means that CHECK is gone rather than that a case
     * was forgotten.
     *
     * <p>The three PENDINGs here are three different facts and the types keep them
     * apart: {@code ReviewStatus.PENDING} is "queued, unclaimed",
     * {@code ReviewDecision.PENDING} is "no human verdict yet", and
     * {@code PullRequestActivity.PENDING} is what the first of those two produces.
     * Reading the decision instead of the status would report {@code PENDING} for
     * every finished review awaiting a human and swallow {@code REVIEWING} whole.
     */
    private static final Map<ReviewState, PullRequestActivity> ACTIVITY_BY_STATE = Map.of(
            new ReviewState(ReviewStatus.PENDING, ReviewDecision.PENDING),
            PullRequestActivity.PENDING,
            new ReviewState(ReviewStatus.RUNNING, ReviewDecision.PENDING),
            PullRequestActivity.REVIEWING,
            // Finished, but still waiting for a person: PRD.md 5's REVIEWING row
            // covers this too, and it is not PENDING.
            new ReviewState(ReviewStatus.COMPLETED, ReviewDecision.PENDING),
            PullRequestActivity.REVIEWING,
            new ReviewState(ReviewStatus.COMPLETED, ReviewDecision.APPROVE),
            PullRequestActivity.APPROVED,
            new ReviewState(ReviewStatus.COMPLETED, ReviewDecision.REQUEST_CHANGES),
            PullRequestActivity.CHANGES_REQUESTED,
            new ReviewState(ReviewStatus.FAILED, ReviewDecision.PENDING),
            PullRequestActivity.FAILED);

    private final ReviewActivityRepository activities;
    private final ProjectAccessService access;
    private final RequirementDirectory requirements;

    ReviewActivityService(ReviewActivityRepository activities, ProjectAccessService access,
            RequirementDirectory requirements) {
        this.activities = activities;
        this.access = access;
        this.requirements = requirements;
    }

    /**
     * One requirement's activity. A requirement belonging to another project
     * answers exactly like one that was never created, so ids cannot be probed
     * across projects.
     */
    public ActivityView forRequirement(long projectId, long userId, long requirementId) {
        access.requireMember(projectId, userId);
        if (!requirements.existsInProject(projectId, requirementId)) {
            throw ApiException.notFound();
        }
        return ActivityView.of(activities.ofRequirement(projectId, requirementId).stream()
                .map(ReviewActivityService::activityOf)
                .toList());
    }

    /**
     * Every requirement in the project, so a list page fetches activity once
     * instead of once per row.
     */
    public Map<Long, ActivityView> forProject(long projectId, long userId) {
        access.requireMember(projectId, userId);
        Map<Long, List<PullRequestActivity>> perRequirement = new LinkedHashMap<>();
        activities.requirementIds(projectId)
                .forEach(requirementId -> perRequirement.put(requirementId, new ArrayList<>()));
        for (CurrentReview row : activities.ofProject(projectId)) {
            if (row.requirementId() != null) {
                // The pull request's composite foreign key already proves the
                // requirement is in this project, so the list is always there.
                perRequirement.get(row.requirementId()).add(activityOf(row));
            }
        }
        Map<Long, ActivityView> views = new LinkedHashMap<>();
        perRequirement.forEach((requirementId, subs) -> views.put(requirementId, ActivityView.of(subs)));
        return views;
    }

    /**
     * One pull request's activity. The return type is the six-valued enum on
     * purpose: {@code NO_PR} and {@code MIXED} are answers about a requirement and
     * cannot mean anything here, so they are not expressible.
     */
    static PullRequestActivity activityOf(CurrentReview row) {
        if (!row.hasCurrentReview()) {
            // PRD.md 5, first row: nothing matches the current head, fingerprint and
            // revision. This is also what a published revision or a changed diff
            // produces, which is the whole point of deriving instead of storing.
            return PullRequestActivity.REVIEW_REQUIRED;
        }
        PullRequestActivity activity = ACTIVITY_BY_STATE.get(new ReviewState(row.status(), row.decision()));
        if (activity == null) {
            throw new IllegalStateException("Review is " + row.status() + " with decision " + row.decision()
                    + ", which ck_review_decision_needs_completion should have made unstorable.");
        }
        return activity;
    }

    /** The two orthogonal columns PRD.md 5 reads, as one lookup key. */
    private record ReviewState(ReviewStatus status, ReviewDecision decision) {
    }

    /**
     * A requirement's aggregated activity together with the per-state counts.
     *
     * <p>The counts are always present and always carry all six
     * single-pull-request values, zeros included (design.md 2.8). PRD.md 5 only
     * demands them when the aggregate is {@code MIXED}; always sending them costs
     * a few bytes and removes an entire class of missing-key branch from the
     * client.
     */
    public record ActivityView(RequirementActivity activity, Map<PullRequestActivity, Integer> counts) {

        private static ActivityView of(List<PullRequestActivity> perPullRequest) {
            Map<PullRequestActivity, Integer> counts = new EnumMap<>(PullRequestActivity.class);
            for (PullRequestActivity value : PullRequestActivity.values()) {
                counts.put(value, 0);
            }
            perPullRequest.forEach(activity -> counts.merge(activity, 1, Integer::sum));
            return new ActivityView(RequirementActivity.aggregate(perPullRequest), counts);
        }
    }
}
