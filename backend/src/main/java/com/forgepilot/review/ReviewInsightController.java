package com.forgepilot.review;

import java.security.Principal;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import com.forgepilot.review.ReviewViews.RequirementCoverage;
import com.forgepilot.review.ReviewViews.ReviewCalibration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 两个从审查结果推导出来的只读视图。
 *
 * <p>覆盖度由 {@code review} 而非需求端点提供，理由与 {@link ReviewActivityController}
 * 完全相同：它是从 {@code review} 与 {@code finding} 推导出来的，而依赖箭头的方向是
 * {@code review -> requirement}。
 *
 * <p>两个读取接口对任何项目成员开放：它们说不出审查详情本身没有说过的东西。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
class ReviewInsightController {

    private final ReviewInsightService insights;
    private final UserDirectory users;

    ReviewInsightController(ReviewInsightService insights, UserDirectory users) {
        this.insights = insights;
        this.users = users;
    }

    @GetMapping("/requirements/{requirementId}/coverage")
    RequirementCoverage coverage(@PathVariable long projectId, @PathVariable long requirementId,
            Principal principal) {
        return insights.coverage(projectId, userIdOf(principal), requirementId);
    }

    @GetMapping("/review-calibration")
    ReviewCalibration calibration(@PathVariable long projectId, Principal principal) {
        return insights.calibration(projectId, userIdOf(principal));
    }

    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }
}
