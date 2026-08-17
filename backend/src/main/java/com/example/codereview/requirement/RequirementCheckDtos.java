package com.example.codereview.requirement;

import java.time.Instant;
import java.util.List;

/** 体检报告的六维结构(P2,R5)。report_json 落库与 REST 响应共用同一结构,防跨层口径漂移。 */
public final class RequirementCheckDtos {

    private RequirementCheckDtos() {
    }

    /** 六维(父 design §6);未知维度在解析层整体拒绝。 */
    public enum CheckDimension {
        COMPLETENESS,
        CLARITY,
        TESTABILITY,
        EXCEPTION_COVERAGE,
        RULE_CONFLICT,
        RISK
    }

    public record CheckItem(String source, String severity, String message, String suggestion) {
    }

    public record DimensionReport(String dimension, List<CheckItem> items) {
    }

    public record CheckReportResponse(
            Long reportId,
            Integer round,
            String model,
            int totalTokens,
            Instant createdAt,
            List<DimensionReport> dimensions
    ) {
    }

    /** LLM 层产物:六维条目 + 原始响应 + token 数(mock 路径 token=0)。 */
    public record LlmCheckResult(List<DimensionReport> dimensions, String rawResponse, int totalTokens) {
    }
}
