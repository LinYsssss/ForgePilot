package com.example.codereview.review;

import com.example.codereview.common.api.ApiResponse;
import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.security.CurrentUserProvider;
import com.example.codereview.review.ReviewDtos.CreateReviewTaskRequest;
import com.example.codereview.review.ReviewDtos.ReviewReportDetail;
import com.example.codereview.review.ReviewDtos.ReviewReportSummary;
import com.example.codereview.review.ReviewDtos.ReviewTaskResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/reviews")
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewReportExporter reportExporter;
    private final CurrentUserProvider currentUserProvider;

    public ReviewController(ReviewService reviewService, ReviewReportExporter reportExporter,
                            CurrentUserProvider currentUserProvider) {
        this.reviewService = reviewService;
        this.reportExporter = reportExporter;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/tasks")
    public ApiResponse<ReviewTaskResponse> createTask(@PathVariable Long projectId, @Valid @RequestBody CreateReviewTaskRequest request) {
        return ApiResponse.ok(reviewService.create(projectId, currentUserProvider.getRequired().userId(), request));
    }

    @GetMapping("/tasks")
    public ApiResponse<com.example.codereview.common.api.PageResponse<ReviewTaskResponse>> tasks(
            @PathVariable Long projectId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(reviewService.listTasks(
                projectId, currentUserProvider.getRequired().userId(), page, size));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ReviewTaskResponse> taskDetail(@PathVariable Long projectId, @PathVariable Long taskId) {
        return ApiResponse.ok(reviewService.taskDetail(projectId, currentUserProvider.getRequired().userId(), taskId));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<ReviewTaskResponse> cancelTask(@PathVariable Long projectId, @PathVariable Long taskId) {
        return ApiResponse.ok(reviewService.cancelTask(projectId, currentUserProvider.getRequired().userId(), taskId));
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<Void> deleteTask(@PathVariable Long projectId, @PathVariable Long taskId) {
        reviewService.deleteTask(projectId, currentUserProvider.getRequired().userId(), taskId);
        return ApiResponse.ok();
    }

    @GetMapping("/reports")
    public ApiResponse<com.example.codereview.common.api.PageResponse<ReviewReportSummary>> reports(
            @PathVariable Long projectId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(reviewService.reports(
                projectId, currentUserProvider.getRequired().userId(), page, size));
    }

    @GetMapping("/reports/{reportId}")
    public ApiResponse<ReviewReportDetail> reportDetail(@PathVariable Long projectId, @PathVariable Long reportId) {
        return ApiResponse.ok(reviewService.reportDetail(projectId, currentUserProvider.getRequired().userId(), reportId));
    }

    @DeleteMapping("/reports/{reportId}")
    public ApiResponse<Void> deleteReport(@PathVariable Long projectId, @PathVariable Long reportId) {
        reviewService.deleteReport(projectId, currentUserProvider.getRequired().userId(), reportId);
        return ApiResponse.ok();
    }

    /**
     * 导出报告。返回原始文件(不包 ApiResponse),便于浏览器下载或 CI 直接消费:
     * {@code markdown} 供人阅读,{@code sarif} 可上传 GitHub Code Scanning。
     */
    @GetMapping("/reports/{reportId}/export")
    public ResponseEntity<byte[]> exportReport(@PathVariable Long projectId, @PathVariable Long reportId,
                                               @RequestParam(defaultValue = "markdown") String format) {
        ReviewReportDetail report = reviewService.reportDetail(
                projectId, currentUserProvider.getRequired().userId(), reportId);
        String normalized = format == null ? "markdown" : format.trim().toLowerCase();
        String body;
        MediaType contentType;
        String extension;
        switch (normalized) {
            case "sarif" -> {
                body = reportExporter.toSarif(report);
                contentType = MediaType.APPLICATION_JSON;
                extension = "sarif";
            }
            case "markdown", "md" -> {
                body = reportExporter.toMarkdown(report);
                contentType = MediaType.valueOf("text/markdown");
                extension = "md";
            }
            default -> throw new BusinessException(400, "不支持的导出格式：" + format);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"reposage-report-" + reportId + "." + extension + "\"")
                .body(bytes);
    }
}
