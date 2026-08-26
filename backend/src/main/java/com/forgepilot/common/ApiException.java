package com.forgepilot.common;

import org.springframework.http.HttpStatus;

/** 允许被调用方看到的失败，自带它映射到的 HTTP 状态码与错误码。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 同时用于「资源不存在」<em>和</em>「资源存在但调用方无权看见」两种情况。
     * 二者必须不可区分，否则状态码会泄露别的项目是否存在该资源。
     */
    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "Resource not found.");
    }

    /**
     * 调用方能看见该资源，但不能执行这个操作。只在成员关系已确认之后才抛出，
     * 因此不会泄露任何新信息。
     */
    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", "This operation is not allowed for your role.");
    }

    /** 与当前状态冲突的请求，例如并发的 LEADER 转移。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "conflict", message);
    }

    /** 格式合法但被领域规则拒绝的请求，例如非法的状态流转。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
