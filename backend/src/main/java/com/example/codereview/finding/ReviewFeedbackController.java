package com.example.codereview.finding;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.api.ErrorCode;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.finding.ReviewFeedbackDtos.FeedbackRecord;
import com.example.codereview.finding.ReviewFeedbackDtos.FeedbackRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈闭环 REST 入口。两个端点同属反馈领域但基路径不同,故不设类级 @RequestMapping:
 * 提交挂在 run 资源之下(权限 = run 所属项目的访问权,service 内守卫);
 * 导出是平台级管理员操作,由 SecurityConfig 以 hasRole(ADMIN) 整体拦截。
 */
@RestController
public class ReviewFeedbackController {

    private final ReviewFeedbackService service;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;

    public ReviewFeedbackController(ReviewFeedbackService service,
                                    CurrentUserProvider currentUserProvider,
                                    ObjectMapper objectMapper) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/agent-runs/{runId}/feedback")
    public ApiResponse<FeedbackRecord> submit(@PathVariable Long runId,
                                              @Valid @RequestBody FeedbackRequest request) {
        return ApiResponse.ok(service.submit(runId, currentUserProvider.getRequired().userId(), request));
    }

    /**
     * JSON Lines(每行一条完整记录)而非 JSON 数组:r8 侧用行级工具(grep / jq -c)逐条策展,
     * 且多次导出追加合并时无需解析整体结构。反馈量级是"人手工提交",全量构串不构成内存压力。
     */
    @GetMapping(value = "/api/feedback/export", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String since)
            throws JsonProcessingException {
        StringBuilder body = new StringBuilder();
        for (FeedbackRecord record : service.export(parseSince(since))) {
            body.append(objectMapper.writeValueAsString(record)).append('\n');
        }
        // note/category 是中文自由文本,而 NDJSON 媒体类型本身不带 charset:交给 String 转换器
        // 会按 ISO-8859-1 落字节,中文全部变成 '?',导出的语料在 r8 回灌前就已损毁。
        // 显式按 UTF-8 编码并在 Content-Type 上声明(与 ReviewController 报告导出同口径)。
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_NDJSON, StandardCharsets.UTF_8))
                .body(bytes);
    }

    /**
     * 手工解析而不是声明 Instant 参数:类型绑定失败(MethodArgumentTypeMismatch)不在全局
     * 异常处理器清单里,会落进兜底 500——参数格式错必须是带指引的 400。
     */
    private static Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(since.strip());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "since 必须是 ISO-8601 UTC 时间戳,如 2026-08-14T00:00:00Z");
        }
    }
}
