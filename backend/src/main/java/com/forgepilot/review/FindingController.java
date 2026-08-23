package com.forgepilot.review;

import java.security.Principal;
import java.util.List;

import com.forgepilot.auth.AccountView;
import com.forgepilot.auth.UserDirectory;
import com.forgepilot.common.ApiException;
import com.forgepilot.review.ReviewViews.FindingEventView;
import com.forgepilot.review.ReviewViews.FindingStatusResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finding 的人工生命周期（api-contract.md 3.2、3.4）。
 *
 * <p>这里没有「指派」端点。PRD.md 3 只授予了“Finding 认领”这一项，
 * 因此认领会把认领者设为处理人，而没有人能被别人指派——
 * 少一个端点，也少一个授权面（design.md 3.3）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/findings/{findingId}")
class FindingController {

    private final FindingLifecycleService lifecycle;
    private final UserDirectory users;

    FindingController(FindingLifecycleService lifecycle, UserDirectory users) {
        this.lifecycle = lifecycle;
        this.users = users;
    }

    @PostMapping("/status")
    FindingStatusResult move(@PathVariable long projectId, @PathVariable long findingId,
            @Valid @RequestBody StatusRequest request, Principal principal) {
        return lifecycle.move(projectId, userIdOf(principal), findingId, request.status(),
                request.comment());
    }

    @GetMapping("/events")
    List<FindingEventView> events(@PathVariable long projectId, @PathVariable long findingId,
            Principal principal) {
        return lifecycle.history(projectId, userIdOf(principal), findingId);
    }

    private long userIdOf(Principal principal) {
        return users.byUsername(principal.getName()).map(AccountView::id)
                .orElseThrow(ApiException::notFound);
    }

    /** 传的是目标状态而非「流转」：起点是那一行当时实际持有的状态。 */
    record StatusRequest(@NotNull FindingStatus status, @Size(max = 2000) String comment) {
    }
}
