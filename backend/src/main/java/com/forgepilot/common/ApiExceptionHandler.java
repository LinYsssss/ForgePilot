package com.forgepilot.common;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把各类失败统一转换成唯一的 {@link ApiError} 结构。数据库约束冲突到达这里时，
 * 其事务必定已经回滚：按 D013.11，任何代码都不得捕获约束冲突后在同一事务里继续执行。
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return respond(exception.getStatus(), exception.getCode(), exception.getMessage(), exception);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(DataIntegrityViolationException exception) {
        // 约束文本里含内部列名，因此只写日志，绝不返回给调用方。
        return respond(HttpStatus.CONFLICT, "conflict",
                "The request conflicts with the current state.", exception);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleInvalidRequest(MethodArgumentNotValidException exception) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request",
                "The request body is invalid.", exception);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String message,
            Exception exception) {
        // traceId 是调用方所见与真实原因之间唯一的关联线索，
        // 因此在同一处生成并与异常一起写入日志。
        String traceId = UUID.randomUUID().toString();
        if (status.is5xxServerError()) {
            log.error("{} {} traceId={}", status.value(), code, traceId, exception);
        } else {
            log.warn("{} {} traceId={}: {}", status.value(), code, traceId, exception.toString());
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId));
    }
}
