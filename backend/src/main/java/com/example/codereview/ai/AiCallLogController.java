package com.example.codereview.ai;

import com.example.codereview.ai.AiCallLogDtos.AiCallLogResponse;
import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.security.CurrentUserProvider;
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
    public ApiResponse<com.example.codereview.common.api.PageResponse<AiCallLogResponse>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        // limit 是信封化前的旧参数:未显式给 size 时沿用它,老调用方不至于突然缩到默认 20。
        Integer effectiveSize = size != null ? size : limit;
        return ApiResponse.ok(aiCallLogService.listPage(
                currentUserProvider.getRequired().userId(), projectId, taskId, page, effectiveSize));
    }
}
