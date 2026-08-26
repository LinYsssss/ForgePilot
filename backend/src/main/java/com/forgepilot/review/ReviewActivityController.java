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
 * 审查活动状态由 {@code review} 而非需求端点提供，因为它是从
 * {@code pull_request} 与 {@code review} 推导出来的，而依赖箭头的方向是
 * {@code review -> requirement}。代价是需求页面多发一次请求；
 * 另一种做法的代价则是功能依赖图成环。
 *
 * <p>两个读取接口对任何项目成员开放：活动状态说不出任何 PR 列表本身
 * 没有说过的东西。
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

    /** 以需求 id 为键，使列表页一次调用就能读到整列数据。 */
    @GetMapping("/review-activity")
    Map<Long, ActivityView> forProject(@PathVariable long projectId, Principal principal) {
        return activities.forProject(projectId, userIdOf(principal));
    }

    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }
}
