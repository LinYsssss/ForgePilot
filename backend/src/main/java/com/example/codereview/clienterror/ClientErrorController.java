package com.example.codereview.clienterror;

import com.example.codereview.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端错误上报入口(生产加固 R4)。匿名可达:JS 报错大多发生在登录前或会话失效后,
 * 加认证门槛恰好挡掉最需要的样本。滥用面由三层收口:专用限流预算
 * ({@code app.ratelimit.client-errors-limit},默认 10/分/来源)、4KB 截断、只落日志不入库。
 * CSRF 豁免(见 SecurityConfig):sendBeacon 无法携带自定义头,且本端点不改任何服务端状态。
 */
@RestController
public class ClientErrorController {

    private final ClientErrorReportService service;

    public ClientErrorController(ClientErrorReportService service) {
        this.service = service;
    }

    @PostMapping("/api/client-errors")
    public ApiResponse<Void> report(@Valid @RequestBody ClientErrorReport report) {
        service.record(report);
        return ApiResponse.ok();
    }
}
