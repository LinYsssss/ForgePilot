package com.forgepilot.review;

import java.security.Principal;
import java.util.Map;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import com.forgepilot.review.ReviewActivityService.ActivityView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review activity is served from {@code review}, not from the requirement
 * endpoints, because it is derived from {@code pull_request} and {@code review}
 * and the dependency arrow runs {@code review -> requirement} (design.md 2.1).
 * The cost is one extra request for the requirement pages; the alternative is a
 * cycle in the feature graph.
 *
 * <p>Both reads are open to any project member: activity says nothing that the
 * pull request list does not already say.
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
class ReviewActivityController {

    private final ReviewActivityService activities;
    private final UserDirectory users;

    ReviewActivityController(ReviewActivityService activities, UserDirectory users) {
        this.activities = activities;
        this.users = users;
    }

    @GetMapping("/requirements/{requirementId}/review-activity")
    ActivityView forRequirement(@PathVariable long projectId, @PathVariable long requirementId,
            Principal principal) {
        return activities.forRequirement(projectId, userIdOf(principal), requirementId);
    }

    /** Keyed by requirement id, so a list page reads the whole column in one call. */
    @GetMapping("/review-activity")
    Map<Long, ActivityView> forProject(@PathVariable long projectId, Principal principal) {
        return activities.forProject(projectId, userIdOf(principal));
    }

    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }
}
