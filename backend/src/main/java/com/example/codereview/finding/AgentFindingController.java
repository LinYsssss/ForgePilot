package com.example.codereview.finding;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.finding.AgentFindingDtos.AgentFindingResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/agent-runs/{agentRunId}/findings")
public class AgentFindingController {

    private final AgentFindingQueryService service;
    private final CurrentUserProvider currentUserProvider;

    public AgentFindingController(AgentFindingQueryService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public ApiResponse<List<AgentFindingResponse>> list(@PathVariable Long projectId, @PathVariable Long agentRunId) {
        return ApiResponse.ok(service.list(projectId, agentRunId, currentUserProvider.getRequired().userId()));
    }
}
