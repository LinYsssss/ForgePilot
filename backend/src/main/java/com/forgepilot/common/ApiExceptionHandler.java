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
 * Turns failures into the single {@link ApiError} shape. A database constraint
 * conflict arrives here only after its transaction has already rolled back:
 * per D013.11 no code may catch one and carry on inside the same transaction.
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
        // The constraint text names internal columns, so it is logged, never returned.
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
        // The traceId is the only link between what the caller sees and the cause,
        // so it is minted and logged together with it.
        String traceId = UUID.randomUUID().toString();
        if (status.is5xxServerError()) {
            log.error("{} {} traceId={}", status.value(), code, traceId, exception);
        } else {
            log.warn("{} {} traceId={}: {}", status.value(), code, traceId, exception.toString());
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId));
    }
}
