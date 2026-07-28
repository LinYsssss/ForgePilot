package com.example.codereview.mq;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.api.PageResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.mq.MqLogDtos.MqLogResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mq/logs")
public class MqLogController {

    private final MqLogQueryService query;
    private final CurrentUserProvider currentUserProvider;

    public MqLogController(MqLogQueryService query, CurrentUserProvider currentUserProvider) {
        this.query = query;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Deliberately routed through {@link MqLogQueryService} rather than the repository: the
     * ownership check has to live somewhere the controller cannot skip, and hiding the entry point
     * in the UI is not authorization.
     */
    @GetMapping
    public ApiResponse<PageResponse<MqLogResponse>> list(
            @RequestParam Long taskId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Long userId = currentUserProvider.getRequired().userId();
        return ApiResponse.ok(query.list(taskId, userId, page, size));
    }
}
