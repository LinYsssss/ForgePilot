package com.example.codereview.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.codereview.common.exception.BusinessException;
import com.example.codereview.common.web.TraceIdFilter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Guards the contracts frozen in Phase 0 so that neither parallel workstream can drift them by
 * accident. See {@code docs/并行实施拆分方案.md}.
 *
 * <p>The point of these assertions is not that the values are interesting, but that they are
 * <em>stable</em>: the frontend and both backend tracks are written against them.
 */
class SharedApiContractTest {

    @AfterEach
    void clearTrace() {
        MDC.clear();
    }

    // ------------------------------------------------------------------ PageResponse

    @Test
    void pageResponseCarriesSpringPageMetadata() {
        PageImpl<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 7);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.items()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.totalPages()).isEqualTo(4);
    }

    @Test
    void pageResponseMapsElementsWithoutLosingMetadata() {
        PageImpl<String> page = new PageImpl<>(List.of("a"), PageRequest.of(0, 20), 1);

        PageResponse<Integer> response = PageResponse.from(page, String::length);

        assertThat(response.items()).containsExactly(1);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void pageSizeIsClampedIntoTheAgreedRange() {
        assertThat(PageResponse.sanitizeSize(null)).isEqualTo(PageResponse.DEFAULT_SIZE);
        assertThat(PageResponse.sanitizeSize(0)).isEqualTo(PageResponse.DEFAULT_SIZE);
        assertThat(PageResponse.sanitizeSize(-5)).isEqualTo(PageResponse.DEFAULT_SIZE);
        assertThat(PageResponse.sanitizeSize(50)).isEqualTo(50);
        assertThat(PageResponse.sanitizeSize(10_000)).isEqualTo(PageResponse.MAX_SIZE);
        assertThat(PageResponse.MAX_SIZE).isEqualTo(100);
        assertThat(PageResponse.DEFAULT_SIZE).isEqualTo(20);
    }

    @Test
    void pageIndexNeverGoesNegative() {
        assertThat(PageResponse.sanitizePage(null)).isZero();
        assertThat(PageResponse.sanitizePage(-3)).isZero();
        assertThat(PageResponse.sanitizePage(4)).isEqualTo(4);
    }

    // ------------------------------------------------------------------ ErrorCode

    @Test
    void genericLegacyCodesMapToTheirGenericErrorCode() {
        assertThat(ErrorCode.fromLegacy(400)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ErrorCode.fromLegacy(401)).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(ErrorCode.fromLegacy(403)).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ErrorCode.fromLegacy(404)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.fromLegacy(409)).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ErrorCode.fromLegacy(429)).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(ErrorCode.fromLegacy(500)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.fromLegacy(503)).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void aiAndGitLegacyCodesKeepTheirMeaning() {
        assertThat(ErrorCode.fromLegacy(6001)).isEqualTo(ErrorCode.GIT_COMMAND_FAILED);
        assertThat(ErrorCode.fromLegacy(6002)).isEqualTo(ErrorCode.REVIEW_TASK_NOT_FOUND);
        assertThat(ErrorCode.fromLegacy(6003)).isEqualTo(ErrorCode.AI_EMBEDDING_FAILED);
        assertThat(ErrorCode.fromLegacy(6004)).isEqualTo(ErrorCode.AI_CALL_FAILED);
        assertThat(ErrorCode.fromLegacy(6005)).isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
        assertThat(ErrorCode.fromLegacy(6006)).isEqualTo(ErrorCode.AI_CIRCUIT_OPEN);
    }

    @Test
    void unknownLegacyCodesFallBackWithoutInventingAnIdentifier() {
        assertThat(ErrorCode.fromLegacy(422)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ErrorCode.fromLegacy(502)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.fromLegacy(7777)).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void everyErrorCodeExposesAUsableHttpStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.httpStatus()).as("http status of %s", code).isBetween(200, 599);
            assertThat(code.defaultMessage()).as("message of %s", code).isNotBlank();
        }
    }

    // ------------------------------------------------------------------ ApiResponse

    @Test
    void successKeepsTheNumericZeroTheFrontendBranchesOn() {
        ApiResponse<String> response = ApiResponse.ok("payload");

        assertThat(response.code()).isZero();
        assertThat(response.errorCode()).isEqualTo("OK");
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data()).isEqualTo("payload");
    }

    @Test
    void errorCarriesBothTheNumericAndStringIdentifier() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.PROJECT_FORBIDDEN);

        assertThat(response.code()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo("PROJECT_FORBIDDEN");
        assertThat(response.message()).isEqualTo("无权访问该项目");
    }

    @Test
    void explicitNumericCodeIsNotReDerivedFromTheErrorCode() {
        // 6002 maps to REVIEW_TASK_NOT_FOUND, whose http status is 404 — but the caller's numeric
        // code must survive untouched, otherwise existing responses would silently change.
        ApiResponse<Void> response = ApiResponse.error(6002, ErrorCode.REVIEW_TASK_NOT_FOUND, "审查任务不存在");

        assertThat(response.code()).isEqualTo(6002);
        assertThat(response.errorCode()).isEqualTo("REVIEW_TASK_NOT_FOUND");
    }

    @Test
    void traceIdIsPickedUpFromMdcWhenPresent() {
        MDC.put(TraceIdFilter.TRACE_ID, "abc123");

        assertThat(ApiResponse.ok().traceId()).isEqualTo("abc123");
        assertThat(ApiResponse.error(ErrorCode.NOT_FOUND).traceId()).isEqualTo("abc123");
    }

    @Test
    void missingTraceIdIsNotAnError() {
        assertThat(ApiResponse.ok().traceId()).isNull();
    }

    // ------------------------------------------------------------------ BusinessException

    @Test
    void errorCodeConstructorDerivesStatusAndNumericCode() {
        BusinessException ex = new BusinessException(ErrorCode.AGENT_RUN_CONFLICT);

        assertThat(ex.getHttpStatus()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_RUN_CONFLICT);
        assertThat(ex.getMessage()).isEqualTo("Agent 运行状态冲突");
    }

    @Test
    void legacyConstructorStillDecidesStatusTheSameWayItAlwaysHas() {
        assertThat(new BusinessException(403, "无权访问该项目").getHttpStatus()).isEqualTo(403);
        // Codes outside the HTTP range have always fallen back to 400; that must not change.
        assertThat(new BusinessException(6002, "审查任务不存在").getHttpStatus()).isEqualTo(400);
        assertThat(new BusinessException(6002, "审查任务不存在").getCode()).isEqualTo(6002);
    }

    @Test
    void legacyConstructorStillGetsAStringIdentifier() {
        assertThat(new BusinessException(404, "项目不存在").getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(new BusinessException(6001, "Git 命令执行失败").getErrorCode())
                .isEqualTo(ErrorCode.GIT_COMMAND_FAILED);
    }

    @Test
    void derivedErrorCodeNeverContradictsTheResolvedHttpStatus() {
        // A 4xx response must not carry an errorCode that advertises 5xx, and vice versa. Only the
        // deliberately remapped legacy codes (6001-6006) are exempt: they predate the enum and
        // keep their historical status.
        int[] codes = {400, 401, 403, 404, 409, 413, 422, 429, 500, 502, 503, 7777, -1};
        for (int code : codes) {
            BusinessException ex = new BusinessException(code, "boom");
            boolean statusIsServerError = ex.getHttpStatus() >= 500;
            boolean errorCodeIsServerError = ex.getErrorCode().httpStatus() >= 500;
            assertThat(errorCodeIsServerError)
                    .as("legacy code %d resolved to status %d but errorCode %s", code, ex.getHttpStatus(),
                            ex.getErrorCode())
                    .isEqualTo(statusIsServerError);
        }
    }
}
