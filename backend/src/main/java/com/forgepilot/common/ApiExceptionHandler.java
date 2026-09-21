package com.forgepilot.common;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把各类失败统一转换成唯一的 {@link ApiError} 结构。数据库约束冲突到达这里时，
 * 其事务必定已经回滚：任何代码都不得捕获约束冲突后在同一事务里继续执行。
 *
 * <p>除业务异常外，Spring MVC 自己在进入控制器<em>之前</em>抛出的那几类异常
 * （请求体解析不了、路径参数类型不对、缺少参数、方法或媒体类型不支持、路径不存在）
 * 也必须在这里接住。实测：没有这些映射时它们会走容器的 {@code /error} 页，
 * 返回 Spring 默认的 {@code timestamp/status/error/path} 结构——没有 {@code code}
 * 与 {@code traceId}，前端只能显示一句「HTTP request failed」。最后的兜底把任何
 * 未预见的运行时异常也收进同一结构，并保证它们进日志时带着 traceId。
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
        return respond(HttpStatus.CONFLICT, "conflict", "请求与当前状态冲突。", exception);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
    ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request", "请求内容不符合要求。", exception);
    }

    /** 请求体解析不了、路径或参数类型不匹配、缺少必填参数：都是调用方能自行纠正的 400。 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class})
    ResponseEntity<ApiError> handleMalformedRequest(Exception exception) {
        return respond(HttpStatus.BAD_REQUEST, "bad_request", "请求格式不正确。", exception);
    }

    /**
     * 未知路径。与 {@link ApiException#notFound()} 用同一个 code：一个不存在的路径
     * 与一个不存在的资源对调用方来说是同一件事，也同样不该泄露任何存在性信息。
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ApiError> handleUnknownPath(Exception exception) {
        return respond(HttpStatus.NOT_FOUND, "not_found", "资源不存在。", exception);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "该路径不支持此请求方法。", exception);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
                "请求的媒体类型不受支持。", exception);
    }

    /** 兜底：未预见的失败也必须带 traceId 进日志，而不是以框架默认页逃出契约。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "服务器内部错误，请稍后重试。", exception);
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
