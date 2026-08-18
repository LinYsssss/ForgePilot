package com.example.codereview.dashboard;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.dashboard.DashboardDtos.MetricsResponse;
import com.example.codereview.dashboard.DashboardDtos.WorkbenchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class DashboardController {

    private final DashboardService dashboardService;
    private final CurrentUserProvider currentUserProvider;

    public DashboardController(DashboardService dashboardService, CurrentUserProvider currentUserProvider) {
        this.dashboardService = dashboardService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/workbench")
    public ApiResponse<WorkbenchResponse> workbench(@PathVariable Long projectId,
                                                    @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(dashboardService.workbench(
                projectId, currentUserProvider.getRequired().userId(), limit));
    }

    @GetMapping("/metrics")
    public ApiResponse<MetricsResponse> metrics(@PathVariable Long projectId,
                                                @RequestParam(required = false) String window) {
        return ApiResponse.ok(dashboardService.metrics(
                projectId, currentUserProvider.getRequired().userId(), window));
    }
}
