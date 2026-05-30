package com.example.codereview.ai;

import com.example.codereview.ai.AiCallLogDtos.AiCallLogResponse;
import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/logs")
public class AiCallLogController {

    private final AiCallLogService aiCallLogService;
    private final CurrentUserProvider currentUserProvider;

    public AiCallLogController(AiCallLogService aiCallLogService, CurrentUserProvider currentUserProvider) {
        this.aiCallLogService = aiCallLogService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public ApiResponse<List<AiCallLogResponse>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.ok(aiCallLogService.list(currentUserProvider.getRequired().userId(), projectId, taskId, limit));
    }
}
