package com.example.codereview.mq;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.mq.MqLogDtos.MqLogResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mq/logs")
public class MqLogController {

    private final MqTaskLogRepository logs;

    public MqLogController(MqTaskLogRepository logs) {
        this.logs = logs;
    }

    @GetMapping
    public ApiResponse<List<MqLogResponse>> list(@RequestParam Long taskId) {
        return ApiResponse.ok(logs.findByTaskIdOrderByCreatedAtDesc(taskId)
                .stream()
                .map(MqLogResponse::from)
                .toList());
    }
}
