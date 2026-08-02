package com.example.codereview.agent.api;

import com.example.codereview.agent.api.AgentRunDtos.AgentRunDetail;
import com.example.codereview.agent.api.AgentRunDtos.AgentRunTimeline;
import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent Run timeline and control API. Reads return persisted state; {@code /events} adds an SSE
 * notification channel that resumes from {@code Last-Event-ID}. Every operation is authorized against
 * the run's owning project inside {@link AgentRunService}/{@link AgentEventService}.
 */
@RestController
@RequestMapping("/api/agent-runs")
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final AgentEventService agentEventService;
    private final CurrentUserProvider currentUserProvider;

    public AgentRunController(
            AgentRunService agentRunService,
            AgentEventService agentEventService,
            CurrentUserProvider currentUserProvider
    ) {
        this.agentRunService = agentRunService;
        this.agentEventService = agentEventService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentRunDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(agentRunService.detail(id, currentUserProvider.getRequired().userId()));
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<com.example.codereview.common.api.PageResponse<AgentRunDetail>> projectRuns(
            @PathVariable Long projectId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer page,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer size
    ) {
        return ApiResponse.ok(agentRunService.listForProject(
                projectId, currentUserProvider.getRequired().userId(), page, size));
    }

    @GetMapping("/{id}/timeline")
    public ApiResponse<AgentRunTimeline> timeline(@PathVariable Long id) {
        return ApiResponse.ok(agentRunService.timeline(id, currentUserProvider.getRequired().userId()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<AgentRunDetail> cancel(@PathVariable Long id) {
        return ApiResponse.ok(agentRunService.cancel(id, currentUserProvider.getRequired().userId()));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<AgentRunDetail> retry(@PathVariable Long id) {
        return ApiResponse.ok(agentRunService.retry(id, currentUserProvider.getRequired().userId()));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable Long id,
            @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId
    ) {
        return agentEventService.subscribe(id, currentUserProvider.getRequired().userId(), lastEventId);
    }
}
